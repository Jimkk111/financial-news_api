package com.financial.news.service.crawler.ingest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
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
     * GET 文本内容，按 响应头 charset → HTML meta 嗅探 → 严格 UTF-8 → GBK 兜底 解码
     * <p>中文站点存在大量 GBK/GB2312 页面（如新浪老文章页），强制 UTF-8 会得到乱码正文。</p>
     */
    public String getString(String url, String referer) throws Exception {
        HttpResponse<byte[]> response = fetch(url, referer);
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            return "";
        }
        Charset charset = headerCharset(response.headers().firstValue("Content-Type").orElse(null));
        if (charset == null) {
            charset = sniffHtmlCharset(body);
        }
        if (charset != null) {
            return new String(body, charset);
        }
        // 严格 UTF-8：字节序列非法说明不是 UTF-8，回退 GBK
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(body)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(body, Charset.forName("GBK"));
        }
    }

    /**
     * GET 字节内容，带按 host 限速
     */
    public byte[] getBytes(String url, String referer) throws Exception {
        return fetch(url, referer).body();
    }

    private HttpResponse<byte[]> fetch(String url, String referer) throws Exception {
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
        return response;
    }

    /** 解析 Content-Type 里的 charset 参数 */
    private Charset headerCharset(String contentType) {
        if (contentType == null) {
            return null;
        }
        return charsetByName(java.util.regex.Pattern.compile("charset=([\\w-]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(contentType).results().findFirst().map(m -> m.group(1)).orElse(null));
    }

    /** 嗅探 HTML head 中的 meta charset 声明（只需扫描头部字节） */
    private Charset sniffHtmlCharset(byte[] body) {
        int len = Math.min(body.length, 4096);
        String head = new String(body, 0, len, StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<meta[^>]+charset=[\"']?([\\w-]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(head);
        return m.find() ? charsetByName(m.group(1)) : null;
    }

    private Charset charsetByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Charset.forName(name.trim());
        } catch (Exception e) {
            return null;
        }
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
