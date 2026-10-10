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

    private static final Pattern ITICK_NON_EQUITY = Pattern.compile(
            "\\b(?:ETF|ETN|warrants?|units?|rights|bonds?|debentures?)\\b|"
                    + "\\b(?:preferred|preference)\\s+(?:stock|shares?|securities)\\b|"
                    + "\\b(?:senior|subordinated|secured|unsecured|exchange.traded)\\s+notes?\\b",
            Pattern.CASE_INSENSITIVE);

    public static String cleanName(String name) {
        return org.jsoup.Jsoup.parse(name).text().replaceAll("[\\s\\u00a0]+", " ").trim();
    }

    public static boolean isItickStock(String region, String code, String name, String type) {
        if (!"stock".equalsIgnoreCase(type) || name.isBlank()) return false;
        if ("SH".equals(region)) return code.matches("6\\d{5}");
        if ("SZ".equals(region)) return code.matches("(?:00|30)\\d{4}");
        if ("US".equals(region)) {
            return code.matches("[A-Z0-9._-]{1,14}") && !ITICK_NON_EQUITY.matcher(name).find();
        }
        if (!"HK".equals(region) || !code.matches("\\d{1,5}")) return false;
        int number = Integer.parseInt(code);
        int[][] excluded = {{10000,29999},{47000,48999},{49500,69999},{4800,4999},
                {2800,2849},{3000,3199},{3400,3599},{7200,7399},{7500,7599},{7700,7799},
                {9000,9599},{9700,9849},{41500,41599},{82800,82849},{83000,83199},
                {83400,83599},{87200,87399},{87500,87599},{87700,87799},{89200,89599},
                {4600,4699},{84600,84699}};
        for (int[] range : excluded) if (number >= range[0] && number <= range[1]) return false;
        return !name.matches(".*[一二三四五六七八九十甲乙0-9]{2}[牛熊购沽購][A-Z0-9]+[#*]?$");
    }
}
