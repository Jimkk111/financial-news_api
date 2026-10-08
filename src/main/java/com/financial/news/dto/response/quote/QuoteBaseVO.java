package com.financial.news.dto.response.quote;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 行情响应公共头（交易状态 + 数据日期 + 延迟标注）
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class QuoteBaseVO {

    /** stock | index */
    private String secType;

    /** 完整标识，如 600519.SH */
    private String symbol;

    private String name;

    /** CN | HK | US */
    private String market;

    /** CNY | HKD | USD */
    private String currency;

    /** PRE_OPEN | OPEN | LUNCH_BREAK | CLOSED | HOLIDAY | SUSPENDED | DELISTED */
    private String tradeStatus;

    /** 行情数据归属交易日 yyyy-MM-dd（休市时为最近交易日） */
    private String dataDate;

    /** 数据时间 HH:mm:ss */
    private String dataTime;

    /** true=数据延迟/降级，前端必须展示"数据延迟"提示 */
    private boolean delayed;
}
