package com.financial.news.service.quote.provider.model;

/**
 * 上游证券列表行（主表同步用）
 *
 * @param code      交易所代码，如 600519、00700、AAPL
 * @param marketNum 上游市场号：1 沪 0 深 116 港 105/106/107 美股；-1 为 Nasdaq 官方目录，内部 secid 未知
 * @param name      证券名称
 * @param stock     是否属于股票展示范围（false 的现有记录会隐藏）
 * @author financial-news
 * @since 1.0.0
 */
public record UpstreamSecurity(String code, int marketNum, String name, boolean stock) {
    public UpstreamSecurity(String code, int marketNum, String name) {
        this(code, marketNum, name, true);
    }
}
