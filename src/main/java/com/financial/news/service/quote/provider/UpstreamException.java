package com.financial.news.service.quote.provider;

/**
 * 上游数据源异常（主/备源均失败时由 Router 抛出，服务层转为 QUOTE_UPSTREAM_FAILED）
 *
 * @author financial-news
 * @since 1.0.0
 */
public class UpstreamException extends RuntimeException {

    public UpstreamException(String message) {
        super(message);
    }

    public UpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
