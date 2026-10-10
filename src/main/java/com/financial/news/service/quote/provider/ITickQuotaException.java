package com.financial.news.service.quote.provider;

/** 本地配额耗尽不代表上游故障，不计入熔断。 */
public class ITickQuotaException extends UpstreamException {
    public ITickQuotaException() { super("iTick 每分钟请求额度已用完，请稍后重试"); }
}
