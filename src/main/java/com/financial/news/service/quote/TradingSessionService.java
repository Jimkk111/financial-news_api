package com.financial.news.service.quote;

import com.financial.news.entity.QuoteTradeCalendar;
import com.financial.news.mapper.QuoteTradeCalendarMapper;
import com.financial.news.service.quote.provider.model.QuoteSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;

/**
 * 交易时段与状态机：运行时只查日历表，不写任何"周几/节假日/冬夏令时"判断。
 * <p>日历缺行时按市场默认模板推导（工作日开市 + 固定时段，美股按冬/夏令时规则），
 * 保证冷启动可用，次日日历任务再校准。</p>
 * <p>美股时段跨日（北京时间 21:30–次日 04:00），close 早于 open 即视为跨到次日。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingSessionService {

    private final QuoteTradeCalendarMapper calendarMapper;

    /** 统一时区（北京时间） */
    public static final java.time.ZoneId ZONE = java.time.ZoneId.of("Asia/Shanghai");

    /** 某市场某日的会话视图 */
    public record SessionDay(LocalDate date, boolean open,
                             LocalTime s1Open, LocalTime s1Close,
                             LocalTime s2Open, LocalTime s2Close) {

        public boolean hasSecondSession() {
            return s2Open != null && s2Close != null;
        }
    }

    /**
     * 查询日历；无行时按模板推导（不落库，落库由日历同步任务负责）
     */
    public SessionDay getDay(MarketEnum market, LocalDate date) {
        QuoteTradeCalendar row = calendarMapper.selectByMarketAndDate(market.name(), date);
        if (row != null) {
            return new SessionDay(date, row.getIsOpen() != null && row.getIsOpen() == 1,
                    row.getSession1Open(), row.getSession1Close(), row.getSession2Open(), row.getSession2Close());
        }
        return defaultDay(market, date);
    }

    /**
     * 交易状态判定。
     *
     * @param securityStatus quote_security.status（1 正常 2 退市），可空
     * @param snapshot       快照（可为空；用于停牌启发式）
     */
    public TradeStatus resolveStatus(MarketEnum market, QuoteSecType secType, Integer securityStatus,
                                     LocalDateTime now, QuoteSnapshot snapshot) {
        if (securityStatus != null && securityStatus == 2) {
            return TradeStatus.DELISTED;
        }
        // 美股等跨日市场：先看昨日的时段窗口是否延续到今天凌晨（如昨日 21:30 开、今日 04:00 收）
        SessionDay yesterday = getDay(market, now.toLocalDate().minusDays(1));
        if (crossesMidnight(yesterday) && withinWindows(yesterday, now)) {
            return maybeSuspended(secType, snapshot, TradeStatus.OPEN);
        }
        SessionDay day = getDay(market, now.toLocalDate());
        if (!day.open()) {
            return TradeStatus.HOLIDAY;
        }
        List<LocalDateTime[]> windows = windowsOf(day);
        if (windows.isEmpty()) {
            return TradeStatus.HOLIDAY;
        }
        if (now.isBefore(windows.get(0)[0])) {
            return TradeStatus.PRE_OPEN;
        }
        for (int i = 0; i < windows.size(); i++) {
            LocalDateTime[] window = windows.get(i);
            if (!now.isBefore(window[0]) && !now.isAfter(window[1])) {
                return maybeSuspended(secType, snapshot, TradeStatus.OPEN);
            }
            boolean hasNext = i + 1 < windows.size();
            if (hasNext && now.isAfter(window[1]) && now.isBefore(windows.get(i + 1)[0])) {
                return TradeStatus.LUNCH_BREAK;
            }
        }
        return TradeStatus.CLOSED;
    }

    /** 当前是否处于任一交易时段内（预热任务与 delayed 判定用），含昨日跨日时段的延续 */
    public boolean isInSession(MarketEnum market, LocalDateTime now) {
        SessionDay yesterday = getDay(market, now.toLocalDate().minusDays(1));
        if (crossesMidnight(yesterday) && withinWindows(yesterday, now)) {
            return true;
        }
        SessionDay day = getDay(market, now.toLocalDate());
        return day.open() && withinWindows(day, now);
    }

    /**
     * 距下次开盘的时长（休市缓存 TTL 用）。扫描不到日历时返回兜底 12h。
     */
    public Duration untilNextOpen(MarketEnum market, LocalDateTime now) {
        LocalDate start = now.toLocalDate();
        for (int offset = 0; offset < 45; offset++) {
            LocalDate date = start.plusDays(offset);
            SessionDay day = getDay(market, date);
            if (!day.open()) {
                continue;
            }
            LocalDateTime openAt = LocalDateTime.of(date, day.s1Open());
            if (offset == 0 && now.isAfter(openAt)) {
                continue;
            }
            Duration until = Duration.between(now, openAt);
            if (!until.isNegative()) {
                return until;
            }
        }
        return Duration.ofHours(12);
    }

    /** 上一交易日（从 ref-1 起向前找，最多 30 天） */
    public LocalDate lastTradingDate(MarketEnum market, LocalDate ref) {
        for (int offset = 1; offset <= 30; offset++) {
            LocalDate date = ref.minusDays(offset);
            if (getDay(market, date).open()) {
                return date;
            }
        }
        return ref.minusDays(1);
    }

    /**
     * 分时点在折叠午休轴上的分钟偏移：
     * 上午段 = time - s1Open；下午段 = (s1Close - s1Open) + 1 + (time - s2Open)；美股单段跨日直接算差。
     * 返回空表示时间早于开盘（异常数据），调用方跳过该点。
     */
    public Optional<Integer> minuteOffset(SessionDay day, LocalDateTime time) {
        if (!day.open() || day.s1Open() == null) {
            return Optional.empty();
        }
        LocalDateTime s1Start = LocalDateTime.of(day.date(), day.s1Open());
        if (!time.isBefore(s1Start)) {
            LocalDateTime s1End = endOf(day.date(), day.s1Open(), day.s1Close());
            if (!time.isAfter(s1End)) {
                return Optional.of((int) Duration.between(s1Start, time).toMinutes());
            }
        }
        if (day.hasSecondSession()) {
            LocalDateTime s2Start = LocalDateTime.of(day.date(), day.s2Open());
            if (!time.isBefore(s2Start)) {
                int morning = (int) Duration.between(s1Start, endOf(day.date(), day.s1Open(), day.s1Close())).toMinutes();
                return Optional.of(morning + 1 + (int) Duration.between(s2Start, time).toMinutes());
            }
        }
        // 单段跨日市场（美股）：凌晨部分
        if (time.isBefore(s1Start) && !day.hasSecondSession()) {
            int minutes = (int) Duration.between(s1Start, time.plusHours(24)).toMinutes();
            if (minutes >= 0) {
                return Optional.of(minutes);
            }
        }
        return Optional.empty();
    }

    /** 停牌启发式：盘中/午休但上游无价格，或全天无成交且高低收等于昨收 → SUSPENDED（仅股票） */
    private TradeStatus maybeSuspended(QuoteSecType secType, QuoteSnapshot snapshot, TradeStatus inSession) {
        if (secType != QuoteSecType.STOCK || snapshot == null) {
            return inSession;
        }
        if (snapshot.getLatestPrice() == null) {
            return TradeStatus.SUSPENDED;
        }
        if (snapshot.getVolume() != null && snapshot.getVolume() == 0
                && snapshot.getHigh() != null && snapshot.getHigh().equals(snapshot.getLow())
                && snapshot.getPrevClose() != null && snapshot.getHigh().equals(snapshot.getPrevClose())) {
            return TradeStatus.SUSPENDED;
        }
        return inSession;
    }

    /** close 早于 open 表示跨日（美股） */
    private LocalDateTime endOf(LocalDate date, LocalTime open, LocalTime close) {
        LocalDateTime end = LocalDateTime.of(date, close);
        return close.isBefore(open) ? end.plusDays(1) : end;
    }

    /** 该日是否包含跨日时段（如美股 21:30–次日 04:00） */
    private boolean crossesMidnight(SessionDay day) {
        return day.open() && day.s1Open() != null && day.s1Close() != null && day.s1Close().isBefore(day.s1Open());
    }

    /** 该日的所有时段窗口 [start, end]（跨日已折算到绝对时间） */
    private List<LocalDateTime[]> windowsOf(SessionDay day) {
        if (!day.open() || day.s1Open() == null || day.s1Close() == null) {
            return List.of();
        }
        java.util.List<LocalDateTime[]> windows = new java.util.ArrayList<>();
        windows.add(new LocalDateTime[]{LocalDateTime.of(day.date(), day.s1Open()),
                endOf(day.date(), day.s1Open(), day.s1Close())});
        if (day.hasSecondSession()) {
            windows.add(new LocalDateTime[]{LocalDateTime.of(day.date(), day.s2Open()),
                    endOf(day.date(), day.s2Open(), day.s2Close())});
        }
        return windows;
    }

    /** now 是否落在该日的任一时段窗口内 */
    private boolean withinWindows(SessionDay day, LocalDateTime now) {
        for (LocalDateTime[] window : windowsOf(day)) {
            if (!now.isBefore(window[0]) && !now.isAfter(window[1])) {
                return true;
            }
        }
        return false;
    }

    /** 日历缺行时/未来日期的默认会话模板（日历同步任务也用它生成未来行） */
    public SessionDay defaultDay(MarketEnum market, LocalDate date) {
        boolean weekday = date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
        return switch (market) {
            case CN -> new SessionDay(date, weekday,
                    LocalTime.of(9, 30), LocalTime.of(11, 30), LocalTime.of(13, 0), LocalTime.of(15, 0));
            case HK -> new SessionDay(date, weekday,
                    LocalTime.of(9, 30), LocalTime.of(12, 0), LocalTime.of(13, 0), LocalTime.of(16, 0));
            case US -> {
                // 美东夏令时：3 月第二个周日起至 11 月第一个周日（含）前
                LocalDate dstStart = date.withMonth(3).with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.SUNDAY));
                LocalDate dstEnd = date.withMonth(11).with(TemporalAdjusters.dayOfWeekInMonth(1, DayOfWeek.SUNDAY));
                boolean dst = !date.isBefore(dstStart) && date.isBefore(dstEnd);
                yield new SessionDay(date, weekday,
                        dst ? LocalTime.of(21, 30) : LocalTime.of(22, 30),
                        dst ? LocalTime.of(4, 0) : LocalTime.of(5, 0),
                        null, null);
            }
        };
    }
}
