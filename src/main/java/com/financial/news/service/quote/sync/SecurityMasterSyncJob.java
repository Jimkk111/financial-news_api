package com.financial.news.service.quote.sync;

import com.financial.news.config.QuoteProperties;
import com.financial.news.entity.QuoteSecurity;
import com.financial.news.mapper.QuoteSecurityMapper;
import com.financial.news.service.quote.MarketEnum;
import com.financial.news.service.quote.PinyinAbbrUtil;
import com.financial.news.service.quote.QuoteSecType;
import com.financial.news.service.quote.TradingSessionService;
import com.financial.news.service.quote.provider.EastMoneyQuoteProvider;
import com.financial.news.service.quote.provider.UpstreamException;
import com.financial.news.service.quote.provider.model.UpstreamSecurity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 标的主表同步任务：每日全量拉取三市场证券列表 upsert（名称变更重算拼音），
 * 连续多日未出现（阈值可配）判定退市。启动时异步更新，各市场失败后分别重试。
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityMasterSyncJob {

    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<MarketEnum, LocalDateTime> successfulSyncs = new ConcurrentHashMap<>();

    /** fs 板块过滤：沪深北 A 股；港股；美股三大所 */
    private static final Map<MarketEnum, String> FS_BY_MARKET = Map.of(
            MarketEnum.CN, "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048",
            MarketEnum.HK, "m:116+t:3",
            MarketEnum.US, "m:105,m:106,m:107");

    private final EastMoneyQuoteProvider eastMoneyProvider;
    private final QuoteSecurityMapper securityMapper;
    private final QuoteProperties properties;

    @Scheduled(cron = "${quote.sync.security-cron}")
    public void scheduledSync() {
        sync();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        Thread thread = new Thread(this::sync, "quote-security-boot");
        thread.setDaemon(true);
        thread.start();
    }

    /** 每个市场分别重试，避免一个市场成功后其他市场永久停留在种子名单。 */
    @Scheduled(fixedDelayString = "${quote.sync.security-retry-ms:300000}", initialDelay = 300000)
    public void retryIncomplete() {
        syncMarkets(true);
    }

    public LocalDateTime lastSuccessfulSync(MarketEnum market) {
        return successfulSyncs.get(market);
    }

    public void sync() {
        syncMarkets(false);
    }

    private void syncMarkets(boolean retryOnly) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            LocalDate today = LocalDate.now(TradingSessionService.ZONE);
            int threshold = Math.max(1, properties.getSync().getDelistMissingRounds());
            for (MarketEnum market : MarketEnum.values()) {
                LocalDateTime last = successfulSyncs.get(market);
                if (retryOnly && last != null && last.toLocalDate().equals(today)) {
                    continue;
                }
                try {
                    syncMarket(market, today, threshold);
                    successfulSyncs.put(market, LocalDateTime.now(TradingSessionService.ZONE));
                } catch (UpstreamException e) {
                    log.error("[行情] {} 标的主表同步失败（保留旧数据）: {}", market, e.getMessage());
                } catch (Exception e) {
                    log.error("[行情] {} 标的主表同步异常: {}", market, e.getMessage(), e);
                }
            }
        } finally {
            running.set(false);
        }
    }

    private void syncMarket(MarketEnum market, LocalDate today, int delistThreshold) {
        if (market == MarketEnum.HK) {
            int excluded = securityMapper.excludeLegacyHkStructuredProducts();
            if (excluded > 0) {
                log.info("[行情] 已隐藏历史港股结构性产品：{} 条", excluded);
            }
        }
        List<UpstreamSecurity> rows = eastMoneyProvider.listAllSecurities(FS_BY_MARKET.get(market));
        if (rows.isEmpty()) {
            throw new UpstreamException("证券列表为空");
        }
        Set<String> seen = new HashSet<>();
        // 先验证整批，再写库和计算退市；部分列表不得触发退市判定。
        for (UpstreamSecurity row : rows) {
            String symbol = toSymbol(row);
            if (symbol == null || !seen.add(symbol)) {
                throw new UpstreamException("证券列表含无效或重复标的");
            }
            boolean belongsToMarket = switch (market) {
                case CN -> symbol.endsWith(".SH") || symbol.endsWith(".SZ") || symbol.endsWith(".BJ");
                case HK -> symbol.endsWith(".HK");
                case US -> symbol.endsWith(".US");
            };
            if (!belongsToMarket) {
                throw new UpstreamException("证券列表包含其他市场标的");
            }
        }
        long oldCount = securityMapper.countActiveStocksByMarket(market.name());
        long stockCount = rows.stream().filter(UpstreamSecurity::stock).count();
        // 港股范围由全部证券缩小为正股，历史总数不可作为此时的正股数量基线。
        if (stockCount < 100 || (market != MarketEnum.HK && rows.size() < oldCount * 0.8)) {
            throw new UpstreamException("证券列表数量异常，拒绝覆盖现有主表: " + rows.size());
        }
        for (UpstreamSecurity row : rows) {
            String symbol = toSymbol(row);
            String name = truncate(row.name(), 64);
            securityMapper.upsert(QuoteSecurity.builder()
                    .symbol(symbol)
                    .secType(QuoteSecType.STOCK.getDbValue())
                    .market(market.name())
                    .name(name)
                    .pinyinAbbr(truncate(PinyinAbbrUtil.abbr(name), 40))
                    .status(row.stock() ? QuoteSecurity.STATUS_ACTIVE : QuoteSecurity.STATUS_NON_STOCK)
                    .currency(market.getCurrency())
                    .upstreamSecid(row.marketNum() == -1 ? "sina:" + row.code()
                            : row.marketNum() + "." + row.code())
                    .missingDays(0)
                    .lastActiveDate(today)
                    .build());
        }
        int delistedNow = 0;
        if (market == MarketEnum.HK) {
            int excluded = securityMapper.excludeMissingStocks(market.name(), List.copyOf(seen));
            log.info("[行情] 港股非正股历史记录已隐藏：{} 条", excluded);
        }
        // 备用目录与主源覆盖范围不同，只补充/更新，不据此判定旧标的退市。
        boolean officialDirectory = rows.stream().anyMatch(row -> row.marketNum() == -1);
        for (QuoteSecurity active : securityMapper.selectActiveStocksByMarket(market.name())) {
            if (officialDirectory || seen.contains(active.getSymbol())) {
                continue;
            }
            delistedNow += securityMapper.incrementMissing(active.getSymbol(), today, delistThreshold);
        }
        log.info("[行情] {} 标的主表同步完成：上游 {} 行，股票 {} 行，非股票 {} 行，本次更新退市候选 {} 行",
                market, rows.size(), stockCount, rows.size() - stockCount, delistedNow);
    }

    /** 上游行 → 完整 symbol；格式不合规（异常代码）返回 null 跳过 */
    private String toSymbol(UpstreamSecurity row) {
        String code = row.code().toUpperCase(Locale.ROOT);
        String suffix = switch (row.marketNum()) {
            case 1 -> "SH";
            case 0 -> code.startsWith("4") || code.startsWith("8") || code.startsWith("92") ? "BJ" : "SZ";
            case 116 -> "HK";
            case 105, 106, 107 -> "US";
            case -1 -> "US";
            default -> null;
        };
        if (suffix == null) {
            return null;
        }
        boolean valid = switch (suffix) {
            case "SH", "SZ", "BJ" -> code.matches("\\d{6}");
            case "HK" -> code.matches("\\d{5}");
            case "US" -> code.matches("[A-Z0-9._$-]{1,14}");
            default -> false;
        };
        return valid ? code + "." + suffix : null;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.codePointCount(0, value.length()) <= maxLength) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxLength));
    }
}
