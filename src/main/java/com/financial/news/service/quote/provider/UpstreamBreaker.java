package com.financial.news.service.quote.provider;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 上游简易熔断器：连续失败达阈值打开一段时间，期间直接跳过该源；打开期满放行一次试探
 *
 * @author financial-news
 * @since 1.0.0
 */
public class UpstreamBreaker {

    private final int failThreshold;
    private final long openMillis;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntilEpochMs = new AtomicLong();

    public UpstreamBreaker(int failThreshold, long openMillis) {
        this.failThreshold = Math.max(1, failThreshold);
        this.openMillis = openMillis;
    }

    /** 是否允许发起调用 */
    public boolean allow() {
        return System.currentTimeMillis() >= openUntilEpochMs.get();
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
    }

    public void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= failThreshold) {
            openUntilEpochMs.set(System.currentTimeMillis() + openMillis);
            consecutiveFailures.set(0);
        }
    }
}
