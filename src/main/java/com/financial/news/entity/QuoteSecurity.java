package com.financial.news.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 行情标的主表（搜索、退市标注、主源 secid 映射的唯一事实源）
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuoteSecurity {

    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_DELISTED = 2;

    private Integer id;

    /** 完整标识 code.SUFFIX，如 600519.SH、AAPL.US */
    private String symbol;

    /** 类型：1 股票 2 指数（QuoteSecType.dbValue） */
    private Integer secType;

    /** 市场：CN/HK/US */
    private String market;

    /** 证券名称 */
    private String name;

    /** 名称拼音首字母（大写，仅字母数字），如 GZMT */
    private String pinyinAbbr;

    /** 状态：1 正常 2 已退市 */
    private Integer status;

    /** 币种代码：CNY/HKD/USD */
    private String currency;

    /** 主源内部标识，如 1.600519、116.00700、124.HSTECH */
    private String upstreamSecid;

    /** 连续同步未出现次数（退市判定） */
    private Integer missingDays;

    /** 最近一次出现在上游列表的日期 */
    private LocalDate lastActiveDate;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
