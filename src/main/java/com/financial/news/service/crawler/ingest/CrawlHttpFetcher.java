package com.financial.news.service.crawler.ingest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 采集共享 HTTP 客户端
 * <p>统一 UA/Accept 头、超时与按 host 限速（礼貌抓取，降低被封风险）。
 * 单实例内存限速；多实例部署时需升级为 Redis 令牌桶。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Component
public class CrawlHttpFetcher {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${crawler.ingest.rate-limit-interval-ms:1500}")
    private long minIntervalMs;

    @Value("${crawler.agent.http-timeout:30}")
    private int httpTimeout;

    private final Map<String, Object> hostLocks = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAccess = new ConcurrentHashMap<>();

    /**
     * GET 文本内容（UTF-8），带按 host 限速
     */
    public String getString(String url, String referer) throws Exception {
        return new String(getBytes(url, referer), StandardCharsets.UTF_8);
    }

    /**
     * GET 字节内容，带按 host 限速
     */
    public byte[] getBytes(String url, String referer) throws Exception {
        URI uri = URI.create(url);
        rateLimit(uri.getHost());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(httpTimeout))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/json,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET();
        if (referer != null && !referer.isBlank()) {
            builder.header("Referer", referer);
        }
        HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    /**
     * 按 host 限速：同一 host 两次请求间隔不小于 minIntervalMs
     */
    private void rateLimit(String host) {
        if (host == null) {
            return;
        }
        Object lock = hostLocks.computeIfAbsent(host.toLowerCase(), k -> new Object());
        synchronized (lock) {
            Long last = lastAccess.get(host.toLowerCase());
            long now = System.currentTimeMillis();
            if (last != null) {
                long wait = minIntervalMs - (now - last);
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            lastAccess.put(host.toLowerCase(), System.currentTimeMillis());
        }
    }
}
