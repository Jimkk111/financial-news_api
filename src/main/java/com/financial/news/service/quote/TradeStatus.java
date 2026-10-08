package com.financial.news.service.quote;

/**
 * 交易状态（对外 API 契约字段 tradeStatus）
 *
 * @author financial-news
 * @since 1.0.0
 */
public enum TradeStatus {

    /** 未开盘（交易日开盘前，展示上一交易日数据） */
    PRE_OPEN,
    /** 交易中 */
    OPEN,
    /** 午间休市（保留上午数据） */
    LUNCH_BREAK,
    /** 已收盘（展示最近交易日数据） */
    CLOSED,
    /** 休市（周末/节假日/临时休市） */
    HOLIDAY,
    /** 停牌（展示停牌前最后数据） */
    SUSPENDED,
    /** 已退市（无最新报价，仅历史 K 线） */
    DELISTED
}
