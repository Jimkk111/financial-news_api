package com.financial.news.service.quote;

import java.util.regex.Pattern;

/** 普通股票范围：ETF 使用官方类型标志，其余衍生证券使用完整证券描述识别。 */
public final class StockUniverseFilter {
    private static final Pattern NON_EQUITY = Pattern.compile(
            "\\b(?:warrants?|units?|preferred|preference|rights|bonds?|debentures?|"
                    + "(?:senior|subordinated|secured|unsecured|exchange.traded)\\s+notes?|ETFs?|ETNs?)\\b",
            Pattern.CASE_INSENSITIVE);

    private StockUniverseFilter() {
    }

    public static boolean isUsStock(String code, String name, String etf) {
        return "N".equals(etf) && !code.contains("$") && !NON_EQUITY.matcher(name).find();
    }
}
