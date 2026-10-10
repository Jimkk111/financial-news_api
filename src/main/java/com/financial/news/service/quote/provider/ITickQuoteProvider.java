package com.financial.news.service.quote.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.news.config.QuoteProperties;
import com.financial.news.mapper.QuoteProviderSymbolMapper;
import com.financial.news.service.quote.MarketEnum;
import com.financial.news.service.quote.QuoteSecType;
import com.financial.news.service.quote.StockUniverseFilter;
import com.financial.news.service.quote.TradingSessionService;
import com.financial.news.service.quote.provider.model.*;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** iTick REST 适配。Token 仅在请求头中使用，日志不输出上游正文或凭证。 */
@Component
public class ITickQuoteProvider implements QuoteProvider, SecurityUniverseProvider {
    public static final String NAME = "itick";
    private final QuoteProperties properties;
    private final QuoteProviderSymbolMapper mappings;
    private final ObjectMapper mapper;
    private final OkHttpClient client;
    private final Deque<Long> requests = new ArrayDeque<>();
    private final Map<String, CachedSnapshot> snapshots = new ConcurrentHashMap<>();

    public ITickQuoteProvider(QuoteProperties properties, QuoteProviderSymbolMapper mappings, ObjectMapper mapper) {
        this.properties = properties;
        this.mappings = mappings;
        this.mapper = mapper;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(properties.getProvider().getConnectTimeoutMs()))
                .readTimeout(Duration.ofMillis(properties.getProvider().getSecurityListReadTimeoutMs()))
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .callTimeout(Duration.ofSeconds(20)).build();
    }

    @Override public String name() { return NAME; }
    public boolean enabled() { return !properties.getProvider().getItickToken().isBlank(); }
    @Override public boolean supportsSnapshots() { return enabled(); }
    @Override public boolean supportsTrend() { return enabled(); }
    @Override public boolean supportsKline() { return enabled(); }

    @Override
    public List<UpstreamSecurity> listSecurities(MarketEnum market) {
        List<UpstreamSecurity> result = new ArrayList<>();
        List<String> regions = market == MarketEnum.CN ? List.of("SH", "SZ") : List.of(market.name());
        Set<String> seen = new HashSet<>();
        for (String region : regions) {
            JsonNode data = fetch("/symbol/list", Map.of("type", "stock", "region", region));
            if (!data.isArray() || data.size() < 100) throw new UpstreamException("itick invalid directory: " + region);
            for (JsonNode row : data) {
                String code = row.path("c").asText("").trim().toUpperCase(Locale.ROOT);
                String name = StockUniverseFilter.cleanName(row.path("n").asText(""));
                String exchange = row.path("e").asText("").trim();
                String type = row.path("t").asText("");
                if (code.isBlank() || name.isBlank() || exchange.isBlank() || type.isBlank())
                    throw new UpstreamException("itick incomplete directory row: " + region);
                // 美股页面沿用三大交易所范围；OTC 含重复/迁移上市记录，不混入普通股目录。
                if (region.equals("US") && !Set.of("NASDAQ", "NYSE", "AMEX").contains(exchange)) continue;
                boolean stock = StockUniverseFilter.isItickStock(region, code, name, type);
                // 不可作为现有公开 symbol 的代码（例如 /PA 优先股）不入主表。
                if (!code.matches(region.equals("US") ? "[A-Z0-9._$-]{1,14}"
                        : region.equals("HK") ? "\\d{1,5}" : "\\d{6}")) continue;
                String normalized = region.equals("HK") ? String.format(Locale.ROOT, "%05d", Integer.parseInt(code)) : code;
                String key = region + ":" + normalized;
                if (!seen.add(key)) continue;
                int number = switch (region) { case "SH" -> 1; case "SZ" -> 0; case "HK" -> 116; default -> -1; };
                result.add(new UpstreamSecurity(normalized, number, name, stock, region, code, exchange));
            }
        }
        return result;
    }

    @Override
    public List<QuoteSnapshot> getSnapshots(List<UpstreamTarget> targets) {
        List<QuoteSnapshot> result = new ArrayList<>();
        Map<String, List<Resolved>> groups = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (UpstreamTarget target : targets) {
            if (target.secType() != QuoteSecType.STOCK || target.symbol().endsWith(".BJ")) continue;
            CachedSnapshot cached = snapshots.get(target.symbol());
            if (cached != null && now - cached.at() < properties.getCache().getSnapshotFreshSeconds() * 1000L) {
                result.add(cached.value());
                continue;
            }
            // 额度不足时仍可显示已有报价；真实时间戳使服务层标注 delayed。
            if (cached != null && now - cached.at() < properties.getCache().getClosedMaxTtlSeconds() * 1000L)
                result.add(cached.value());
            Resolved resolved = resolve(target);
            groups.computeIfAbsent(resolved.region(), k -> new ArrayList<>()).add(resolved);
        }
        int batchSize = Math.max(1, Math.min(50, properties.getProvider().getItickBatchSize()));
        for (List<Resolved> group : groups.values()) {
            // 优先从未取到报价的标的，避免免费额度始终只刷新列表前几行。
            group.sort(Comparator.comparingLong(r -> {
                CachedSnapshot cached = snapshots.get(r.target().symbol());
                return cached == null ? 0 : cached.at();
            }));
            for (int from = 0; from < group.size(); from += batchSize) {
                List<Resolved> batch = group.subList(from, Math.min(group.size(), from + batchSize));
                Map<String, String> params = params(batch.get(0));
                // 免费报价入口携带 exchange 会返回空对象（实测），使用主上市交易所。
                params.remove("exchange");
                params.put("codes", String.join(",", batch.stream().map(Resolved::code).toList()));
                JsonNode data;
                try {
                    data = fetch("/stock/quotes", params);
                } catch (UpstreamException e) {
                    if (result.isEmpty()) throw e;
                    // 已取到的批次保留，由列表显示未覆盖标的的数据延迟状态。
                    return result;
                }
                if (!data.isObject()) throw new UpstreamException("itick invalid quote response");
                for (Resolved resolved : batch) {
                    JsonNode row = data.get(resolved.code());
                    if (row == null || row.isNull()) continue;
                    if (!resolved.code().equalsIgnoreCase(row.path("s").asText()))
                        throw new UpstreamException("itick quote symbol mismatch");
                    Double price = number(row, "ld");
                    Long time = integer(row, "t");
                    if (time == null) throw new UpstreamException("itick quote missing timestamp");
                    QuoteSnapshot value = QuoteSnapshot.builder().symbol(resolved.target().symbol())
                            .market(resolved.target().market().name()).latestPrice(price)
                            .prevClose(number(row, "p")).open(number(row, "o"))
                            .high(number(row, "h")).low(number(row, "l"))
                            .changeAmount(number(row, "ch")).changePercent(number(row, "chp"))
                            .volume(integer(row, "v")).turnover(number(row, "tu"))
                            .dataTimeEpochSec(time / 1000).priceAvailable(price != null && price > 0).build();
                    snapshots.put(resolved.target().symbol(), new CachedSnapshot(System.currentTimeMillis(), value));
                    result.removeIf(previous -> previous.getSymbol().equals(resolved.target().symbol()));
                    result.add(value);
                }
            }
        }
        return result;
    }

    @Override
    public TrendData getTrend(UpstreamTarget target) {
        Resolved resolved = resolve(target);
        List<QuoteSnapshot> quote = getSnapshots(List.of(target));
        Double prevClose = quote.isEmpty() ? null : quote.get(0).getPrevClose();
        ZoneId zone = zone(target.market());
        TreeMap<Long, JsonNode> rows = new TreeMap<>();
        Long end = null;
        LocalDate sessionDate = null;
        // 美股分钟线含盘前盘后；向前补页直到覆盖最近常规交易日开盘。
        for (int page = 0; page < 4; page++) {
            List<JsonNode> part = klines(resolved, 1, 500, end);
            if (part.isEmpty()) break;
            long earliest = Long.MAX_VALUE;
            for (JsonNode row : part) {
                long timestamp = row.path("t").asLong();
                rows.put(timestamp, row);
                earliest = Math.min(earliest, timestamp);
                ZonedDateTime local = Instant.ofEpochMilli(timestamp).atZone(zone);
                if (regular(target.market(), local.toLocalTime())
                        && (sessionDate == null || local.toLocalDate().isAfter(sessionDate))) sessionDate = local.toLocalDate();
            }
            if (sessionDate != null && !Instant.ofEpochMilli(earliest).atZone(zone)
                    .isAfter(sessionDate.atTime(9, 30).atZone(zone))) break;
            if (end != null && earliest >= end) throw new UpstreamException("itick minute pagination did not advance");
            end = earliest - 1;
        }
        if (sessionDate == null) throw new UpstreamSymbolUnavailableException("itick no regular-session trend: " + target.symbol());
        if (Instant.ofEpochMilli(rows.firstKey()).atZone(zone).isAfter(sessionDate.atTime(9, 30).atZone(zone)))
            throw new UpstreamException("itick incomplete regular-session trend");
        List<TrendPoint> points = new ArrayList<>();
        double cumulativeTurnover = 0;
        long cumulativeVolume = 0;
        boolean averageAvailable = true;
        for (var entry : rows.entrySet()) {
            ZonedDateTime local = Instant.ofEpochMilli(entry.getKey()).atZone(zone);
            if (!local.toLocalDate().equals(sessionDate) || !regular(target.market(), local.toLocalTime())) continue;
            JsonNode row = entry.getValue();
            Long volume = integer(row, "v");
            Double turnover = number(row, "tu");
            if (volume == null || turnover == null) averageAvailable = false;
            if (volume != null) cumulativeVolume += volume;
            if (turnover != null) cumulativeTurnover += turnover;
            points.add(new TrendPoint(local.withZoneSameInstant(TradingSessionService.ZONE).toLocalDateTime(),
                    number(row, "c"), volume,
                    averageAvailable && cumulativeVolume > 0 ? cumulativeTurnover / cumulativeVolume : null));
        }
        return new TrendData(target.symbol(), prevClose, points);
    }

    @Override
    public List<KlineBar> getKline(UpstreamTarget target, int fetchCount) {
        Resolved resolved = resolve(target);
        TreeMap<Long, JsonNode> rows = new TreeMap<>();
        Long end = null;
        for (int page = 0; page < 3 && rows.size() < fetchCount; page++) {
            List<JsonNode> part = klines(resolved, 8, Math.min(500, fetchCount - rows.size()), end);
            if (part.isEmpty()) break;
            long earliest = Long.MAX_VALUE;
            for (JsonNode row : part) {
                long timestamp = row.path("t").asLong();
                rows.put(timestamp, row);
                earliest = Math.min(earliest, timestamp);
            }
            if (end != null && earliest >= end) throw new UpstreamException("itick daily pagination did not advance");
            end = earliest - 1;
        }
        if (rows.isEmpty()) throw new UpstreamSymbolUnavailableException("itick no daily bars: " + target.symbol());
        List<KlineBar> bars = new ArrayList<>();
        for (var entry : rows.entrySet()) {
            JsonNode row = entry.getValue();
            // iTick 日线时间戳为 UTC 日期锚点，不能按纽约时区向前偏移一天。
            KlineBar bar = new KlineBar(Instant.ofEpochMilli(entry.getKey()).atZone(ZoneOffset.UTC).toLocalDate(),
                    number(row, "o"), number(row, "c"), number(row, "h"), number(row, "l"),
                    integer(row, "v"), number(row, "tu"));
            bar.setFq("none");
            bars.add(bar);
        }
        return bars;
    }

    private List<JsonNode> klines(Resolved resolved, int type, int limit, Long end) {
        Map<String, String> params = params(resolved);
        params.put("code", resolved.code());
        params.put("kType", String.valueOf(type));
        params.put("limit", String.valueOf(limit));
        if (end != null) params.put("et", String.valueOf(end));
        JsonNode data = fetch("/stock/kline", params);
        if (!data.isArray()) throw new UpstreamException("itick invalid kline response");
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode row : data) {
            if (!row.path("t").isNumber() || row.path("t").asLong() <= 0
                    || number(row, "c") == null || number(row, "o") == null
                    || number(row, "h") == null || number(row, "l") == null)
                throw new UpstreamException("itick invalid kline row");
            rows.add(row);
        }
        return rows;
    }

    private Resolved resolve(UpstreamTarget target) {
        if (target.secType() != QuoteSecType.STOCK || target.symbol().endsWith(".BJ"))
            throw new UpstreamSymbolUnavailableException("itick unsupported target: " + target.symbol());
        QuoteProviderSymbolMapper.Mapping mapping = mappings.find(target.symbol());
        if (mapping != null) return new Resolved(target, mapping.region(), mapping.code(), mapping.exchange());
        int dot = target.symbol().lastIndexOf('.');
        String region = target.symbol().substring(dot + 1);
        String code = target.symbol().substring(0, dot);
        if (region.equals("HK")) code = String.valueOf(Integer.parseInt(code));
        return new Resolved(target, region, code, "");
    }

    private Map<String, String> params(Resolved resolved) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("region", resolved.region());
        if (!resolved.exchange().isBlank()) result.put("exchange", resolved.exchange());
        return result;
    }

    private JsonNode fetch(String path, Map<String, String> params) {
        if (!enabled()) throw new UpstreamException("ITICK_TOKEN is not configured");
        HttpUrl.Builder url = HttpUrl.get(properties.getProvider().getItickBaseUrl() + path).newBuilder();
        params.forEach(url::addQueryParameter);
        reserveRequest();
        Request request = new Request.Builder().url(url.build()).header("accept", "application/json")
                .header("token", properties.getProvider().getItickToken()).build();
        // 禁止自动重试，避免超额以及泄漏原始返回正文。
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 429) throw new ITickQuotaException();
            if (!response.isSuccessful()) throw new UpstreamException("itick " + path + " HTTP " + response.code());
            if (response.body() == null) throw new UpstreamException("itick empty response");
            JsonNode root = mapper.readTree(response.body().string());
            if (root != null && root.path("code").asInt(-1) == 429) throw new ITickQuotaException();
            if (root != null && root.path("code").asInt(-1) == 1
                    && "your request is too much".equalsIgnoreCase(root.path("msg").asText()))
                throw new ITickQuotaException();
            if (root == null || !root.path("code").isNumber() || root.path("code").asInt(-1) != 0)
                throw new UpstreamException("itick " + path + " API code " + (root == null ? "missing" : root.path("code").asText("missing")));
            if (!root.hasNonNull("data")) throw new UpstreamException("itick missing data");
            return root.get("data");
        } catch (IOException e) {
            throw new UpstreamException("itick " + path + " request failed");
        }
    }

    private synchronized void reserveRequest() {
        long now = System.currentTimeMillis();
        while (!requests.isEmpty() && now - requests.peekFirst() >= 60_000) requests.removeFirst();
        if (requests.size() >= Math.max(1, properties.getProvider().getItickRequestsPerMinute()))
            throw new ITickQuotaException();
        requests.addLast(now);
    }

    private static Double number(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())) return null;
        return value.asDouble();
    }
    private static Long integer(JsonNode row, String field) {
        Double value = number(row, field);
        return value == null ? null : value.longValue();
    }
    private static ZoneId zone(MarketEnum market) {
        return market == MarketEnum.US ? ZoneId.of("America/New_York") : TradingSessionService.ZONE;
    }
    private static boolean regular(MarketEnum market, LocalTime time) {
        if (time.isBefore(LocalTime.of(9, 30))) return false;
        if (market == MarketEnum.US) return !time.isAfter(LocalTime.of(16, 0));
        if (market == MarketEnum.HK) return !time.isAfter(LocalTime.NOON)
                || (!time.isBefore(LocalTime.of(13, 0)) && !time.isAfter(LocalTime.of(16, 0)));
        return !time.isAfter(LocalTime.of(11, 30))
                || (!time.isBefore(LocalTime.of(13, 0)) && !time.isAfter(LocalTime.of(15, 0)));
    }
    private record Resolved(UpstreamTarget target, String region, String code, String exchange) {}
    private record CachedSnapshot(long at, QuoteSnapshot value) {}
}
