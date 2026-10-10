package com.financial.news.service.quote.provider;

import com.financial.news.config.QuoteProperties;
import com.financial.news.service.quote.provider.model.KlineBar;
import com.financial.news.service.quote.provider.model.QuoteSnapshot;
import com.financial.news.service.quote.provider.model.TrendData;
import com.financial.news.service.quote.provider.model.UpstreamTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 新浪财经行情备源：仅快照（hq.sinajs.cn），GB-K 编码，需带 Referer 头。
 * <p>前缀约定：沪 sh600519 / 深 sz000001 / 港 rt_hk00700 / 美股 gb_aapl；
 * 各前缀字段布局不同，解析时分别处理。2026-10 实测字段序号见各解析方法。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Component
public class SinaQuoteProvider implements QuoteProvider {

    public static final String NAME = "sina";

    private static final String REFERER = "https://finance.sina.com.cn/";
    private static final Charset GBK = Charset.forName("GBK");
    private static final Pattern VAR_PATTERN = Pattern.compile("var hq_str_(\\w+)=\"([^\"]*)\"");
    private static final DateTimeFormatter CN_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter HK_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    private final OkHttpClient httpClient;
    private final String snapshotUrl;

    public SinaQuoteProvider(QuoteProperties properties) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(properties.getProvider().getConnectTimeoutMs()))
                .readTimeout(Duration.ofMillis(properties.getProvider().getReadTimeoutMs()))
                .build();
        this.snapshotUrl = properties.getProvider().getSinaBaseUrl() + "/list=";
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
        return false;
    }

    @Override
    public boolean supportsKline() {
        return false;
    }

    @Override
    public List<QuoteSnapshot> getSnapshots(List<UpstreamTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        Map<String, UpstreamTarget> byKey = new HashMap<>();
        for (UpstreamTarget target : targets) {
            byKey.put(toSinaKey(target), target);
        }
        List<QuoteSnapshot> result = new ArrayList<>();
        List<String> keys = new ArrayList<>(byKey.keySet());
        for (int from = 0; from < keys.size(); from += 40) {
            List<String> batch = keys.subList(from, Math.min(from + 40, keys.size()));
            String body = fetchBody(snapshotUrl + String.join(",", batch));
            Matcher matcher = VAR_PATTERN.matcher(body);
            while (matcher.find()) {
                UpstreamTarget target = byKey.get(matcher.group(1));
                if (target == null) {
                    continue;
                }
                QuoteSnapshot snapshot = parse(target, matcher.group(2).split(","));
                if (snapshot != null) {
                    result.add(snapshot);
                }
            }
        }
        if (result.isEmpty()) {
            throw new UpstreamException("sina snapshot returned no rows");
        }
        return result;
    }

    @Override
    public TrendData getTrend(UpstreamTarget target) {
        throw new UpstreamException("sina provider does not support trend");
    }

    @Override
    public List<KlineBar> getKline(UpstreamTarget target, int fetchCount) {
        throw new UpstreamException("sina provider does not support kline");
    }

    private QuoteSnapshot parse(UpstreamTarget target, String[] f) {
        try {
            switch (target.market()) {
                case CN -> {
                    if (f.length < 32) {
                        return null;
                    }
                    // 0 名称 1 开 2 昨收 3 最新 4 高 5 低 ... 8 量 9 额 ... 30 日期 31 时间
                    // 量单位：股票为"股"；沪市指数（sh000xxx）为"手"需 ×100；深市指数（sz399xxx）已是"股"
                    Double price = parseDouble(f[3]);
                    Double prevClose = parseDouble(f[2]);
                    return base(target, f[0], price, prevClose, parseDouble(f[1]),
                            parseDouble(f[4]), parseDouble(f[5]), normalizeCnVolume(parseLong(f[8]), target), parseDouble(f[9]))
                            .changeAmount(sub(price, prevClose))
                            .changePercent(percent(price, prevClose))
                            .dataTimeEpochSec(epoch(f[30] + " " + f[31], CN_TIME))
                            .build();
                }
                case HK -> {
                    if (f.length < 19) {
                        return null;
                    }
                    // 0 英文名 1 中文名 2 开 3 昨收 4 高 5 低 6 最新 7 涨跌 8 涨跌幅
                    // 11 成交额 12 成交量(股) 17 日期 18 时间
                    Double price = parseDouble(f[6]);
                    return base(target, f[1], price, parseDouble(f[3]), parseDouble(f[2]),
                            parseDouble(f[4]), parseDouble(f[5]), parseLong(f[12]), parseDouble(f[11]))
                            .changeAmount(parseDouble(f[7]))
                            .changePercent(parseDouble(f[8]))
                            .dataTimeEpochSec(epoch(f[17] + " " + f[18], HK_DATE))
                            .build();
                }
                case US -> {
                    if (f.length < 28) {
                        return null;
                    }
                    // 0 中文名 1 最新 2 涨跌幅 3 时间 4 涨跌额 5 开 6 高 7 低 ... 10 量(股) ... 27 昨收
                    Double price = parseDouble(f[1]);
                    Double prevClose = parseDouble(f[27]);
                    return base(target, f[0], price, prevClose != null && prevClose > 0 ? prevClose : null,
                            parseDouble(f[5]), parseDouble(f[6]), parseDouble(f[7]), parseLong(f[10]), null)
                            .changeAmount(parseDouble(f[4]))
                            .changePercent(parseDouble(f[2]))
                            .dataTimeEpochSec(epoch(f[3], CN_TIME))
                            .build();
                }
                default -> {
                    return null;
                }
            }
        } catch (Exception e) {
            log.warn("[行情] sina 快照解析失败: {} {}", target.symbol(), e.getMessage());
            return null;
        }
    }

    private QuoteSnapshot.QuoteSnapshotBuilder base(UpstreamTarget target, String name, Double price,
                                                    Double prevClose, Double open, Double high, Double low,
                                                    Long volume, Double turnover) {
        boolean available = price != null && price > 0;
        return QuoteSnapshot.builder()
                .symbol(target.symbol())
                .market(target.market().name())
                .name(name)
                .latestPrice(price)
                .prevClose(prevClose)
                .open(open)
                .high(high)
                .low(low)
                .volume(volume)
                .turnover(turnover)
                .priceAvailable(available);
    }

    private String fetchBody(String url) {
        Request request = new Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .header("User-Agent", "Mozilla/5.0 (financial-news-api)")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new UpstreamException("sina snapshot http " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new UpstreamException("sina snapshot empty body");
            }
            return new String(body.bytes(), GBK);
        } catch (IOException e) {
            throw new UpstreamException("sina snapshot request failed", e);
        }
    }

    /** 新浪请求键：沪 sh + code / 深 sz + code / 港 rt_hk + 5 位 code / 美股 gb_ + 小写代码 */
    private String toSinaKey(UpstreamTarget target) {
        String code = target.symbol().substring(0, target.symbol().lastIndexOf('.'));
        return switch (target.market()) {
            case CN -> (target.symbol().endsWith(".SH") ? "sh" : target.symbol().endsWith(".BJ") ? "bj" : "sz") + code;
            case HK -> "rt_hk" + code;
            case US -> "gb_" + code.toLowerCase();
        };
    }

    /**
     * 新浪 CN 成交量归一化为股：股票字段已是股；沪市指数（sh000xxx，含上证系列/沪深300）
     * 字段是"手"需 ×100；深市指数（sz399xxx）字段已是股。
     */
    private Long normalizeCnVolume(Long rawVolume, UpstreamTarget target) {
        if (rawVolume == null) {
            return null;
        }
        boolean shIndex = target.secType() == com.financial.news.service.quote.QuoteSecType.INDEX
                && target.symbol().startsWith("000");
        return shIndex ? rawVolume * 100 : rawVolume;
    }

    private Double parseDouble(String text) {
        if (text == null || text.isBlank()) {
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
        Double d = parseDouble(text);
        return d == null ? null : d.longValue();
    }

    private Double sub(Double a, Double b) {
        return a == null || b == null ? null : a - b;
    }

    private Double percent(Double price, Double prevClose) {
        if (price == null || prevClose == null || prevClose == 0) {
            return null;
        }
        return (price - prevClose) / prevClose * 100;
    }

    private Long epoch(String text, DateTimeFormatter formatter) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim(), formatter)
                    .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                    .toEpochSecond();
        } catch (Exception e) {
            return null;
        }
    }
}
