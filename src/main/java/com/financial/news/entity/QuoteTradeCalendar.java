package com.financial.news.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 行情交易日历（逐市场逐日预计算，运行时只查表）
 * <p>美股时段跨日：session1_close（次日 04:00/05:00）早于 session1_open（21:30/22:30）即表示跨日</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuoteTradeCalendar {

    public static final String SOURCE_AUTO = "AUTO";
    public static final String SOURCE_MANUAL = "MANUAL";

    private Integer id;

    /** 市场：CN/HK/US */
    private String market;

    /** 日期（北京时间） */
    private LocalDate tradeDate;

    /** 是否交易日：0 否 1 是 */
    private Integer isOpen;

    /** 第一时段开盘（北京时间） */
    private LocalTime session1Open;

    /** 第一时段收盘 */
    private LocalTime session1Close;

    /** 第二时段开盘，无则 NULL */
    private LocalTime session2Open;

    /** 第二时段收盘 */
    private LocalTime session2Close;

    /** 来源：AUTO 推导 / MANUAL 人工（不被同步任务覆盖） */
    private String source;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
