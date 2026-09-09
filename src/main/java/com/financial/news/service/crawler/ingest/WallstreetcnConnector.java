package com.financial.news.service.crawler.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 华尔街见闻连接器（官方 JSON API，已验证可用）
 * <p>列表：/apiv1/content/articles（同一文章会重复返回，按 ID 去重）；
 * 详情：/apiv1/content/articles/{id}?extract=0 返回完整 HTML 正文。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WallstreetcnConnector implements SourceConnector {

    private static final String API_BASE = "https://api-one.wallstcn.com/apiv1/content";

    private final CrawlHttpFetcher fetcher;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${crawler.ingest.sources.wallstreetcn.enabled:true}")
    private boolean enabled;

    @Value("${crawler.ingest.sources.wallstreetcn.category:}")
    private String defaultCategory;

    @Override
    public String sourceKey() {
        return "wallstreetcn";
    }

    @Override
    public String sourceName() {
        return "华尔街见闻";
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public String defaultCategory() {
        return defaultCategory.isBlank() ? null : defaultCategory;
    }

    @Override
    public List<ArticleRef> listLatest(int limit) throws Exception {
        // API 存在重复返回问题，多取 3 倍量再按 ID 去重
        String body = fetcher.getString(
                API_BASE + "/articles?channel=global-channel&limit=" + Math.max(limit * 3, 30),
                "https://wallstreetcn.com/");
        JsonNode items = mapper.readTree(body).path("data").path("items");
        List<ArticleRef> refs = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        for (JsonNode item : items) {
            long id = item.path("id").asLong(0);
            if (id == 0 || !seenIds.add(id)) {
                continue;
            }
            String title = item.path("title").asText("");
            if (title.isBlank() || title.length() < 4) {
                continue;
            }
            String url = item.path("uri").asText("");
            if (url.isBlank()) {
                url = "https://wallstreetcn.com/articles/" + id;
            }
            refs.add(ArticleRef.builder()
                    .refId(String.valueOf(id))
                    .url(url)
                    .title(title.trim())
                    .publishTime(toTime(item.path("display_time").asLong(0)))
                    .build());
            if (refs.size() >= limit) {
                break;
            }
        }
        log.info("[{}] 列表获取 {} 篇（原始 {} 条）", sourceKey(), refs.size(), items.size());
        return refs;
    }

    @Override
    public ArticleDetail fetchDetail(ArticleRef ref) throws Exception {
        String body = fetcher.getString(
                API_BASE + "/articles/" + ref.refId() + "?extract=0",
                "https://wallstreetcn.com/");
        JsonNode data = mapper.readTree(body).path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new IllegalStateException("API 返回数据为空");
        }
        List<String> categories = new ArrayList<>();
        JsonNode cats = data.path("categories");
        if (cats.isArray()) {
            cats.forEach(c -> {
                String name = c.path("name").asText("");
                if (!name.isBlank()) {
                    categories.add(name);
                }
            });
        }
        return ArticleDetail.builder()
                .title(data.path("title").asText(""))
                .url(ref.url())
                .publishTime(toTime(data.path("display_time").asLong(0)))
                .contentHtml(data.path("content").asText(""))
                .imageUrl(data.path("image").path("uri").asText(""))
                .summaryHint(null)
                .categories(categories)
                .build();
    }

    private LocalDateTime toTime(long epochSeconds) {
        return epochSeconds > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.of("Asia/Shanghai"))
                : null;
    }
}
