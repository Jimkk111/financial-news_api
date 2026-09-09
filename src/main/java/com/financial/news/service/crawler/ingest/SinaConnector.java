package com.financial.news.service.crawler.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 新浪财经连接器（滚动新闻 JSON 接口，已验证可用）
 * <p>列表：feed.mix.sina.com.cn roll 接口（title/url/ctime/intro 齐全）；
 * 详情：抓取文章页 SSR HTML，定点选择器提取正文，Readability 兜底。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SinaConnector implements SourceConnector {

    private static final String LIST_API =
            "https://feed.mix.sina.com.cn/api/roll/get?pageid=153&lid=2516&k=&num=%d&page=1";
    /** 新浪正文候选选择器，按命中优先级排列 */
    private static final String[] CONTENT_SELECTORS = {"#artibody", ".article-content-left", "#reference"};

    private final CrawlHttpFetcher fetcher;
    private final ReadabilityExtractor readability;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${crawler.ingest.sources.sina.enabled:true}")
    private boolean enabled;

    @Value("${crawler.ingest.sources.sina.category:}")
    private String defaultCategory;

    @Override
    public String sourceKey() {
        return "sina";
    }

    @Override
    public String sourceName() {
        return "新浪财经";
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
        String body = fetcher.getString(String.format(LIST_API, Math.max(limit, 10)), null);
        JsonNode data = mapper.readTree(body).path("result").path("data");
        List<ArticleRef> refs = new ArrayList<>();
        for (JsonNode item : data) {
            String url = item.path("url").asText("");
            String title = item.path("title").asText("");
            if (url.isBlank() || title.isBlank() || title.length() < 4) {
                continue;
            }
            long ctime = parseLong(item.path("ctime").asText("0"));
            refs.add(ArticleRef.builder()
                    .refId(url)
                    .url(url)
                    .title(title.trim())
                    .publishTime(ctime > 0
                            ? LocalDateTime.ofInstant(Instant.ofEpochSecond(ctime), ZoneId.of("Asia/Shanghai"))
                            : null)
                    .build());
            if (refs.size() >= limit) {
                break;
            }
        }
        log.info("[{}] 列表获取 {} 篇", sourceKey(), refs.size());
        return refs;
    }

    @Override
    public ArticleDetail fetchDetail(ArticleRef ref) throws Exception {
        String html = fetcher.getString(ref.url(), "https://finance.sina.com.cn/");
        Document doc = Jsoup.parse(html, ref.url());

        String title = "";
        Element titleEl = doc.selectFirst("h1.main-title, h1, .article-title");
        if (titleEl != null) {
            title = titleEl.text();
        }

        String contentHtml = "";
        for (String selector : CONTENT_SELECTORS) {
            Element content = doc.selectFirst(selector);
            if (content != null && content.text().length() >= 100) {
                contentHtml = content.html();
                break;
            }
        }
        if (contentHtml.isBlank()) {
            contentHtml = readability.extract(doc);
        }

        String imageUrl = "";
        Element ogImage = doc.selectFirst("meta[property=og:image]");
        if (ogImage != null) {
            imageUrl = ogImage.attr("content");
        }

        return ArticleDetail.builder()
                .title(title)
                .url(ref.url())
                .publishTime(ref.publishTime()) // ctime 可靠，以列表时间为准
                .contentHtml(contentHtml)
                .imageUrl(imageUrl)
                .summaryHint(null)
                .categories(List.of())
                .build();
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
