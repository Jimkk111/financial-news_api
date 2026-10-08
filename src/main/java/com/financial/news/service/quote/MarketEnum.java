package com.financial.news.service.quote;

import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;

import java.util.Arrays;

/**
 * 行情市场枚举
 *
 * @author financial-news
 * @since 1.0.0
 */
public enum MarketEnum {

    /** A股（沪深） */
    CN("CNY"),
    /** 港股 */
    HK("HKD"),
    /** 美股 */
    US("USD");

    /** 币种代码 */
    private final String currency;

    MarketEnum(String currency) {
        this.currency = currency;
    }

    public String getCurrency() {
        return currency;
    }

    /**
     * 大小写不敏感解析，非法值抛 QUOTE_MARKET_INVALID
     */
    public static MarketEnum parse(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.QUOTE_MARKET_INVALID);
        }
        return Arrays.stream(values())
                .filter(m -> m.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.QUOTE_MARKET_INVALID));
    }
}
