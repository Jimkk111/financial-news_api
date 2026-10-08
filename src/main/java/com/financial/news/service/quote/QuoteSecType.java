package com.financial.news.service.quote;

import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;

import java.util.Arrays;

/**
 * 标的类型：股票 / 指数（详情接口路径的一部分，构成完整唯一标识）
 *
 * @author financial-news
 * @since 1.0.0
 */
public enum QuoteSecType {

    /** 股票 */
    STOCK(1, "stock"),
    /** 指数 */
    INDEX(2, "index");

    /** 数据库存储值 */
    private final int dbValue;
    /** API 路径取值 */
    private final String apiValue;

    QuoteSecType(int dbValue, String apiValue) {
        this.dbValue = dbValue;
        this.apiValue = apiValue;
    }

    public int getDbValue() {
        return dbValue;
    }

    public String getApiValue() {
        return apiValue;
    }

    public static QuoteSecType fromDbValue(Integer dbValue) {
        if (dbValue == null) {
            return null;
        }
        return Arrays.stream(values()).filter(t -> t.dbValue == dbValue).findFirst().orElse(null);
    }

    /**
     * 解析路径变量 {type}，非法值抛 QUOTE_SYMBOL_INVALID
     */
    public static QuoteSecType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.QUOTE_SYMBOL_INVALID);
        }
        return Arrays.stream(values())
                .filter(t -> t.apiValue.equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.QUOTE_SYMBOL_INVALID));
    }
}
