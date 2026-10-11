package com.financial.news.service.quote.provider;

/** 配额/并发限制不计入数据源故障熔断。 */
public class UpstreamQuotaException extends UpstreamException {
    private final int retryAfterSeconds;

    public UpstreamQuotaException(String message, int retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() { return retryAfterSeconds; }
}
