package com.financial.news.service.quote.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.news.config.QuoteProperties;
import com.financial.news.service.quote.provider.model.KlineBar;
import com.financial.news.service.quote.provider.model.QuoteSnapshot;
import com.financial.news.service.quote.provider.model.TrendData;
import com.financial.news.service.quote.provider.model.TrendPoint;
import com.financial.news.service.quote.provider.model.UpstreamSecurity;
import com.financial.news.service.quote.provider.model.UpstreamTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * 东方财富行情数据源（主源，非官方接口，字段经防腐层在此收口）
 * <p>上游字段要点（2026-10 实测）：
 * 快照 ulist.np/get —— f2 最新价/f3 涨跌幅/f4 涨跌额/f5 量/f6 额/f12 代码/f13 市场/
 * f14 名称/f15 高/f16 低/f17 开/f18 昨收/f124 数据时间(epoch 秒)；
 * 成交量单位：f13 为 0/1（沪深）时是"手"，其余（116 港、105/106/107 美股、100/124 指数）为"股"；
 * 指数 secid 前缀不统一（恒指 100、恒生科技 124），一律以主表 upstream_secid 为准。
 * 分时 trends2 —— 行格式 "yyyy-MM-dd HH:mm,价格,量,均价"，data.preClose 昨收；
 * 日 K kline/get —— 行格式 "date,open,close,high,low,量,额"（fqt=1 前复权）。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Component
public class EastMoneyQuoteProvider implements QuoteProvider {

    public static final String NAME = "eastmoney";

    private static final DateTimeFormatter TREND_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final String snapshotUrl;
    private final String trendUrl;
    private final String klineUrl;
    private final String clistUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EastMoneyQuoteProvider(QuoteProperties properties, ObjectMapper objectMapper) {
        this.snapshotUrl = properties.getProvider().getEastMoneyBaseUrl() + "/api/qt/ulist.np/get";
        this.trendUrl = properties.getProvider().getEastMoneyHisBaseUrl() + "/api/qt/stock/trends2/get";
        this.klineUrl = properties.getProvider().getEastMoneyHisBaseUrl() + "/api/qt/stock/kline/get";
        this.clistUrl = properties.getProvider().getEastMoneyBaseUrl() + "/api/qt/clist/get";
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(properties.getProvider().getConnectTimeoutMs()))
                .readTimeout(Duration.ofMillis(properties.getProvider().getReadTimeoutMs()))
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supportsSnapshots() {
        return true;
    }

    @Override
    public boolean supportsTrend() {
        return true;
    }

    @Override
    public boolean supportsKline() {
        return true;
    }

    @Override
    public List<QuoteSnapshot> getSnapshots(List<UpstreamTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        // 按 secid 重建索引：响应中 f13.f12 即请求时的 secid
        Map<String, UpstreamTarget> bySecid = new HashMap<>();
        for (UpstreamTarget target : targets) {
            bySecid.put(target.secid(), target);
        }
        List<QuoteSnapshot> result = new ArrayList<>();
        for (int from = 0; from < targets.size(); from += 50) {
            List<UpstreamTarget> batch = targets.subList(from, Math.min(from + 50, targets.size()));
            String secids = batch.stream().map(UpstreamTarget::secid).reduce((a, b) -> a + "," + b).orElse("");
            String url = snapshotUrl + "?fltt=2&invt=2&secids=" + secids
                    + "&fields=f2,f3,f4,f5,f6,f12,f13,f14,f15,f16,f17,f18,f124";
            JsonNode data = fetchJson(url, "snapshot").path("data");
            JsonNode diff = data.path("diff");
            if (!diff.isArray()) {
                continue;
            }
            for (JsonNode row : diff) {
                String secid = row.path("f13").asText() + "." + row.path("f12").asText();
                UpstreamTarget target = bySecid.get(secid);
                if (target == null) {
                    continue;
                }
                Double latest = asDouble(row.get("f2"));
                Long rawVolume = asLong(row.get("f5"));
                result.add(QuoteSnapshot.builder()
                        .symbol(target.symbol())
                        .market(target.market().name())
                        .name(textOr(row.get("f14"), target.symbol()))
                        .latestPrice(latest)
                        .changeAmount(asDouble(row.get("f4")))
                        .changePercent(asDouble(row.get("f3")))
                        .open(asDouble(row.get("f17")))
                        .prevClose(asDouble(row.get("f18")))
                        .high(asDouble(row.get("f15")))
                        .low(asDouble(row.get("f16")))
                        .volume(normalizeVolume(rawVolume, secid))
                        .turnover(asDouble(row.get("f6")))
                        .dataTimeEpochSec(asLong(row.get("f124")))
                        .priceAvailable(latest != null && latest > 0)
                        .build());
            }
        }
        if (result.isEmpty()) {
            throw new UpstreamException("eastmoney snapshot returned no rows");
        }
        return result;
    }

