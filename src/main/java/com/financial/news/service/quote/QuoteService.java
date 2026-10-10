package com.financial.news.service.quote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.config.QuoteProperties;
import com.financial.news.dto.response.quote.HotListVO;
import com.financial.news.dto.response.quote.IndexListVO;
import com.financial.news.dto.response.quote.KlineBarVO;
import com.financial.news.dto.response.quote.KlineMaVO;
import com.financial.news.dto.response.quote.QuoteBaseVO;
import com.financial.news.dto.response.quote.QuoteKlineVO;
import com.financial.news.dto.response.quote.QuoteSnapshotVO;
import com.financial.news.dto.response.quote.QuoteTrendVO;
import com.financial.news.dto.response.quote.TrendPointVO;
import com.financial.news.dto.response.quote.StockListVO;
import com.financial.news.service.quote.sync.SecurityMasterSyncJob;
import com.financial.news.entity.QuoteHotList;
import com.financial.news.entity.QuoteSecurity;
import com.financial.news.mapper.QuoteHotListMapper;
import com.financial.news.mapper.QuoteSecurityMapper;
import com.financial.news.service.quote.provider.QuoteProviderRouter;
import com.financial.news.service.quote.provider.model.KlineBar;
import com.financial.news.service.quote.provider.model.QuoteSnapshot;
import com.financial.news.service.quote.provider.model.TrendData;
import com.financial.news.service.quote.provider.model.TrendPoint;
import com.financial.news.service.quote.provider.model.UpstreamTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 行情门面服务：指数卡 / 热门列表 / 快照 / 分时 / 日 K。
 * <p>缓存存 provider 领域模型，交易状态、数据日期、delayed 一律在组装 VO 时按当前时间计算，
 * 不缓存中间态。E7（单市场失败不影响其他市场）由"按市场分段请求 + 分段缓存键"保证。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuoteService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private static final String KEY_IDX = "quote:idx:";
    private static final String KEY_HOT = "quote:hot:";
    private static final String KEY_SNAP = "quote:snap:";
    private static final String KEY_TREND = "quote:trend:";
    private static final String KEY_KLINE = "quote:kline:";

    private static final TypeReference<List<QuoteSnapshot>> SNAPSHOT_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<QuoteSnapshot> SNAPSHOT_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<TrendData> TREND_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<KlineBar>> KLINE_TYPE = new TypeReference<>() {
    };

    private static final int DEFAULT_KLINE_COUNT = 250;
    private static final int MAX_KLINE_COUNT = 500;
    private static final int MA_WINDOW_MAX = 60;

    private final QuoteProviderRouter router;
    private final QuoteCacheService cache;
    private final TradingSessionService tradingSession;
    private final QuoteSecurityMapper securityMapper;
    private final QuoteHotListMapper hotListMapper;
    private final QuoteProperties properties;
    private final SecurityMasterSyncJob securitySync;

    public StockListVO getStockList(String marketRaw, Integer page, Integer pageSize) {
        MarketEnum market = MarketEnum.parse(marketRaw);
        int number = page == null ? 1 : Math.max(1, page);
        int size = pageSize == null ? 50 : Math.min(100, Math.max(1, pageSize));
        long offset = (number - 1L) * size;
        long total = securityMapper.countActiveStocksByMarket(market.name());
        List<QuoteSecurity> securities = offset >= total ? List.of()
                : securityMapper.selectStockPage(market.name(), offset, size);
        Map<String, QuoteSnapshot> snapshots = Map.of();
        long fetchedAt = 0;
        if (!securities.isEmpty()) {
            Duration fresh = Duration.ofSeconds(properties.getCache().getListFreshSeconds());
            // 内容指纹避免同步新增股票后，旧页缓存与当前页标的错位。
            String symbols = securities.stream().map(QuoteSecurity::getSymbol).collect(Collectors.joining(","));
            try {
                var cached = cache.getOrLoad("quote:stocks:" + market.name() + ":" + symbols,
                        SNAPSHOT_LIST_TYPE, fresh, staleTtl(fresh), storeTtl(market, fresh),
                        () -> fetchSnapshots(securities));
                fetchedAt = cached.fetchedAtEpochSec();
                snapshots = cached.value().stream().collect(Collectors.toMap(
                        QuoteSnapshot::getSymbol, Function.identity(), (a, b) -> a));
            } catch (RuntimeException e) {
                log.warn("[行情] {} 股票列表报价暂不可用，保留股票信息: {}", market, e.getMessage());
            }
        }
        List<QuoteSnapshotVO> stocks = new ArrayList<>();
        for (QuoteSecurity security : securities) {
            QuoteSnapshot snapshot = snapshots.get(security.getSymbol());
            if (snapshot != null) {
                stocks.add(toSnapshotVO(security, QuoteSecType.STOCK, market, snapshot, fetchedAt));
            } else {
                QuoteSnapshotVO item = new QuoteSnapshotVO();
                item.setSecType("stock");
                item.setSymbol(security.getSymbol());
                item.setName(security.getName());
                item.setMarket(market.name());
                item.setCurrency(security.getCurrency());
                item.setDelayed(true);
                stocks.add(item);
            }
        }
        var syncedAt = securitySync.lastSuccessfulSync(market);
        return new StockListVO(market.name(), number, size, total, offset + size < total,
                syncedAt != null, syncedAt == null ? null : syncedAt.toString(),
                stocks.stream().anyMatch(QuoteBaseVO::isDelayed), stocks);
    }

    // ==================== 指数卡 ====================

    public IndexListVO getIndexCards(String marketRaw) {
        MarketEnum market = MarketEnum.parse(marketRaw);
        List<String> symbols = properties.getIndices() == null
                ? List.of() : properties.getIndices().getOrDefault(market.name(), List.of());
        if (symbols.isEmpty()) {
            return new IndexListVO(market.name(), false, List.of());
        }
        List<QuoteSecurity> securities = securityMapper.selectBySymbolsAndType(symbols, QuoteSecType.INDEX.getDbValue());
        Duration fresh = Duration.ofSeconds(properties.getCache().getListFreshSeconds());
        QuoteCacheService.CachedResult<List<QuoteSnapshot>> cached = cache.getOrLoad(
                KEY_IDX + market.name(), SNAPSHOT_LIST_TYPE, fresh, staleTtl(fresh), storeTtl(market, fresh),
                () -> fetchSnapshots(securities));
        Map<String, QuoteSnapshot> bySymbol = cached.value().stream()
                .collect(Collectors.toMap(QuoteSnapshot::getSymbol, Function.identity(), (a, b) -> a));
        List<QuoteSnapshotVO> items = new ArrayList<>();
        for (QuoteSecurity security : orderedSecurities(symbols, securities)) {
            QuoteSnapshot snapshot = bySymbol.get(security.getSymbol());
            if (snapshot == null) {
                continue;
            }
            QuoteSnapshotVO vo = toSnapshotVO(security, QuoteSecType.INDEX, market, snapshot, cached.fetchedAtEpochSec());
            items.add(vo);
        }
        boolean delayed = items.stream().anyMatch(QuoteBaseVO::isDelayed);
        return new IndexListVO(market.name(), delayed, items);
    }

    // ==================== 热门列表 ====================

    public HotListVO getHotList(String marketRaw, Integer limit) {
        MarketEnum market = MarketEnum.parse(marketRaw);
        int size = limit == null ? 20 : Math.min(Math.max(limit, 1), 50);
        List<QuoteHotList> rows = hotListMapper.selectEnabledByMarket(market.name());
        List<String> symbols = rows.stream().map(QuoteHotList::getSymbol).toList();
        if (symbols.isEmpty()) {
            return new HotListVO(market.name(), false, List.of());
        }
        List<QuoteSecurity> securities = securityMapper.selectBySymbolsAndType(symbols, QuoteSecType.STOCK.getDbValue());
        Duration fresh = Duration.ofSeconds(properties.getCache().getListFreshSeconds());
        QuoteCacheService.CachedResult<List<QuoteSnapshot>> cached = cache.getOrLoad(
                KEY_HOT + market.name(), SNAPSHOT_LIST_TYPE, fresh, staleTtl(fresh), storeTtl(market, fresh),
                () -> fetchSnapshots(securities));
        Map<String, QuoteSnapshot> bySymbol = cached.value().stream()
                .collect(Collectors.toMap(QuoteSnapshot::getSymbol, Function.identity(), (a, b) -> a));
        List<QuoteSnapshotVO> items = new ArrayList<>();
        for (QuoteSecurity security : orderedSecurities(symbols, securities)) {
            QuoteSnapshot snapshot = bySymbol.get(security.getSymbol());
            if (snapshot == null) {
                continue;
            }
            items.add(toSnapshotVO(security, QuoteSecType.STOCK, market, snapshot, cached.fetchedAtEpochSec()));
            if (items.size() >= size) {
                break;
            }
        }
        boolean delayed = items.stream().anyMatch(QuoteBaseVO::isDelayed);
        return new HotListVO(market.name(), delayed, items);
    }

    // ==================== 单标的快照 ====================

    public QuoteSnapshotVO getSnapshot(String typeRaw, String symbolRaw) {
        QuoteSecType type = QuoteSecType.parse(typeRaw);
        String symbol = normalizeSymbol(symbolRaw, type);
        MarketEnum market = marketOf(symbol);
        QuoteSecurity security = resolveSecurity(type, symbol, market);
        Duration fresh = Duration.ofSeconds(properties.getCache().getSnapshotFreshSeconds());
        QuoteCacheService.CachedResult<QuoteSnapshot> cached;
        try {
            cached = cache.getOrLoad(
                    KEY_SNAP + type.getApiValue() + ":" + symbol, SNAPSHOT_TYPE, fresh, staleTtl(fresh), storeTtl(market, fresh),
                    () -> {
                        QuoteSnapshot snapshot = fetchOne(security);
                        if (snapshot == null) {
                            throw new UpstreamEmptyException(symbol);
                        }
                        return snapshot;
                    });
        } catch (UpstreamEmptyException e) {
            throw new BusinessException(ErrorCode.QUOTE_NOT_FOUND);
        }
        return toSnapshotVO(security, type, market, cached.value(), cached.fetchedAtEpochSec());
    }

    // ==================== 分时 ====================

    public QuoteTrendVO getTrend(String typeRaw, String symbolRaw) {
        QuoteSecType type = QuoteSecType.parse(typeRaw);
        String symbol = normalizeSymbol(symbolRaw, type);
        MarketEnum market = marketOf(symbol);
        QuoteSecurity security = resolveSecurity(type, symbol, market);
        Duration fresh = Duration.ofSeconds(properties.getCache().getTrendFreshSeconds());
        QuoteCacheService.CachedResult<TrendData> cached = cache.getOrLoad(
                KEY_TREND + type.getApiValue() + ":" + symbol, TREND_TYPE, fresh, staleTtl(fresh), storeTtl(market, fresh),
                () -> router.getTrend(toTarget(security, type, market)));

        TrendData trend = cached.value();
        List<TrendPoint> points = trend.getPoints() == null ? List.of() : trend.getPoints();
        // 分时归属交易日取首点日期：美股跨日时段（北京时间 21:30–次日 04:00）的交易日是开盘当日
        LocalDate dataDate = points.isEmpty()
                ? LocalDate.now(TradingSessionService.ZONE)
                : points.get(0).getTime().toLocalDate();
        TradingSessionService.SessionDay day = tradingSession.getDay(market, dataDate);

        QuoteTrendVO vo = new QuoteTrendVO();
        fillHead(vo, security, type, market, null, cached.fetchedAtEpochSec(), properties.getCache().getTrendFreshSeconds(), dataDate);
        vo.setPrevClose(trend.getPrevClose());
        vo.setTimelineMinutes(timelineMinutes(market));
        List<TrendPointVO> pointVos = new ArrayList<>(points.size());
        for (TrendPoint point : points) {
            Optional<Integer> minute = tradingSession.minuteOffset(day, point.getTime());
            if (minute.isEmpty()) {
                continue;
            }
            pointVos.add(new TrendPointVO(minute.get(),
                    point.getTime().toLocalTime().format(MINUTE_FORMAT),
                    point.getPrice(), point.getAvgPrice(), point.getVolume()));
        }
        vo.setPoints(pointVos);
        if (!points.isEmpty()) {
            vo.setDataTime(points.get(points.size() - 1).getTime().toLocalTime().format(TIME_FORMAT));
        }
        return vo;
    }

    // ==================== 日 K ====================

    public QuoteKlineVO getKline(String typeRaw, String symbolRaw, Integer count) {
        QuoteSecType type = QuoteSecType.parse(typeRaw);
        String symbol = normalizeSymbol(symbolRaw, type);
        MarketEnum market = marketOf(symbol);
        QuoteSecurity security = resolveSecurity(type, symbol, market);
        int size = count == null ? DEFAULT_KLINE_COUNT : Math.min(Math.max(count, 20), MAX_KLINE_COUNT);
        Duration fresh = Duration.ofSeconds(properties.getCache().getKlineFreshSeconds());
        QuoteCacheService.CachedResult<List<KlineBar>> cached = cache.getOrLoad(
                KEY_KLINE + type.getApiValue() + ":" + symbol + ":" + size, KLINE_TYPE, fresh, staleTtl(fresh),
                storeTtl(market, fresh),
                () -> router.getKline(toTarget(security, type, market), size + MA_WINDOW_MAX));

        List<KlineBar> bars = cached.value();
        int from = Math.max(0, bars.size() - size);
        List<KlineBar> window = bars.subList(from, bars.size());

        QuoteKlineVO vo = new QuoteKlineVO();
        LocalDate dataDate = window.isEmpty() ? LocalDate.now(TradingSessionService.ZONE)
                : window.get(window.size() - 1).getDate();
        fillHead(vo, security, type, market, null, cached.fetchedAtEpochSec(), properties.getCache().getKlineFreshSeconds(), dataDate);
        vo.setFq("qfq");
        // MA 与涨跌幅在全量历史上计算后按窗口对齐截取，保证输出首根的均线/涨跌幅可用
        List<KlineBarVO> allBars = toBarVos(bars);
        KlineMaVO allMa = new KlineMaVO(ma(bars, 5), ma(bars, 10), ma(bars, 20), ma(bars, 60));
        vo.setBars(new ArrayList<>(allBars.subList(from, allBars.size())));
        vo.setMa(trimMa(allMa, from));
        return vo;
    }

    // ==================== 私有组装 ====================

    private List<QuoteSnapshot> fetchSnapshots(List<QuoteSecurity> securities) {
        if (securities.isEmpty()) {
            return List.of();
        }
        List<UpstreamTarget> targets = securities.stream()
                .map(s -> toTarget(s, QuoteSecType.fromDbValue(s.getSecType()), MarketEnum.parse(s.getMarket())))
                .toList();
        return router.getSnapshots(targets);
    }

    private QuoteSnapshot fetchOne(QuoteSecurity security) {
        QuoteSecType type = QuoteSecType.fromDbValue(security.getSecType());
        MarketEnum market = MarketEnum.parse(security.getMarket());
        List<QuoteSnapshot> list = router.getSnapshots(List.of(toTarget(security, type, market)));
        return list.isEmpty() ? null : list.get(0);
    }

    private UpstreamTarget toTarget(QuoteSecurity security, QuoteSecType type, MarketEnum market) {
        return new UpstreamTarget(security.getUpstreamSecid(), security.getSymbol(), type, market);
    }

    /** 按配置/名单顺序排列已解析的标的（缺失的跳过） */
    private List<QuoteSecurity> orderedSecurities(List<String> symbols, List<QuoteSecurity> securities) {
        Map<String, QuoteSecurity> bySymbol = securities.stream()
                .collect(Collectors.toMap(QuoteSecurity::getSymbol, Function.identity(), (a, b) -> a));
        List<QuoteSecurity> ordered = new ArrayList<>(symbols.size());
        for (String symbol : symbols) {
            QuoteSecurity security = bySymbol.get(symbol);
            if (security != null) {
                ordered.add(security);
            }
        }
        return ordered;
    }

    private QuoteSnapshotVO toSnapshotVO(QuoteSecurity security, QuoteSecType type, MarketEnum market,
                                         QuoteSnapshot snapshot, long fetchedAtSec) {
        QuoteSnapshotVO vo = new QuoteSnapshotVO();
        fillHead(vo, security, type, market, snapshot, fetchedAtSec,
                properties.getCache().getSnapshotFreshSeconds(), null);
        vo.setLatestPrice(snapshot.getLatestPrice());
        // PRD 5.2-2：涨跌额与涨跌幅统一保留 2 位小数
        vo.setChangeAmount(round2Optional(snapshot.getChangeAmount()));
        vo.setChangePercent(round2Optional(snapshot.getChangePercent()));
        vo.setOpen(snapshot.getOpen());
        vo.setPrevClose(snapshot.getPrevClose());
        vo.setHigh(snapshot.getHigh());
        vo.setLow(snapshot.getLow());
        vo.setVolume(snapshot.getVolume());
        vo.setTurnover(snapshot.getTurnover());
        return vo;
    }

    private void fillHead(QuoteBaseVO vo, QuoteSecurity security, QuoteSecType type, MarketEnum market,
                          QuoteSnapshot snapshot, long fetchedAtSec, int freshSeconds, LocalDate dataDateOverride) {
        LocalDateTime now = LocalDateTime.now(TradingSessionService.ZONE);
        vo.setSecType(type.getApiValue());
        vo.setSymbol(security.getSymbol());
        vo.setName(snapshot != null && snapshot.getName() != null ? snapshot.getName() : security.getName());
        vo.setMarket(market.name());
        vo.setCurrency(security.getCurrency() != null ? security.getCurrency() : market.getCurrency());
        TradeStatus status = tradingSession.resolveStatus(market, type, security.getStatus(), now, snapshot);
        vo.setTradeStatus(status.name());
        long dataEpoch = snapshot != null && snapshot.getDataTimeEpochSec() != null
                ? snapshot.getDataTimeEpochSec() : fetchedAtSec;
        LocalDateTime dataTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(dataEpoch), TradingSessionService.ZONE);
        vo.setDataDate(dataDateOverride != null ? dataDateOverride.toString() : dataTime.toLocalDate().toString());
        vo.setDataTime(dataTime.toLocalTime().format(TIME_FORMAT));
        vo.setDelayed(computeDelayed(market, fetchedAtSec, freshSeconds, dataEpoch));
    }

    /**
     * delayed = 交易时段内缓存拉取时间超过新鲜窗口（降级读旧），
     * 或盘中数据自身时间戳明显滞后（上游延迟，PRD 5.3 要求明示）。休市时最近交易日数据是正常语义，不标延迟。
     */
    private boolean computeDelayed(MarketEnum market, long fetchedAtSec, int freshSeconds, long dataEpochSec) {
        if (!tradingSession.isInSession(market, LocalDateTime.now(TradingSessionService.ZONE))) {
            return false;
        }
        long nowSec = Instant.now().getEpochSecond();
        if (nowSec - fetchedAtSec > freshSeconds) {
            return true;
        }
        long staleDataSeconds = Math.max(60L, freshSeconds * 2L);
        return nowSec - dataEpochSec > staleDataSeconds;
    }

    private Duration storeTtl(MarketEnum market, Duration fresh) {
        if (tradingSession.isInSession(market, LocalDateTime.now(TradingSessionService.ZONE))) {
            return fresh;
        }
        Duration untilOpen = tradingSession.untilNextOpen(market, LocalDateTime.now(TradingSessionService.ZONE));
        Duration capped = Duration.ofSeconds(properties.getCache().getClosedMaxTtlSeconds());
        return untilOpen.compareTo(capped) < 0 ? untilOpen : capped;
    }

    private Duration staleTtl(Duration fresh) {
        // stale 窗口取 fresh 的 20 倍且至少 10 分钟（快照 15s → 10min；K 线 5min → 100min）
        Duration computed = fresh.multipliedBy(20);
        return computed.compareTo(Duration.ofMinutes(10)) < 0 ? Duration.ofMinutes(10) : computed;
    }

    private int timelineMinutes(MarketEnum market) {
        return switch (market) {
            case CN -> 240;
            case HK -> 330;
            case US -> 390;
        };
    }

    private List<KlineBarVO> toBarVos(List<KlineBar> window) {
        List<KlineBarVO> vos = new ArrayList<>(window.size());
        Double prevClose = null;
        for (KlineBar bar : window) {
            Double changePercent = prevClose == null || prevClose == 0
                    ? null : (bar.getClose() - prevClose) / prevClose * 100;
            vos.add(new KlineBarVO(bar.getDate().toString(), bar.getOpen(), bar.getHigh(), bar.getLow(),
                    bar.getClose(), bar.getVolume(), bar.getTurnover(), changePercent));
            prevClose = bar.getClose();
        }
        return vos;
    }

    private KlineMaVO trimMa(KlineMaVO allMa, int from) {
        return new KlineMaVO(trim(allMa.getMa5(), from), trim(allMa.getMa10(), from),
                trim(allMa.getMa20(), from), trim(allMa.getMa60(), from));
    }

    private List<Double> trim(List<Double> values, int from) {
        if (from <= 0) {
            return values;
        }
        return new ArrayList<>(values.subList(Math.min(from, values.size()), values.size()));
    }

    /** 按窗口计算与 bars 下标对齐的均线，窗口不足为 null（E5） */
    private List<Double> ma(List<KlineBar> window, int size) {
        List<Double> result = new ArrayList<>(window.size());
        for (int i = 0; i < window.size(); i++) {
            if (i + 1 < size) {
                result.add(null);
                continue;
            }
            double sum = 0;
            boolean valid = true;
            for (int j = i - size + 1; j <= i; j++) {
                Double close = window.get(j).getClose();
                if (close == null) {
                    valid = false;
                    break;
                }
                sum += close;
            }
            result.add(valid ? round2(sum / size) : null);
        }
        return result;
    }

    private Double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private Double round2Optional(Double value) {
        return value == null ? null : round2(value);
    }

    // ==================== 标的解析 ====================

    private QuoteSecurity resolveSecurity(QuoteSecType type, String symbol, MarketEnum market) {
        QuoteSecurity security = securityMapper.selectBySymbolAndType(symbol, type.getDbValue());
        if (security != null) {
            return security;
        }
        String secid = deriveSecid(type, market, symbol);
        if (secid == null) {
            throw new BusinessException(ErrorCode.QUOTE_NOT_FOUND);
        }
        // 主表暂无（如直接输入未被同步任务收录的代码）：可推导 secid 时构造瞬态标的兜底
        return QuoteSecurity.builder()
                .symbol(symbol)
                .secType(type.getDbValue())
                .market(market.name())
                .name(symbol)
                .status(QuoteSecurity.STATUS_ACTIVE)
                .currency(market.getCurrency())
                .upstreamSecid(secid)
                .missingDays(0)
                .build();
    }

    private String deriveSecid(QuoteSecType type, MarketEnum market, String symbol) {
        int dot = symbol.lastIndexOf('.');
        String code = symbol.substring(0, dot);
        String suffix = symbol.substring(dot + 1);
        return switch (market) {
            case CN -> "SH".equals(suffix) ? "1." + code : "0." + code;
            case HK -> type == QuoteSecType.STOCK ? "116." + code : HK_INDEX_SECID.get(code);
            case US -> type == QuoteSecType.INDEX ? US_INDEX_SECID.get(code) : null;
        };
    }

    private static final Map<String, String> HK_INDEX_SECID = Map.of(
            "HSI", "100.HSI",
            "HSTECH", "124.HSTECH",
            "HSCEI", "100.HSCEI");

    private static final Map<String, String> US_INDEX_SECID = Map.of(
            "DJI", "100.DJIA",
            "IXIC", "100.NDX",
            "SPX", "100.SPX");

    /**
     * 校验并规范化 symbol（大写）。规则：
     * 沪深北 6 位数字 + .SH/.SZ/.BJ；港股股票 5 位数字/.HK；港股指数字母/.HK；美股代码/.US
     */
    private String normalizeSymbol(String raw, QuoteSecType type) {
        if (raw == null) {
            throw new BusinessException(ErrorCode.QUOTE_SYMBOL_INVALID);
        }
        String symbol = raw.trim().toUpperCase();
        int dot = symbol.lastIndexOf('.');
        if (dot <= 0 || dot == symbol.length() - 1) {
            throw new BusinessException(ErrorCode.QUOTE_SYMBOL_INVALID);
        }
        String code = symbol.substring(0, dot);
        String suffix = symbol.substring(dot + 1);
        boolean valid = switch (suffix) {
            case "SH", "SZ", "BJ" -> code.matches("\\d{6}");
            case "HK" -> type == QuoteSecType.INDEX ? code.matches("[A-Z]{1,10}") : code.matches("\\d{5}");
            case "US" -> code.matches("[A-Z0-9._]{1,10}");
            default -> false;
        };
        if (!valid) {
            throw new BusinessException(ErrorCode.QUOTE_SYMBOL_INVALID);
        }
        return symbol;
    }

    private MarketEnum marketOf(String symbol) {
        String suffix = symbol.substring(symbol.lastIndexOf('.') + 1);
        return switch (suffix) {
            case "SH", "SZ", "BJ" -> MarketEnum.CN;
            case "HK" -> MarketEnum.HK;
            case "US" -> MarketEnum.US;
            default -> throw new BusinessException(ErrorCode.QUOTE_SYMBOL_INVALID);
        };
    }

    /** 上游返回空行（标的无效或未上市）时使用，转为 QUOTE_NOT_FOUND */
    private static class UpstreamEmptyException extends RuntimeException {
        private UpstreamEmptyException(String symbol) {
            super("上游无该标的数据: " + symbol);
        }
    }
}
