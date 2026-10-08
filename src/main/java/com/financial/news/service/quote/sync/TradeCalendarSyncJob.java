package com.financial.news.service.quote.sync;

import com.financial.news.config.QuoteProperties;
import com.financial.news.entity.QuoteTradeCalendar;
import com.financial.news.mapper.QuoteTradeCalendarMapper;
import com.financial.news.service.quote.MarketEnum;
import com.financial.news.service.quote.QuoteSecType;
import com.financial.news.service.quote.TradingSessionService;
import com.financial.news.service.quote.provider.QuoteProviderRouter;
import com.financial.news.service.quote.provider.model.KlineBar;
import com.financial.news.service.quote.provider.model.UpstreamTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 交易日历同步任务：用各市场基准指数（上证指数/恒指/道指）的日 K 日期推导真实交易日，
 * 覆盖节假日与临时休市；未来日期按市场默认模板（含美股冬/夏令时）规则推导，逐日校准。
 * <p>MANUAL 来源行（人工修正）不被覆盖。启动时表为空立即执行一次。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeCalendarSyncJob {

    /** 各市场基准指数（日 K 有值的日期即交易日） */
    private static final Map<MarketEnum, String> BENCHMARK_SECID = Map.of(
            MarketEnum.CN, "1.000001",
            MarketEnum.HK, "100.HSI",
            MarketEnum.US, "100.DJIA");

    private final QuoteProviderRouter router;
    private final QuoteTradeCalendarMapper calendarMapper;
    private final TradingSessionService tradingSession;
    private final QuoteProperties properties;

    @Scheduled(cron = "${quote.sync.calendar-cron}")
    public void scheduledSync() {
        sync();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncIfEmpty() {
        if (calendarMapper.countAll() == 0) {
            Thread thread = new Thread(this::sync, "quote-calendar-boot");
            thread.setDaemon(true);
            thread.start();
        }
    }

    public void sync() {
        LocalDate today = LocalDate.now(TradingSessionService.ZONE);
        for (MarketEnum market : MarketEnum.values()) {
            try {
                syncMarket(market, today);
            } catch (Exception e) {
                log.error("[行情] {} 交易日历同步失败: {}", market, e.getMessage(), e);
            }
        }
    }

    private void syncMarket(MarketEnum market, LocalDate today) throws InterruptedException {
        String benchmark = BENCHMARK_SECID.get(market);
        List<KlineBar> bars = router.getKline(
                new UpstreamTarget(benchmark, benchmark, QuoteSecType.INDEX, market), 140);
        Set<LocalDate> tradingDates = bars.stream().map(KlineBar::getDate).collect(Collectors.toSet());
        if (tradingDates.isEmpty()) {
            log.warn("[行情] {} 基准指数日 K 为空，跳过本次日历同步", market);
            return;
        }

        LocalDate rangeStart = today.minusDays(10);
        LocalDate rangeEnd = today.plusDays(Math.max(30, properties.getSync().getCalendarForwardDays()));
        Map<LocalDate, QuoteTradeCalendar> existing = calendarMapper
                .selectRange(market.name(), rangeStart, rangeEnd).stream()
                .collect(Collectors.toMap(QuoteTradeCalendar::getTradeDate, Function.identity()));

        int inserted = 0;
        int updated = 0;
        for (LocalDate date = rangeStart; !date.isAfter(rangeEnd); date = date.plusDays(1)) {
            QuoteTradeCalendar expected = expectedRow(market, date, today, tradingDates);
            if (expected == null) {
                continue;
            }
            QuoteTradeCalendar old = existing.get(date);
            if (old == null) {
                calendarMapper.insert(expected);
                inserted++;
            } else if (!QuoteTradeCalendar.SOURCE_MANUAL.equals(old.getSource()) && differs(old, expected)) {
                expected.setId(old.getId());
                calendarMapper.updateAuto(expected);
                updated++;
            }
            // 分页写库间稍作停歇，避免任务启动瞬间连续打满连接池
            if ((inserted + updated) % 200 == 0 && inserted + updated > 0) {
                Thread.sleep(50);
            }
        }
        log.info("[行情] {} 交易日历同步完成：新增 {} 行，更新 {} 行，区间 {} ~ {}",
                market, inserted, updated, rangeStart, rangeEnd);
    }

    /**
     * 期望日历行：过去以基准指数日 K 为准（有 K 即开市）；今天与未来按工作日规则推导（次日自愈）。
     */
    private QuoteTradeCalendar expectedRow(MarketEnum market, LocalDate date, LocalDate today,
                                           Set<LocalDate> tradingDates) {
        boolean isOpen;
        if (tradingDates.contains(date)) {
            isOpen = true;
        } else if (date.isBefore(today)) {
            isOpen = false;
        } else {
            isOpen = tradingSession.defaultDay(market, date).open();
        }
        TradingSessionService.SessionDay template = tradingSession.defaultDay(market, date);
        if (isOpen) {
            return buildRow(market, date, 1, template);
        }
        return QuoteTradeCalendar.builder()
                .market(market.name()).tradeDate(date).isOpen(0)
                .source(QuoteTradeCalendar.SOURCE_AUTO)
                .build();
    }

    private QuoteTradeCalendar buildRow(MarketEnum market, LocalDate date, int open,
                                        TradingSessionService.SessionDay template) {
        return QuoteTradeCalendar.builder()
                .market(market.name())
                .tradeDate(date)
                .isOpen(open)
                .session1Open(template.s1Open())
                .session1Close(template.s1Close())
                .session2Open(template.s2Open())
                .session2Close(template.s2Close())
                .source(QuoteTradeCalendar.SOURCE_AUTO)
                .build();
    }

    private boolean differs(QuoteTradeCalendar old, QuoteTradeCalendar expected) {
        return old.getIsOpen() == null || expected.getIsOpen() == null
                || !old.getIsOpen().equals(expected.getIsOpen())
                || !java.util.Objects.equals(old.getSession1Open(), expected.getSession1Open())
                || !java.util.Objects.equals(old.getSession1Close(), expected.getSession1Close())
                || !java.util.Objects.equals(old.getSession2Open(), expected.getSession2Open())
                || !java.util.Objects.equals(old.getSession2Close(), expected.getSession2Close());
    }
}
