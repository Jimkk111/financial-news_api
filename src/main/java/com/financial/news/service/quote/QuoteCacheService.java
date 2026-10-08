package com.financial.news.service.quote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 行情缓存：Redis 双 TTL（fresh 窗口内直接返回；stale 窗口内上游故障时降级返回旧值，
 * 是否展示"数据延迟"由调用方按 fetchedAt + 交易时段判定）+ JVM 内 per-key single-flight 防击穿。
 * <p>缓存值统一为 {fetchedAt, data} 信封 JSON；data 由调用方以 TypeReference 还原。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuoteCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 上游取数专用线程池（有界，快速失败，不占用 Tomcat 工作线程长等待） */
    private final ExecutorService upstreamExecutor = Executors.newFixedThreadPool(8, r -> {
        Thread thread = new Thread(r, "quote-upstream-" + r.hashCode());
        thread.setDaemon(true);
        return thread;
    });

    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

    /**
     * 取数结果：value + 数据拉取时间（epoch 秒）
     */
    public record CachedResult<T>(T value, long fetchedAtEpochSec) {
    }

    /**
     * 读缓存/加载：
     * 1) fresh 窗口内 → 直接返回；
     * 2) 超窗 → single-flight 加载，成功则回写（TTL=storeTtl）；
     * 3) 加载失败 → stale 窗口内有旧值则返回旧值（调用方据 fetchedAt 判 delayed），否则抛出原异常。
     */
    public <T> CachedResult<T> getOrLoad(String key, TypeReference<T> type,
                                         Duration freshTtl, Duration staleTtl, Duration storeTtl,
                                         Supplier<T> loader) {
        long nowSec = Instant.now().getEpochSecond();
        Envelope cached = readEnvelope(key);
        if (cached != null && nowSec - cached.fetchedAt < freshTtl.toSeconds()) {
            return decode(cached, type);
        }
        try {
            T value = loadSingleFlight(key, loader);
            store(key, value, storeTtl);
            return new CachedResult<>(value, nowSec);
        } catch (RuntimeException e) {
            if (cached != null && nowSec - cached.fetchedAt < staleTtl.toSeconds()) {
                log.warn("[行情] 上游失败，降级返回缓存 {}: {}", key, e.getMessage());
                return decode(cached, type);
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T loadSingleFlight(String key, Supplier<T> loader) {
        CompletableFuture<Object> future = inflight.computeIfAbsent(key, k ->
                CompletableFuture.supplyAsync(() -> (Object) loader.get(), upstreamExecutor)
                        .whenComplete((r, e) -> inflight.remove(k)));
        try {
            return (T) future.join();
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof RuntimeException re ? re : new IllegalStateException(cause);
        }
    }

    private void store(String key, Object value, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(
                    new Envelope(Instant.now().getEpochSecond(), objectMapper.valueToTree(value)));
            redisTemplate.opsForValue().set(key, json, ttl);
        } catch (Exception e) {
            log.warn("[行情] 缓存回写失败 {}: {}", key, e.getMessage());
        }
    }

    private Envelope readEnvelope(String key) {
        try {
            String raw = redisTemplate.opsForValue().get(key);
            if (raw == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(raw);
            return new Envelope(root.path("fetchedAt").asLong(), root.path("data"));
        } catch (Exception e) {
            log.warn("[行情] 缓存读取失败 {}: {}", key, e.getMessage());
            return null;
        }
    }

    private <T> CachedResult<T> decode(Envelope envelope, TypeReference<T> type) {
        try {
            return new CachedResult<>(objectMapper.treeToValue(envelope.data, type), envelope.fetchedAt);
        } catch (Exception e) {
            throw new IllegalStateException("行情缓存反序列化失败: " + e.getMessage(), e);
        }
    }

    private record Envelope(long fetchedAt, JsonNode data) {
    }
}
