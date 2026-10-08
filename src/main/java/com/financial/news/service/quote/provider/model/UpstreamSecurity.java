package com.financial.news.service.quote.provider.model;

/**
 * 上游证券列表行（主表同步用）
 *
 * @param code      交易所代码，如 600519、00700、AAPL
 * @param marketNum 上游市场号：1 沪 0 深 116 港 105/106/107 美股
 * @param name      证券名称
 * @author financial-news
 * @since 1.0.0
 */
public record UpstreamSecurity(String code, int marketNum, String name) {
}
