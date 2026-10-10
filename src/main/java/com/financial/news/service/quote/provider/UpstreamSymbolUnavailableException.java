package com.financial.news.service.quote.provider;

/** 单个证券未被数据源收录，不代表数据源服务故障。 */
public class UpstreamSymbolUnavailableException extends UpstreamException {
    public UpstreamSymbolUnavailableException(String message) {
        super(message);
    }
}
