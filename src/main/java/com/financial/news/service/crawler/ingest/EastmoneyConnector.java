package com.financial.news.service.crawler.ingest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 东方财富连接器（SSR 列表页，已验证渲染方式）
 * <p>列表：股市新闻页 cgsxw.html，固定选择器提取链接；
 * 详情：#ContentBody 定点提取，Readability 兜底。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EastmoneyConnector implements SourceConnector {

    private static final String LIST_URL = "https://finance.eastmoney.com/a/cgsxw.html";
    private static final String[] LIST_SELECTORS = {"ul.news_list li a", "div.text a"};
    private static final String[] CONTENT_SELECTORS = {"#ContentBody", ".txtinfos", ".abstract"};
    private static final Pattern RELATIVE_TIME = Pattern.compile("(\\d+)\\s*(分钟|小时|天)前");

    private final CrawlHttpFetcher fetcher;
    private final ReadabilityExtractor readability;

    @Value("${crawler.ingest.sources.eastmoney.enabled:true}")
    private boolean enabled;

    @Value("${crawler.ingest.sources.eastmoney.category:}")
    private String defaultCategory;

    @Override
    public String sourceKey() {
        return "eastmoney";
    }

    @Override
    public String sourceName() {
        return "东方财富";
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
        String html = fetcher.getString(LIST_URL, null);
        Document doc = Jsoup.parse(html, LIST_URL);
        List<ArticleRef> refs = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        for (String selector : LIST_SELECTORS) {
            Elements links = doc.select(selector);
            for (Element link : links) {
                String href = link.absUrl("href");
                if (href.isBlank() || !href.contains("/a/") || !href.endsWith(".html")) {
                    continue;
                }
                if (!seenUrls.add(href)) {
                    continue;
                }
                String title = link.text().trim();
                if (title.isBlank() || title.length() < 4) {
                    continue;
                }
                refs.add(ArticleRef.builder()
                        .refId(href)
                        .url(href)
                        .title(title)
                        .publishTime(parseListTime(link))
                        .build());
                if (refs.size() >= limit) {
                    return refs;
                }
            }
            if (!refs.isEmpty()) {
                break;
            }
        }
        log.info("[{}] 列表获取 {} 篇", sourceKey(), refs.size());
        return refs;
    }

    @Override
    public ArticleDetail fetchDetail(ArticleRef ref) throws Exception {
        String html = fetcher.getString(ref.url(), LIST_URL);
        Document doc = Jsoup.parse(html, ref.url());

        String title = "";
        Element titleEl = doc.selectFirst("#ContentBody .title, .title, h1");
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
                .publishTime(ref.publishTime() != null ? ref.publishTime() : parsePageTime(doc))
                .contentHtml(contentHtml)
                .imageUrl(imageUrl)
                .summaryHint(null)
                .categories(List.of())
                .build();
    }

    /**
     * 列表项内的时间文本（"HH:mm" 或 "x分钟前"），解析失败返回 null（不伪造当前时间）
     */
    private LocalDateTime parseListTime(Element link) {
        Element parent = link.parent();
        if (parent == null) {
            return null;
        }
        Element timeEl = parent.selectFirst("span.time, [class*=time], [class*=date]");
        if (timeEl == null) {
            return null;
        }
        String text = timeEl.text().trim();
        LocalDateTime now = LocalDateTime.now();
        if (text.matches("\\d{2}:\\d{2}")) {
            return now.toLocalDate().atTime(Integer.parseInt(text.substring(0, 2)), Integer.parseInt(text.substring(3, 5)));
        }
        Matcher m = RELATIVE_TIME.matcher(text);
        if (m.find()) {
            int amount = Integer.parseInt(m.group(1));
            return switch (m.group(2)) {
                case "分钟" -> now.minusMinutes(amount);
                case "小时" -> now.minusHours(amount);
                case "天" -> now.minusDays(amount);
                default -> null;
            };
        }
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime parsePageTime(Document doc) {
        Element meta = doc.selectFirst("meta[property=article:published_time], meta[name=publishdate], .time");
        if (meta == null) {
            return null;
        }
        String content = meta.hasAttr("content") ? meta.attr("content") : meta.text();
        try {
            return LocalDateTime.parse(content.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return null;
        }
    }
}