    @Override
    public TrendData getTrend(UpstreamTarget target) {
        String url = trendUrl + "?secid=" + target.secid()
                + "&ndays=1&iscr=0&fields1=f1,f2,f3,f7,f8&fields2=f51,f53,f56,f58";
        JsonNode data = fetchJson(url, "trend").path("data");
        Double preClose = asDouble(data.get("preClose"));
        List<TrendPoint> points = new ArrayList<>();
        JsonNode trends = data.path("trends");
        if (trends.isArray()) {
            for (JsonNode line : trends) {
                String[] parts = line.asText().split(",");
                if (parts.length < 4) {
                    continue;
                }
                points.add(new TrendPoint(
                        LocalDateTime.parse(parts[0], TREND_TIME_FORMAT),
                        parseDouble(parts[1]),
                        normalizeVolume(parseLong(parts[2]), target.secid()),
                        parseDouble(parts[3])));
            }
        }
        return new TrendData(target.symbol(), preClose, points);
    }

    @Override
    public List<KlineBar> getKline(UpstreamTarget target, int fetchCount) {
        String url = klineUrl + "?secid=" + target.secid()
                + "&klt=101&fqt=1&lmt=" + fetchCount + "&end=20500101&fields1=f1,f2,f3&fields2=f51,f52,f53,f54,f55,f56,f57";
        JsonNode data = fetchJson(url, "kline").path("data");
        List<KlineBar> bars = new ArrayList<>();
        JsonNode klines = data.path("klines");
        if (klines.isArray()) {
            for (JsonNode line : klines) {
                String[] parts = line.asText().split(",");
                if (parts.length < 7) {
                    continue;
                }
                bars.add(new KlineBar(
                        LocalDate.parse(parts[0]),
                        parseDouble(parts[1]),
                        parseDouble(parts[2]),
                        parseDouble(parts[3]),
                        parseDouble(parts[4]),
                        normalizeVolume(parseLong(parts[5]), target.secid()),
                        parseDouble(parts[6])));
            }
        }
        if (bars.isEmpty()) {
            throw new UpstreamException("eastmoney kline returned no rows: " + target.secid());
        }
        return bars;
    }

    /**
     * 证券列表全量分页（主表同步用，非 SPI 能力）
     *
     * @param fs 板块过滤，如 "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23"（沪深）、"m:116"（港股）、"m:105,m:106,m:107"（美股）
     */
    public List<UpstreamSecurity> listAllSecurities(String fs) {
        List<UpstreamSecurity> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long total = -1;
        for (int page = 1; page <= 1000; page++) {
            String url = clistUrl
                    + "?pn=" + page + "&pz=100&po=1&np=1&fltt=2&invt=2&fid=f12&fs=" + fs
                    + "&fields=f12,f13,f14";
            JsonNode data = fetchJson(url, "security-list").path("data");
            long reported = data.path("total").asLong(-1);
            if (reported < 0 || (total >= 0 && total != reported)) {
                throw new UpstreamException("security-list total missing or changed during pagination");
            }
            total = reported;
            if (total == 0) {
                throw new UpstreamException("security-list empty universe");
            }
            JsonNode diff = data.path("diff");
            if (!diff.isArray() || diff.isEmpty()) {
                throw new UpstreamException("security-list incomplete at page " + page);
            }
            for (JsonNode row : diff) {
                String code = row.path("f12").asText("").trim();
                String name = row.path("f14").asText("").trim();
                int market = row.path("f13").asInt(-1);
                if (code.isBlank() || name.isBlank() || market < 0
                        || !seen.add(market + "." + code)) {
                    throw new UpstreamException("security-list invalid or duplicate row at page " + page);
                }
                result.add(new UpstreamSecurity(code, market, name));
            }
            if (result.size() == total) {
                return result;
            }
            if (result.size() > total) {
                throw new UpstreamException("security-list count exceeds total");
            }
        }
        throw new UpstreamException("security-list exceeded pagination limit");
    }

    /**
     * 单次 GET，失败自动重试一次；两次都失败抛 UpstreamException
     */
    private JsonNode fetchJson(String url, String scene) {
        IOException lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (financial-news-api)")
                    .get()
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new UpstreamException("eastmoney " + scene + " http " + response.code());
                }
                ResponseBody body = response.body();
                if (body == null) {
                    throw new UpstreamException("eastmoney " + scene + " empty body");
                }
                return objectMapper.readTree(body.string());
            } catch (IOException e) {
                lastError = e;
                log.warn("[行情] eastmoney {} 请求失败（第 {} 次）: {}", scene, attempt + 1, e.getMessage());
            }
        }
        throw new UpstreamException("eastmoney " + scene + " request failed", lastError);
    }

    /** 沪深（secid 前缀 0/1）上游量的单位是"手"，统一乘 100 归一化为股 */
    private Long normalizeVolume(Long rawVolume, String secid) {
        if (rawVolume == null) {
            return null;
        }
        String prefix = secid.substring(0, secid.indexOf('.'));
        boolean cn = "0".equals(prefix) || "1".equals(prefix);
        return cn ? rawVolume * 100 : rawVolume;
    }

    private Double asDouble(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        return parseDouble(node.asText());
    }

    private Long asLong(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.longValue();
        }
        return parseLong(node.asText());
    }

    private Double parseDouble(String text) {
        if (text == null || text.isBlank() || "-".equals(text.trim())) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.trim());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseLong(String text) {
        if (text == null || text.isBlank() || "-".equals(text.trim())) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            Double d = parseDouble(text);
            return d == null ? null : d.longValue();
        }
    }

    private String textOr(JsonNode node, String fallback) {
        return node == null || node.isNull() || node.asText().isBlank() ? fallback : node.asText();
    }
}
