package com.financial.news.service.crawler.ingest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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
 * 详情：#ContentBody 定点提取，Readability 兜底，长文自动跟进 xxx_N.html 分页。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EastmoneyConnector implements SourceConnector {

    private static final String LIST_URL = "https://finance.eastmoney.com/a/cgsxw.html";
    /** 文章 URL 模式（/a/纯数字.html）；列表页改版频繁，按 URL 过滤全页链接比固定容器选择器稳 */
    private static final Pattern ARTICLE_URL = Pattern.compile(".*/a/\\d+\\.html$");
    /** 页面上的时间文本："2026年09月24日 11:42" 或 "2026-09-24 11:42:00" */
    private static final Pattern FLEX_TIME =
            Pattern.compile("(\\d{4})[年-](\\d{1,2})[月-](\\d{1,2})日?\\s+(\\d{1,2}):(\\d{2})");
    private static final String[] CONTENT_SELECTORS = {"#ContentBody", ".txtinfos", ".abstract"};
    private static final Pattern RELATIVE_TIME = Pattern.compile("(\\d+)\\s*(分钟|小时|天)前");
    /** 分页跟进的页数上限（防异常页面死循环） */
    private static final int MAX_PAGES = 10;

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
        // 不依赖具体容器：全页扫链接，按文章 URL 模式过滤（热榜与图文列表都能覆盖）
        for (Element link : doc.select("a[href]")) {
            String href = link.absUrl("href");
            if (href.isBlank() || !ARTICLE_URL.matcher(href).matches()) {
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

        // 页面无 h1：真标题只在 <title>（带站点后缀），".title" 会命中侧栏组件（如"行情中心"）
        String title = doc.title().replaceAll("[_\\-|]\\s*东方财富网.*$", "").strip();
        if (title.isBlank()) {
            Element h1 = doc.selectFirst("h1");
            if (h1 != null) {
                title = h1.text().strip();
            }
        }

        StringBuilder contentHtml = new StringBuilder(extractContent(doc));
        // 长文分页跟进：续页形如 xxx_2.html，首页 pager 通常含全部页码链接
        for (String pageUrl : collectPageUrls(doc, ref.url())) {
            Document pageDoc = Jsoup.parse(fetcher.getString(pageUrl, LIST_URL), pageUrl);
            String part = extractContent(pageDoc);
            if (!part.isBlank()) {
                contentHtml.append(part);
            }
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
                .contentHtml(contentHtml.toString())
                .imageUrl(imageUrl)
                .summaryHint(ref.summaryHint())
                .categories(List.of())
                .build();
    }

    private String extractContent(Document doc) {
        for (String selector : CONTENT_SELECTORS) {
            Element content = doc.selectFirst(selector);
            if (content != null && content.text().length() >= 100) {
                return content.html();
            }
        }
        return readability.extract(doc);
    }

    /** 收集续页链接（xxx_2.html …），按页码升序去重，上限 MAX_PAGES；无分页返回空列表 */
    private List<String> collectPageUrls(Document doc, String articleUrl) {
        String file = articleUrl.substring(articleUrl.lastIndexOf('/') + 1);
        String id = file.replaceFirst("\\.html?$", "");
        if (id.isEmpty() || id.contains("_")) {
            return List.of();
        }
        Pattern pagePattern = Pattern.compile(".*/" + Pattern.quote(id) + "_(\\d+)\\.html?$");
        java.util.TreeMap<Integer, String> pages = new java.util.TreeMap<>();
        for (Element a : doc.select("a[href]")) {
            String href = a.absUrl("href");
            Matcher m = pagePattern.matcher(href);
            if (m.matches()) {
                int n = Integer.parseInt(m.group(1));
                if (n >= 2 && n <= MAX_PAGES) {
                    pages.putIfAbsent(n, href);
                }
            }
        }
        return List.copyOf(pages.values());
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
        // 页面已无 published_time meta，时间在 .tipbox .infos .item（"2026年09月24日 11:42"）
        Element meta = doc.selectFirst("meta[property=article:published_time], meta[name=publishdate]");
        if (meta != null) {
            LocalDateTime t = parseFlexibleTime(meta.attr("content"));
            if (t != null) {
                return t;
            }
        }
        Element timeEl = doc.selectFirst(".tipbox .item, .time");
        return timeEl != null ? parseFlexibleTime(timeEl.text()) : null;
    }

    /** 兼容 "yyyy年MM月dd日 HH:mm" / "yyyy-MM-dd HH:mm[:ss]" / ISO 前缀等写法 */
    private LocalDateTime parseFlexibleTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher m = FLEX_TIME.matcher(value.trim());
        if (m.find()) {
            try {
                return LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
            } catch (Exception ignore) {
                // 日期越界等格式异常，落空由调用方处理
            }
        }
        try {
            return LocalDateTime.parse(value.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return null;
        }
    }
}
