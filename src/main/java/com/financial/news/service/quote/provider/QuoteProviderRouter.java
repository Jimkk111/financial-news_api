package com.financial.news.service.quote.provider;

import com.financial.news.config.QuoteProperties;
import com.financial.news.service.quote.provider.model.KlineBar;
import com.financial.news.service.quote.provider.model.QuoteSnapshot;
import com.financial.news.service.quote.provider.model.TrendData;
import com.financial.news.service.quote.provider.model.UpstreamTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上游路由：按能力过滤的有序尝试（主源优先，其余兜底），每个源独立熔断。
 * 全部失败抛 UpstreamException，由服务层决定走 stale 缓存还是报错。
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Component
public class QuoteProviderRouter {

    private final List<QuoteProvider> orderedProviders;
    private final QuoteProperties properties;
    private final Map<String, UpstreamBreaker> breakers = new ConcurrentHashMap<>();

    public QuoteProviderRouter(List<QuoteProvider> providers, QuoteProperties properties) {
        String primary = properties.getProvider().getPrimary();
        this.orderedProviders = providers.stream()
                .sorted((a, b) -> Boolean.compare(b.name().equals(primary), a.name().equals(primary)))
                .toList();
        this.properties = properties;
    }

    public List<QuoteSnapshot> getSnapshots(List<UpstreamTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        List<QuoteSnapshot> merged = new ArrayList<>();
        List<UpstreamTarget> remaining = targets;
        Exception lastError = null;
        for (QuoteProvider provider : orderedProviders) {
            if (!provider.supportsSnapshots() || remaining.isEmpty()) {
                continue;
            }
            List<UpstreamTarget> eligible = remaining.stream().filter(t -> allowed(provider, t)).toList();
            if (eligible.isEmpty()) continue;
            UpstreamBreaker breaker = breaker(provider.name() + ":snapshot");
            if (breaker != null && !breaker.allow()) {
                log.info("[行情] 熔断中，跳过数据源 {} (snapshot)", provider.name());
                continue;
            }
            try {
                List<QuoteSnapshot> result = provider.getSnapshots(eligible);
                if (breaker != null) {
                    breaker.recordSuccess();
                }
                merged.addAll(result);
                // 主源部分覆盖时，缺失标的继续用下一备源补齐（部分数据优于整体失败）
                java.util.Set<String> covered = new java.util.HashSet<>();
                for (QuoteSnapshot snapshot : merged) {
                    covered.add(snapshot.getSymbol());
                }
                remaining = remaining.stream()
                        .filter(t -> !covered.contains(t.symbol()))
                        .toList();
            } catch (Exception e) {
                lastError = e;
                if (breaker != null && !(e instanceof UpstreamQuotaException) && !(e instanceof UpstreamSymbolUnavailableException)) {
                    breaker.recordFailure();
                }
                log.warn("[行情] 数据源 {} snapshot 失败: {}", provider.name(), e.getMessage());
            }
        }
        if (merged.isEmpty()) {
            if (lastError instanceof UpstreamQuotaException quota) throw quota;
            throw new UpstreamException("quote snapshot all providers failed");
        }
        return merged;
    }

    public TrendData getTrend(UpstreamTarget target) {
        return route("trend", target.market().name(), p -> p.supportsTrend() && allowed(p, target),
                provider -> provider.getTrend(target));
    }

    public List<KlineBar> getKline(UpstreamTarget target, int fetchCount) {
        return route("kline", target.market().name(), p -> p.supportsKline() && allowed(p, target),
                provider -> provider.getKline(target, fetchCount));
    }

    private <T> T route(String scene,
                        String market,
                        java.util.function.Predicate<QuoteProvider> capability,
                        java.util.function.Function<QuoteProvider, T> action) {
        Exception lastError = null;
        for (QuoteProvider provider : orderedProviders) {
            if (!capability.test(provider)) {
                continue;
            }
            UpstreamBreaker breaker = breaker(provider.name() + ":" + scene + ":" + market);
            if (breaker != null && !breaker.allow()) {
                log.info("[行情] 熔断中，跳过数据源 {} ({})", provider.name(), scene);
                continue;
            }
            try {
                T result = action.apply(provider);
                if (breaker != null) {
                    breaker.recordSuccess();
                }
                return result;
            } catch (Exception e) {
                lastError = e;
                if (breaker != null && !(e instanceof UpstreamSymbolUnavailableException) && !(e instanceof UpstreamQuotaException)) {
                    breaker.recordFailure();
                }
                log.warn("[行情] 数据源 {} {} 失败: {}", provider.name(), scene, e.getMessage());
            }
        }
        if (lastError instanceof UpstreamSymbolUnavailableException unavailable) {
            throw unavailable;
        }
        if (lastError instanceof UpstreamQuotaException quota) throw quota;
        throw new UpstreamException("quote " + scene + " all providers failed", lastError);
    }

    private UpstreamBreaker breaker(String key) {
        return breakers.computeIfAbsent(key, k -> new UpstreamBreaker(
                properties.getProvider().getBreakerFailThreshold(),
                properties.getProvider().getBreakerOpenSeconds() * 1000L));
    }

    private boolean allowed(QuoteProvider provider, UpstreamTarget target) {
        boolean longport = LongPortQuoteProvider.NAME.equals(provider.name());
        boolean longportTarget = LongPortQuoteProvider.supportsTarget(target);
        if (longport && !longportTarget) return false;
        if (LongPortQuoteProvider.NAME.equals(properties.getProvider().getPrimary()) && longportTarget
                && !properties.getProvider().isLongportFallbackEnabled()) return longport;
        if (target.secType() != com.financial.news.service.quote.QuoteSecType.STOCK || target.symbol().endsWith(".BJ"))
            return !ITickQuoteProvider.NAME.equals(provider.name());
        return !ITickQuoteProvider.NAME.equals(properties.getProvider().getPrimary())
                || properties.getProvider().isItickFallbackEnabled()
                || ITickQuoteProvider.NAME.equals(provider.name());
    }
}
