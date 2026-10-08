package com.financial.news.service.quote.provider.model;

import com.financial.news.service.quote.MarketEnum;
import com.financial.news.service.quote.QuoteSecType;

/**
 * 上游取数目标（对外 symbol 已在服务层解析完毕，这里只携带主源内部标识）
 *
 * @param secid    主源内部标识，如 1.600519、116.00700、124.HSTECH
 * @param symbol   完整标识，如 600519.SH
 * @param secType  股票/指数
 * @param market   市场
 * @author financial-news
 * @since 1.0.0
 */
public record UpstreamTarget(String secid, String symbol, QuoteSecType secType, MarketEnum market) {
}
