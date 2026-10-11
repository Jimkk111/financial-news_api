package com.financial.news.service.quote.provider;

import com.financial.news.config.QuoteProperties;
import com.financial.news.service.quote.MarketEnum;
import com.financial.news.service.quote.QuoteSecType;
import com.financial.news.service.quote.TradingSessionService;
import com.financial.news.service.quote.provider.model.*;
import com.longbridge.Config;
import com.longbridge.OpenApiException;
import com.longbridge.quote.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

/** 长桥官方 SDK 行情适配；证券目录由独立的 SecurityUniverseProvider 同步。 */
@Component
public class LongPortQuoteProvider implements QuoteProvider {
    public static final String NAME = "longport";
    private static final int BATCH_SIZE = 20;
    private final QuoteProperties properties;
    private final Semaphore concurrent = new Semaphore(4);
    private final Deque<Long> requestTimes = new ArrayDeque<>();
    private final ExecutorService initializer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "longport-connect");
        thread.setDaemon(true);
        return thread;
    });
    private CompletableFuture<QuoteContext> contextFuture;
    private boolean closed;

    public LongPortQuoteProvider(QuoteProperties properties) { this.properties = properties; }

    @Override public String name() { return NAME; }
    // 主源缺凭证时明确报配置错误，不悄悄切换其他股票源。
    private boolean enabled() {
        var config = properties.getProvider();
        return NAME.equals(config.getPrimary()) || (!config.getLongportAppKey().isBlank()
                && !config.getLongportAppSecret().isBlank() && !config.getLongportAccessToken().isBlank());
    }
    @Override public boolean supportsSnapshots() { return enabled(); }
    @Override public boolean supportsTrend() { return enabled(); }
    @Override public boolean supportsKline() { return enabled(); }

    /** 只路由已知支持的标的；北交所、标普指数继续走原来的指数/备用源。 */
    public static boolean supportsTarget(UpstreamTarget target) {
        if (target.symbol().endsWith(".BJ")) return false;
        if (target.secType() == QuoteSecType.STOCK) return true;
        return switch (target.market()) {
            case CN -> true;
            case HK -> Set.of("HSI.HK", "HSCEI.HK", "HSTECH.HK").contains(target.symbol());
            case US -> Set.of("DJI.US", "IXIC.US").contains(target.symbol());
        };
    }

    private static String upstreamSymbol(UpstreamTarget target) {
        if (!supportsTarget(target)) throw new UpstreamSymbolUnavailableException("长桥不支持该标的: " + target.symbol());
        String symbol = target.symbol();
        if (target.secType() != QuoteSecType.STOCK && target.market() == MarketEnum.US) return "." + symbol;
        if (target.market() == MarketEnum.HK && target.secType() == QuoteSecType.STOCK)
            return Integer.parseInt(symbol.substring(0, symbol.lastIndexOf('.'))) + ".HK";
        return symbol;
    }

    @Override
    public List<QuoteSnapshot> getSnapshots(List<UpstreamTarget> targets) {
        List<QuoteSnapshot> result = new ArrayList<>();
        Map<MarketEnum, List<UpstreamTarget>> groups = new LinkedHashMap<>();
        targets.stream().filter(LongPortQuoteProvider::supportsTarget)
                .forEach(t -> groups.computeIfAbsent(t.market(), ignored -> new ArrayList<>()).add(t));
        UpstreamException failure = null;
        for (var group : groups.values()) {
            for (int start = 0; start < group.size(); start += BATCH_SIZE) {
                Map<String, UpstreamTarget> batch = new LinkedHashMap<>();
                group.subList(start, Math.min(start + BATCH_SIZE, group.size()))
                        .forEach(t -> batch.put(upstreamSymbol(t), t));
                try {
                    SecurityQuote[] quotes = request(ctx -> ctx.getQuote(batch.keySet().toArray(String[]::new)));
                    for (SecurityQuote quote : quotes) {
                        UpstreamTarget target = batch.get(canonicalSymbol(quote.getSymbol()));
                        if (target == null) continue;
                        Double latest = positive(quote.getLastDone());
                        Double prevClose = positive(quote.getPrevClose());
                        Double change = latest == null || prevClose == null ? null : latest - prevClose;
                        result.add(QuoteSnapshot.builder().symbol(target.symbol()).market(target.market().name())
                                .latestPrice(latest).prevClose(prevClose).changeAmount(change)
                                .changePercent(change == null ? null : change / prevClose * 100)
                                .open(positive(quote.getOpen())).high(positive(quote.getHigh())).low(positive(quote.getLow()))
                                .volume(quote.getVolume()).turnover(number(quote.getTurnover()))
                                .dataTimeEpochSec(quote.getTimestamp() == null ? null : quote.getTimestamp().toEpochSecond())
                                .priceAvailable(latest != null).build());
                    }
                } catch (UpstreamException ex) {
                    failure = ex;
                    // 配额耗尽后继续请求不能补齐数据。
                    if (ex instanceof UpstreamQuotaException) {
                        if (result.isEmpty()) throw ex;
                        return result;
                    }
                }
            }
        }
        if (result.isEmpty() && failure != null) throw failure;
        return result;
    }

    private static String canonicalSymbol(String symbol) {
        if (symbol == null) return "";
        if (symbol.matches("\\d+\\.HK")) return Integer.parseInt(symbol.substring(0, symbol.length() - 3)) + ".HK";
        return symbol.toUpperCase(Locale.ROOT);
    }

    @Override
    public TrendData getTrend(UpstreamTarget target) {
        String symbol = upstreamSymbol(target);
        IntradayLine[] lines = request(ctx -> ctx.getIntraday(symbol, TradeSessions.Intraday));
        List<TrendPoint> points = Arrays.stream(lines)
                .filter(line -> line.getTimestamp() != null && positive(line.getPrice()) != null)
                .sorted(Comparator.comparing(IntradayLine::getTimestamp))
                .map(line -> new TrendPoint(line.getTimestamp().atZoneSameInstant(TradingSessionService.ZONE).toLocalDateTime(),
                        number(line.getPrice()), line.getVolume(), positive(line.getAvgPrice())))
                .toList();
        if (points.isEmpty()) throw new UpstreamSymbolUnavailableException("长桥暂无分时数据: " + target.symbol());
        // 昨收来自同一行情源，避免切换后图表基准线混用其他源。
        List<QuoteSnapshot> snapshots = getSnapshots(List.of(target));
        Double prevClose = snapshots.isEmpty() ? null : snapshots.get(0).getPrevClose();
        return new TrendData(target.symbol(), prevClose, points);
    }

    @Override
    public List<KlineBar> getKline(UpstreamTarget target, int fetchCount) {
        String symbol = upstreamSymbol(target);
        int count = Math.max(1, Math.min(1000, fetchCount));
        Candlestick[] candles = request(ctx -> ctx.getHistoryCandlesticksByOffset(symbol, Period.Day,
                AdjustType.ForwardAdjust, false, null, count, TradeSessions.Intraday));
        ZoneId zone = target.market() == MarketEnum.US ? ZoneId.of("America/New_York") : TradingSessionService.ZONE;
        Map<java.time.LocalDate, KlineBar> bars = new TreeMap<>();
        for (Candlestick candle : candles) {
            if (candle.getTimestamp() == null || positive(candle.getOpen()) == null || positive(candle.getClose()) == null
                    || positive(candle.getHigh()) == null || positive(candle.getLow()) == null) continue;
            var date = candle.getTimestamp().atZoneSameInstant(zone).toLocalDate();
            bars.put(date, new KlineBar(date, number(candle.getOpen()), number(candle.getClose()),
                    number(candle.getHigh()), number(candle.getLow()), candle.getVolume(), number(candle.getTurnover()), "qfq"));
        }
        if (bars.isEmpty()) throw new UpstreamSymbolUnavailableException("长桥暂无日 K 数据: " + target.symbol());
        List<KlineBar> result = new ArrayList<>(bars.values());
        return result.subList(Math.max(0, result.size() - count), result.size());
    }

    private synchronized CompletableFuture<QuoteContext> connection() {
        if (closed) throw new UpstreamException("长桥行情连接已关闭");
        var config = properties.getProvider();
        if (config.getLongportAppKey().isBlank() || config.getLongportAppSecret().isBlank()
                || config.getLongportAccessToken().isBlank())
            throw new UpstreamException("请配置 LONGPORT_APP_KEY、LONGPORT_APP_SECRET、LONGPORT_ACCESS_TOKEN");
        if (contextFuture == null || contextFuture.isCompletedExceptionally()) {
            contextFuture = CompletableFuture.supplyAsync(() -> {
                try (Config sdkConfig = Config.fromApikey(config.getLongportAppKey(), config.getLongportAppSecret(),
                        config.getLongportAccessToken())) {
                    String region = config.getLongportRegion();
                    if (!Set.of("cn", "hk").contains(region)) throw new UpstreamException("LONGPORT_REGION 必须为 cn 或 hk");
                    String host = "cn".equals(region) ? "longbridge.cn" : "longbridge.com";
                    sdkConfig.httpUrl("https://openapi." + host)
                            .quoteWebsocketUrl("wss://openapi-quote." + host + "/v2")
                            .disablePrintQuotePackages();
                    return QuoteContext.create(sdkConfig);
                } catch (Exception | LinkageError ex) {
                    throw translate(ex);
                }
            }, initializer);
        }
        return contextFuture;
    }

    @FunctionalInterface
    private interface SdkCall<T> { CompletableFuture<T> execute(QuoteContext context) throws OpenApiException; }

    private <T> T request(SdkCall<T> call) {
        QuoteContext context = await(connection());
        if (!concurrent.tryAcquire()) throw new UpstreamQuotaException("长桥行情并发请求已满，请稍后重试", 1);
        boolean dispatched = false;
        try {
            reserveRequest();
            CompletableFuture<T> future = call.execute(context);
            // 超时后上游可能仍在处理；等实际完成才释放并发槽位。
            future.whenComplete((value, error) -> concurrent.release());
            dispatched = true;
            return await(future);
        } catch (OpenApiException ex) {
            throw translate(ex);
        } finally {
            if (!dispatched) concurrent.release();
        }
    }

    private synchronized void reserveRequest() {
        long now = System.nanoTime();
        while (!requestTimes.isEmpty() && now - requestTimes.peekFirst() >= TimeUnit.SECONDS.toNanos(1)) requestTimes.removeFirst();
        if (requestTimes.size() >= 10) throw new UpstreamQuotaException("长桥行情请求过于频繁，请稍后重试", 1);
        requestTimes.addLast(now);
    }

    private <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(Math.max(1000, properties.getProvider().getLongportTimeoutMs()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("长桥行情请求被中断");
        } catch (TimeoutException ex) {
            throw new UpstreamException("长桥行情请求超时");
        } catch (ExecutionException ex) {
            throw translate(ex.getCause());
        }
    }

    private static UpstreamException translate(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        if (error instanceof UpstreamException upstream) return upstream;
        if (error instanceof OpenApiException api) {
            Long code = api.getCode();
            if (Objects.equals(code, 301606L) || Objects.equals(code, 429L))
                return new UpstreamQuotaException("长桥行情请求额度已用完，请稍后重试", 1);
            if (Objects.equals(code, 301600L) || Objects.equals(code, 301603L))
                return new UpstreamSymbolUnavailableException("长桥未收录该标的或暂无行情（" + code + "）");
            if (Objects.equals(code, 301604L)) return new UpstreamException("长桥 OpenAPI 行情权限不足（301604），请检查账户权限");
            return new UpstreamException("长桥行情请求失败，错误码: " + code);
        }
        // 不携带 SDK 原始异常消息/堆栈，防止凭证或认证 URL 被日志记录。
        return new UpstreamException("长桥 SDK 连接失败（" + error.getClass().getSimpleName() + "）");
    }

    private static Double number(BigDecimal value) { return value == null ? null : value.doubleValue(); }
    private static Double positive(BigDecimal value) { return value == null || value.signum() <= 0 ? null : value.doubleValue(); }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        if (contextFuture != null) contextFuture.thenAccept(context -> {
            try { context.close(); } catch (Exception ignored) { /* 关闭时不输出 SDK 凭证信息。 */ }
        });
        initializer.shutdown();
    }
}
