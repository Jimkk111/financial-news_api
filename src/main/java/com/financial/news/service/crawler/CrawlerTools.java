package com.financial.news.service.crawler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.news.entity.Category;
import com.financial.news.entity.News;
import com.financial.news.entity.NewsTag;
import com.financial.news.entity.Tag;
import com.financial.news.mapper.CategoryMapper;
import com.financial.news.mapper.NewsMapper;
import com.financial.news.mapper.NewsTagMapper;
import com.financial.news.mapper.TagMapper;
import com.financial.news.model.content.Block;
import com.financial.news.model.content.ImageBlock;
import com.financial.news.model.content.ParagraphBlock;
import com.financial.news.utils.ContentCodec;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 爬虫 Agent 工具集
 * <p>提供 @Tool 注解的方法供 LangChain4j Agent 在 ReAct 循环中自主调用。
 * 工具负责具体的网页请求、解析和数据库操作，Agent 负责决策调用顺序和参数。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrawlerTools {

    private final NewsMapper newsMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final NewsTagMapper newsTagMapper;

    @Value("${crawler.agent.max-articles-per-run:20}")
    private int maxArticlesPerRun;

    @Value("${crawler.agent.http-timeout:120}")
    private int httpTimeout;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter[] DATE_FORMATTERS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"),
    };

    /** 支持的数据源配置 */
    private static final Map<String, SourceConfig> SOURCE_CONFIGS = Map.of(
            "cls", new SourceConfig("财联社", "https://www.cls.cn/", "https://www.cls.cn/telegraph"),
            "eastmoney", new SourceConfig("东方财富", "https://www.eastmoney.com/", "https://finance.eastmoney.com/a/cgsxw.html"),
            "sina", new SourceConfig("新浪财经", "https://finance.sina.com.cn/", "https://finance.sina.com.cn/"),
            "wallstreet", new SourceConfig("华尔街见闻", "https://wallstreetcn.com/", "https://wallstreetcn.com/news/global"),
            "10jqka", new SourceConfig("同花顺", "https://www.10jqka.com.cn/", "https://news.10jqka.com.cn/")
    );

    /** HTML 缓存：URL -> HTML 内容。避免 LLM 在工具间传递巨大 HTML 导致截断 */
    private static final Map<String, String> HTML_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    /** 文章详情缓存：URL -> 解析后的文章详情 JSON。避免 LLM 传递 htmlContent/contentJson 导致截断 */
    private static final Map<String, Map<String, Object>> ARTICLE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static final int MAX_CACHE_SIZE = 500;
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "finance.eastmoney.com", "eastmoney.com", "finance.sina.com.cn",
            "sina.com.cn", "news.10jqka.com.cn", "10jqka.com.cn",
            "wallstreetcn.com", "api-one.wallstcn.com");
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ======================== 工具方法 ========================

    /**
     * 工具：获取指定 URL 的 HTML 内容
     * <p>Agent 通过此工具抓取新闻网页，HTML 自动缓存到内存，后续工具可通过 URL 直接获取。</p>
     *
     * @param url 完整的网页 URL
     * @return 缓存结果摘要（包含 URL 和大小），后续工具可直接通过此 URL 获取完整 HTML
     */
    @Tool("获取指定URL的网页HTML内容并缓存。返回缓存摘要(含URL和大小)。后续工具(parseArticleContent/extractArticleList)可通过同一URL直接使用缓存的HTML，无需再次传递巨大HTML字符串。")
    public String fetchPage(String url) {
        log.info("Agent 调用 fetchPage: {}", url);
        try {
            URI target = validateUrl(url);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(target)
                    .timeout(Duration.ofSeconds(httpTimeout))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return "ERROR: HTTP " + response.statusCode() + " - 页面获取失败";
            }
            if (response.body().length > MAX_RESPONSE_BYTES) {
                return "ERROR: 页面响应超过大小限制";
            }

            String html = new String(response.body(), StandardCharsets.UTF_8);
            // 缓存完整 HTML，后续工具通过 URL 取回
            HTML_CACHE.put(url, html);
            // 缓存超限时清理最早的一半条目
            if (HTML_CACHE.size() > MAX_CACHE_SIZE) {
                int toRemove = MAX_CACHE_SIZE / 2;
                var it = HTML_CACHE.keySet().iterator();
                for (int i = 0; i < toRemove && it.hasNext(); i++) {
                    String oldUrl = it.next();
                    it.remove();
                    ARTICLE_CACHE.remove(oldUrl);
                }
                log.info("缓存清理: 移除 {} 条旧条目，当前 HTML缓存={}, 文章缓存={}", toRemove, HTML_CACHE.size(), ARTICLE_CACHE.size());
            }
            log.info("fetchPage 成功并缓存: {} 字节, url={}", html.length(), url);
            return String.format("OK|url=%s|size=%d", url, html.length());
        } catch (IOException e) {
            log.error("fetchPage 网络异常: {}", e.getMessage());
            return "ERROR: 网络请求失败 - " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: 请求被中断";
        } catch (Exception e) {
            log.error("fetchPage 异常: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 工具：从已缓存的页面中提取新闻文章列表
     * <p>通过 URL 从缓存获取 HTML，使用 CSS 选择器提取文章列表。
     * 必须先调用 fetchPage 缓存页面，再调用此工具。</p>
     *
     * @param url             已缓存的页面 URL（必须先调用 fetchPage）
     * @param linkSelector    文章链接元素的 CSS 选择器（如 "a.news-item"）
     * @param titleSelector   标题元素的 CSS 选择器（相对于链接元素，可为空则用链接文本）
     * @param timeSelector    时间元素的 CSS 选择器（相对于链接元素，可为空）
     * @param baseUrl         用于补全相对链接的基础 URL
     * @return JSON 格式的文章列表，每项包含 url、title、time 字段
     */
    @Tool("从已缓存的页面中提取新闻文章列表。url是已通过fetchPage缓存的页面URL。返回JSON数组，每项含url/title/time字段。linkSelector是链接选择器，titleSelector是标题选择器，timeSelector是时间选择器，baseUrl用于补全相对链接。")
    public String extractArticleList(String url, String linkSelector, String titleSelector, String timeSelector, String baseUrl) {
        log.info("Agent 调用 extractArticleList: url={}, selector={}", url, linkSelector);
        try {
            // 从缓存获取 HTML
            String html = HTML_CACHE.get(url);
            if (html == null || html.isBlank()) {
                return "ERROR: 缓存中未找到该URL的HTML，请先调用 fetchPage 获取页面";
            }
            Document doc = Jsoup.parse(html);
            Elements links = doc.select(linkSelector);
            List<Map<String, String>> articles = new ArrayList<>();
            Set<String> seenUrls = new HashSet<>();

            for (Element link : links) {
                String href = link.attr("abs:href");
                if (href.isBlank()) href = link.attr("href");
                if (href.isBlank()) continue;

                URI resolved = resolveUrl(href, baseUrl != null && !baseUrl.isBlank() ? baseUrl : url);
                if (resolved == null) continue;
                href = canonicalizeUrl(resolved);

                // 去重
                if (!seenUrls.add(href)) continue;

                // 提取标题
                String title = "";
                if (titleSelector != null && !titleSelector.isBlank()) {
                    Element titleEl = link.selectFirst(titleSelector);
                    if (titleEl != null) title = titleEl.text();
                }
                if (title.isBlank()) title = link.text();
                if (title.isBlank() || title.length() < 4) continue;

                // 提取时间
                String time = "";
                if (timeSelector != null && !timeSelector.isBlank()) {
                    Element timeEl = link.selectFirst(timeSelector);
                    if (timeEl != null) time = timeEl.text();
                }
                // 尝试从 data 属性获取时间
                if (time.isBlank()) {
                    time = link.attr("data-time");
                }
                if (time.isBlank()) {
                    // 在父元素中查找时间
                    Element parent = link.parent();
                    if (parent != null) {
                        Element timeEl = parent.selectFirst("time, [class*=time], [class*=date], span[class*=ago]");
                        if (timeEl != null) time = timeEl.text();
                    }
                }

                Map<String, String> article = new LinkedHashMap<>();
                article.put("url", href);
                article.put("title", title.trim());
                article.put("time", time.trim());
                articles.add(article);

                if (articles.size() >= maxArticlesPerRun) break;
            }

            String result = MAPPER.writeValueAsString(articles);
            log.info("extractArticleList 提取到 {} 篇文章", articles.size());
            return result;
        } catch (Exception e) {
            log.error("extractArticleList 异常: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 工具：从已缓存的文章页面中提取正文内容、标题、时间和图片
     * <p>通过 URL 从缓存获取 HTML，使用通用启发式算法自动识别正文区域。
     * 必须先调用 fetchPage 缓存文章页面，再调用此工具。</p>
     *
     * @param url             已缓存的文章页面 URL（必须先调用 fetchPage）
     * @param contentSelector 正文区域的 CSS 选择器（可为空，为空则自动识别）
     * @param titleSelector   标题选择器（可为空）
     * @param timeSelector    时间选择器（可为空）
     * @return JSON 格式的文章详情，含 title、time、imageUrl、htmlContent、plainText 字段
     */
    @Tool("从已缓存的文章页面中提取正文内容并自动生成块级JSON。url是已通过fetchPage缓存的文章URL。返回JSON含title/time/imageUrl/htmlContent/plainText/contentJson字段。contentSelector指定正文选择器(可为空自动识别)，titleSelector指定标题选择器(可为空)，timeSelector指定时间选择器(可为空)。")
    public String parseArticleContent(String url, String contentSelector, String titleSelector, String timeSelector) {
        log.info("Agent 调用 parseArticleContent: url={}, contentSelector={}, titleSelector={}", url, contentSelector, titleSelector);
        try {
            // 从缓存获取 HTML
            String html = HTML_CACHE.get(url);
            if (html == null || html.isBlank()) {
                return "ERROR: 缓存中未找到该URL的HTML，请先调用 fetchPage 获取页面";
            }
            Document doc = Jsoup.parse(html);
            Map<String, Object> result = new LinkedHashMap<>();

            // 1. 提取标题
            String title = "";
            if (titleSelector != null && !titleSelector.isBlank()) {
                Element titleEl = doc.selectFirst(titleSelector);
                if (titleEl != null) title = titleEl.text();
            }
            if (title.isBlank()) {
                // 通用标题提取
                Element h1 = doc.selectFirst("h1");
                if (h1 != null) title = h1.text();
                else {
                    Element ogTitle = doc.selectFirst("meta[property=og:title]");
                    if (ogTitle != null) title = ogTitle.attr("content");
                }
            }
            result.put("title", title.trim());

            // 2. 提取发布时间
            String time = "";
            if (timeSelector != null && !timeSelector.isBlank()) {
                Element timeEl = doc.selectFirst(timeSelector);
                if (timeEl != null) time = timeEl.text();
            }
            if (time.isBlank()) {
                // 通用时间提取
                Element metaTime = doc.selectFirst("meta[property=article:published_time], meta[name=pubdate], meta[name=publishdate]");
                if (metaTime != null) time = metaTime.attr("content");
            }
            result.put("time", time.trim());

            // 3. 提取封面图
            String imageUrl = "";
            Element ogImage = doc.selectFirst("meta[property=og:image]");
            if (ogImage != null) imageUrl = ogImage.attr("content");
            result.put("imageUrl", imageUrl);

            // 4. 提取正文 HTML
            String contentHtml = "";
            if (contentSelector != null && !contentSelector.isBlank()) {
                Elements contentEls = doc.select(contentSelector);
                contentHtml = contentEls.html();
            } else {
                contentHtml = extractMainContent(doc);
            }

            // 清洗：移除 script/style/广告
            contentHtml = cleanContentHtml(contentHtml);
            result.put("htmlContent", contentHtml);

            // 5. 纯文本（用于摘要）— 保留更多内容以保证完整性
            String plainText = Jsoup.parse(contentHtml).text();
            if (plainText.length() > 5000) plainText = plainText.substring(0, 5000);
            result.put("plainText", plainText);

            // 6. 直接生成块级 JSON，避免 LLM 传递 htmlContent 导致截断
            List<Block> blocks = ContentCodec.fromHtml(contentHtml);
            blocks = ContentCodec.normalize(blocks);
            String contentJson = MAPPER.writeValueAsString(blocks);
            result.put("contentJson", contentJson);

            // 缓存文章详情，供 saveNews 通过 URL 直接取回
            ARTICLE_CACHE.put(url, result);

            // 返回短摘要，不返回巨大的 htmlContent 和 contentJson
            Map<String, Object> summaryMap = new LinkedHashMap<>();
            summaryMap.put("title", title.trim());
            summaryMap.put("time", result.get("time"));
            summaryMap.put("imageUrl", imageUrl);
            summaryMap.put("url", url);
            summaryMap.put("blocks", blocks.size());
            String summaryResult = MAPPER.writeValueAsString(summaryMap);
            log.info("parseArticleContent 成功并缓存: 标题={}, 块数={}, url={}", title.trim(), blocks.size(), url);
            return summaryResult;
        } catch (Exception e) {
            log.error("parseArticleContent 异常: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 工具：将 HTML 正文清洗并转换为块级 JSON
     * <p>过滤广告、导航等噪声，转换为符合项目内容契约的 Block 列表。</p>
     *
     * @param htmlContent 文章正文 HTML 内容
     * @return JSON 字符串，表示 Block 列表
     */
    @Tool("将HTML正文清洗并转换为块级JSON。过滤广告和噪声内容，转换为项目内容契约格式。返回JSON数组，每个元素有type字段标识块类型。")
    public String htmlToContentJson(String htmlContent) {
        log.info("Agent 调用 htmlToContentJson: 长度={}", htmlContent == null ? 0 : htmlContent.length());
        if (htmlContent == null || htmlContent.isBlank()) return "[]";
        try {
            List<Block> blocks = ContentCodec.fromHtml(htmlContent);
            // 过滤可能的噪声块
            blocks = ContentCodec.normalize(blocks);
            String json = MAPPER.writeValueAsString(blocks);
            log.info("htmlToContentJson 生成 {} 个块", blocks.size());
            return json;
        } catch (Exception e) {
            log.error("htmlToContentJson 异常: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 工具：保存新闻到数据库
     * <p>优先通过 URL 从缓存获取 htmlContent 和 contentJson，避免 LLM 传递大字符串导致截断。
     * 如果缓存未命中，则使用 LLM 直接传入的参数。</p>
     *
     * @param title          新闻标题
     * @param url            文章页面URL（可选，优先从缓存取数据）
     * @param summary        新闻摘要（可为空）
     * @param publishTimeStr 发布时间字符串（格式：yyyy-MM-dd HH:mm:ss）
     * @param source         新闻来源（如 "财联社"）
     * @param categoryName   分类名称（如 "财经"、"股票"）
     * @param tagNamesStr    标签名称，逗号分隔（如 "A股,牛市,降息"）
     * @return 保存结果：成功返回 "saved:新闻ID"，重复返回 "duplicate:URL"
     */
    @Tool("保存新闻到数据库。url是文章页面URL(优先从缓存取htmlContent和contentJson)。title是标题，source是来源，categoryName是分类名称，tagNamesStr是逗号分隔的标签名称。")
    @org.springframework.transaction.annotation.Transactional
    public String saveNews(String title, String url, String summary, String publishTimeStr,
                           String source, String categoryName, String tagNamesStr) {
        log.info("Agent 调用 saveNews: 标题={}, 来源={}, 分类={}", title, source, categoryName);
        try {
            String htmlContent = null;
            List<Block> contentJson = null;
            String imageUrl = null;

            // 优先从缓存获取完整数据（一次获取，避免重复查找）
            Map<String, Object> cached = null;
            if (url != null && !url.isBlank()) {
                cached = ARTICLE_CACHE.get(url);
                if (cached == null) cached = ARTICLE_CACHE.get(url.replace("https://wallstreetcn.com/articles/", ""));
            }
            if (cached != null) {
                htmlContent = (String) cached.get("htmlContent");
                imageUrl = (String) cached.get("imageUrl");
                String contentJsonStr = (String) cached.get("contentJson");
                if (contentJsonStr != null && !contentJsonStr.isBlank()) {
                    contentJson = MAPPER.readValue(contentJsonStr, new TypeReference<List<Block>>() {});
                }
                // 从缓存补充摘要
                if ((summary == null || summary.isBlank()) && cached.get("plainText") != null) {
                    String plainText = (String) cached.get("plainText");
                    summary = plainText.length() > 200 ? plainText.substring(0, 200) : plainText;
                }
                log.info("saveNews 从缓存获取数据: url={}, 块数={}", url, contentJson != null ? contentJson.size() : 0);
            }

            // 如果 contentJson 仍为空，尝试从 htmlContent 转换
            if (contentJson == null && htmlContent != null && !htmlContent.isBlank()) {
                contentJson = ContentCodec.normalize(ContentCodec.fromHtml(htmlContent));
            }
            if (title == null || title.isBlank()) return "error:标题不能为空";
            if (htmlContent == null || htmlContent.isBlank() || contentJson == null || contentJson.isEmpty()) {
                return "error:正文为空，拒绝入库";
            }
            title = title.trim();
            source = source == null ? null : source.trim();
            categoryName = categoryName == null ? null : categoryName.trim();
            url = canonicalizeUrlValue(url);
            Integer categoryId = null;
            if (categoryName != null && !categoryName.isBlank()) {
                categoryId = findOrCreateCategory(categoryName.trim());
            }

            // 3. 构建新闻实体
            LocalDateTime publishTime = parseDateTime(publishTimeStr);

            News news = News.builder()
                    .title(title)
                    .summary(summary != null ? summary.substring(0, Math.min(summary.length(), 200)) : null)
                    .content(htmlContent)
                    .contentJson(contentJson)
                    .publishTime(publishTime)
                    .source(source)
                    .url(canonicalizeUrlValue(url))
                    .views(0)
                    .hasImage(imageUrl != null && !imageUrl.isBlank())
                    .imageUrl(imageUrl)
                    .categoryId(categoryId)
                    .build();

            newsMapper.insert(news);

            // 4. 处理标签
            if (tagNamesStr != null && !tagNamesStr.isBlank()) {
                String[] tagNames = tagNamesStr.split("[,，]");
                for (String tagName : tagNames) {
                    String name = tagName.trim();
                    if (name.isBlank()) continue;
                    Integer tagId = findOrCreateTag(name);
                    newsTagMapper.insert(NewsTag.builder()
                            .newsId(news.getId())
                            .tagId(tagId)
                            .build());
                }
            }

            log.info("saveNews 成功: id={}, 标题={}", news.getId(), title);
            return "saved:" + news.getId();
        } catch (Exception e) {
            log.error("saveNews 异常: {}", e.getMessage(), e);
            return "error:" + e.getMessage();
        }
    }

    /**
     * 工具：检查新闻是否已存在（按标题去重）
     *
     * @param title 新闻标题
     * @return "exists:true" 或 "exists:false"
     */
    @Tool("检查新闻是否已存在（按标题模糊匹配）。返回exists:true或exists:false。")
    public String checkNewsExists(String title) {
        log.info("Agent 调用 checkNewsExists: {}", title);
        try {
            News existing = newsMapper.selectOne(
                    new LambdaQueryWrapper<News>().eq(News::getTitle, title).last("LIMIT 1"));
            return "exists:" + (existing != null);
        } catch (Exception e) {
            return "exists:false";
        }
    }

    /**
     * 工具：获取已有分类列表
     *
     * @return JSON 格式的分类列表，每项含 id 和 name
     */
    @Tool("获取数据库中已有的新闻分类列表。返回JSON数组，每项含id和name字段。")
    public String listCategories() {
        log.info("Agent 调用 listCategories");
        try {
            List<Category> categories = categoryMapper.selectList(null);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Category c : categories) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", c.getId());
                map.put("name", c.getName());
                result.add(map);
            }
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 工具：获取已有标签列表
     *
     * @return JSON 格式的标签列表，每项含 id 和 name
     */
    @Tool("获取数据库中已有的新闻标签列表。返回JSON数组，每项含id和name字段。")
    public String listTags() {
        log.info("Agent 调用 listTags");
        try {
            List<Tag> tags = tagMapper.selectList(null);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Tag t : tags) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", t.getId());
                map.put("name", t.getName());
                result.add(map);
            }
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 工具：创建新的新闻分类
     *
     * @param categoryName 分类名称
     * @return "created:分类ID" 或 "exists:分类ID"
     */
    @Tool("创建新的新闻分类。如果分类已存在则返回其ID。返回created:ID或exists:ID。")
    public String createCategory(String categoryName) {
        log.info("Agent 调用 createCategory: {}", categoryName);
        try {
            Category existing = categoryMapper.selectOne(
                    new LambdaQueryWrapper<Category>().eq(Category::getName, categoryName.trim()).last("LIMIT 1"));
            if (existing != null) return "exists:" + existing.getId();
            Integer id = findOrCreateCategory(categoryName.trim());
            return "created:" + id;
        } catch (Exception e) {
            return "error:" + e.getMessage();
        }
    }

    /**
     * 工具：创建新的标签
     *
     * @param tagName 标签名称
     * @return "created:标签ID" 或 "exists:标签ID"
     */
    @Tool("创建新的新闻标签。如果标签已存在则返回其ID。返回created:ID或exists:ID。")
    public String createTag(String tagName) {
        log.info("Agent 调用 createTag: {}", tagName);
        try {
            Integer id = findOrCreateTag(tagName.trim());
            return "done:" + id;
        } catch (Exception e) {
            return "error:" + e.getMessage();
        }
    }

    /**
     * 工具：获取数据源信息
     *
     * @param sourceName 数据源名称关键词（如 "cls"、"财联社"、"eastmoney"）
     * @return 数据源配置信息
     */
    @Tool("获取数据源信息。输入数据源名称关键词(如cls/财联社/eastmoney/东方财富/sina/新浪/wallstreet/华尔街见闻/10jqka/同花顺)，返回名称和首页URL。")
    public String getSourceInfo(String sourceName) {
        log.info("Agent 调用 getSourceInfo: {}", sourceName);
        String lower = sourceName.toLowerCase();
        for (Map.Entry<String, SourceConfig> entry : SOURCE_CONFIGS.entrySet()) {
            if (lower.contains(entry.getKey()) || lower.contains(entry.getValue().name)) {
                SourceConfig cfg = entry.getValue();
                return String.format("{\"name\":\"%s\",\"homeUrl\":\"%s\",\"listUrl\":\"%s\"}",
                        cfg.name, cfg.homeUrl, cfg.listUrl);
            }
        }
        return "unknown";
    }

    /**
     * 工具：调用 JSON API 接口
     * <p>用于直接调用网站后端 API（如 SPA 网站的数据接口），支持自定义请求头。
     * 适用于财联社等前端动态加载的网站，直接请求其后端 JSON 接口。</p>
     *
     * @param apiUrl     完整的 API URL
     * @param referer    Referer 请求头（某些 API 需要验证来源）
     * @return JSON 响应内容字符串，超长时自动截断
     */
    @Tool("调用JSON API接口。用于请求SPA网站的后端数据接口。apiUrl是完整API地址，referer是Referer请求头(某些API需要)。返回JSON响应。")
    public String fetchJsonApi(String apiUrl, String referer) {
        log.info("Agent 调用 fetchJsonApi: {}", apiUrl);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(httpTimeout))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET();

            if (referer != null && !referer.isBlank()) {
                reqBuilder.header("Referer", referer);
            }

            HttpResponse<String> response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return "ERROR: HTTP " + response.statusCode() + " - " + response.body().substring(0, Math.min(200, response.body().length()));
            }

            String body = response.body();
            // 不截断 JSON 响应，保证 Agent 能看到完整数据
            log.info("fetchJsonApi 成功: {} 字节", body.length());
            return body;
        } catch (IOException e) {
            log.error("fetchJsonApi 网络异常: {}", e.getMessage());
            return "ERROR: 网络请求失败 - " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: 请求被中断";
        } catch (Exception e) {
            log.error("fetchJsonApi 异常: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 工具：批量检查 URL 是否已存在于数据库（按来源字段匹配）
     *
     * @param urlsJson URL 列表的 JSON 数组
     * @return 新闻数量和去重后的URL数量
     */
    @Tool("批量检查URL是否已爬取过。urlsJson是URL列表JSON数组。返回统计信息。")
    public String checkUrlsExist(String urlsJson) {
        log.info("Agent 调用 checkUrlsExist");
        try {
            List<String> urls = MAPPER.readValue(urlsJson, new TypeReference<List<String>>() {});
            int existing = 0;
            for (String url : urls) {
                String canonical = canonicalizeUrlValue(url);
                if (canonical == null) continue;
                existing += newsMapper.selectCount(new LambdaQueryWrapper<News>().eq(News::getUrl, canonical)) > 0 ? 1 : 0;
            }
            return String.format("{\"totalUrls\":%d,\"existingUrls\":%d,\"missingUrls\":%d}", urls.size(), existing, urls.size() - existing);
        } catch (Exception e) {
            return "error:" + e.getMessage();
        }
    }

    // ======================== 华尔街见闻专用工具 ========================

    /** 华尔街见闻 API 基础地址 */
    private static final String WSCN_API_BASE = "https://api-one.wallstcn.com/apiv1/content";

    /**
     * 工具：获取华尔街见闻新闻列表（自动去重）
     * <p>华尔街见闻的 global-channel API 存在严重的文章重复问题（同一篇文章会被重复返回多次）。
     * 此工具自动按文章 ID 去重，返回干净的不重复列表。</p>
     *
     * @param channel 频道名称，如 "global-channel"（见闻首页）、"a-stock-channel"（A股）
     * @param limit   返回文章数量上限
     * @return 去重后的 JSON 数组，每项含 id、title、time、url、content_short 字段
     */
    @Tool("获取华尔街见闻新闻列表（自动去重）。channel是频道名(如global-channel/a-stock-channel)，limit是数量。返回JSON数组，每项含id/title/time/url/content_short。华尔街见闻API有严重的重复问题，此工具已自动按ID去重。")
    public String fetchWallstreetArticles(String channel, int limit) {
        log.info("Agent 调用 fetchWallstreetArticles: channel={}, limit={}", channel, limit);
        try {
            int safeLimit = Math.min(Math.max(limit, 1), maxArticlesPerRun);
            String apiUrl = WSCN_API_BASE + "/articles?channel=" + java.net.URLEncoder.encode(channel == null ? "global-channel" : channel, StandardCharsets.UTF_8) + "&limit=" + Math.max(safeLimit * 3, 30);
            String body = doFetchJson(apiUrl, "https://wallstreetcn.com/");
            if (body == null || body.isBlank()) {
                return "ERROR: API 请求失败";
            }

            var root = MAPPER.readTree(body);
            var items = root.path("data").path("items");
            if (!items.isArray() || items.isEmpty()) {
                return "ERROR: API 返回空列表";
            }

            // 按文章 ID 去重，保留顺序
            List<Map<String, Object>> articles = new ArrayList<>();
            Set<Long> seenIds = new HashSet<>();
            for (var item : items) {
                long id = item.path("id").asLong(0);
                if (id == 0 || seenIds.contains(id)) continue;
                seenIds.add(id);

                String title = item.path("title").asText("");
                if (title.isBlank() || title.length() < 4) continue;

                long displayTime = item.path("display_time").asLong(0);
                String displayTimeStr = displayTime > 0
                        ? LocalDateTime.ofEpochSecond(displayTime, 0, java.time.ZoneOffset.ofHours(8))
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        : "";

                Map<String, Object> article = new LinkedHashMap<>();
                article.put("id", id);
                article.put("title", title);
                article.put("time", displayTimeStr);
                article.put("url", item.path("uri").asText(""));
                article.put("content_short", item.path("content_short").asText(""));
                articles.add(article);

                if (articles.size() >= limit) break;
            }

            String result = MAPPER.writeValueAsString(articles);
            log.info("fetchWallstreetArticles 去重后返回 {} 篇文章（原始 {} 条）", articles.size(), items.size());
            return result;
        } catch (Exception e) {
            log.error("fetchWallstreetArticles 异常: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 工具：获取华尔街见闻文章详情（含完整正文）
     * <p>华尔街见闻的文章详情 API 必须传 extract=0 才能返回 HTML 正文内容。</p>
     *
     * @param articleId 文章 ID（从 fetchWallstreetArticles 返回的 id 字段获取）
     * @return JSON 格式的文章详情，含 title、time、htmlContent、imageUrl、categories 字段
     */
    @Tool("获取华尔街见闻文章详情。articleId是文章ID(从fetchWallstreetArticles返回)。返回JSON含title/time/htmlContent/imageUrl/categories。")
    public String fetchWallstreetArticleDetail(String articleId) {
        log.info("Agent 调用 fetchWallstreetArticleDetail: articleId={}", articleId);
        try {
            long articleNumericId;
            try {
                articleNumericId = Long.parseLong(articleId);
            } catch (NumberFormatException e) {
                return "ERROR: articleId 必须为数字";
            }
            String apiUrl = WSCN_API_BASE + "/articles/" + articleNumericId + "?extract=0";
            String body = doFetchJson(apiUrl, "https://wallstreetcn.com/");
            if (body == null || body.isBlank()) {
                return "ERROR: API 请求失败";
            }

            var root = MAPPER.readTree(body);
            var data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return "ERROR: API 返回数据为空";
            }

            String title = data.path("title").asText("");
            String content = data.path("content").asText("");
            long displayTime = data.path("display_time").asLong(0);
            String timeStr = displayTime > 0
                    ? LocalDateTime.ofEpochSecond(displayTime, 0, java.time.ZoneOffset.ofHours(8))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    : "";

            // 封面图
            String imageUrl = data.path("image").path("uri").asText("");

            // 分类
            List<String> categories = new ArrayList<>();
            var cats = data.path("categories");
            if (cats.isArray()) {
                for (var cat : cats) {
                    String name = cat.path("name").asText("");
                    if (!name.isBlank()) categories.add(name);
                }
            }

            // 转换 HTML 为 contentJson
            List<Block> blocks = ContentCodec.fromHtml(content);
            blocks = ContentCodec.normalize(blocks);
            String contentJson = MAPPER.writeValueAsString(blocks);

            // 缓存到 ARTICLE_CACHE，供 saveNews 使用
            Map<String, Object> cached = new LinkedHashMap<>();
            cached.put("title", title);
            cached.put("time", timeStr);
            cached.put("imageUrl", imageUrl);
            cached.put("htmlContent", content);
            cached.put("contentJson", contentJson);
            String plainText = Jsoup.parse(content).text();
            if (plainText.length() > 5000) plainText = plainText.substring(0, 5000);
            cached.put("plainText", plainText);
            String articleUrl = canonicalizeUrl(URI.create("https://wallstreetcn.com/articles/" + articleId));
            ARTICLE_CACHE.put(articleUrl, cached);
            ARTICLE_CACHE.put(articleId, cached);

            // 返回摘要
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", articleNumericId);
            summary.put("title", title);
            summary.put("time", timeStr);
            summary.put("imageUrl", imageUrl);
            summary.put("url", articleUrl);
            summary.put("categories", categories);
            summary.put("blocks", blocks.size());
            summary.put("content_preview", plainText.substring(0, Math.min(300, plainText.length())));

            String result = MAPPER.writeValueAsString(summary);
            log.info("fetchWallstreetArticleDetail 成功: id={}, 标题={}, 块数={}", articleId, title, blocks.size());
            return result;
        } catch (Exception e) {
            log.error("fetchWallstreetArticleDetail 异常: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    // ======================== 私有辅助方法 ========================

    private URI validateUrl(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("URL 不能为空");
        URI uri = URI.create(value.trim());
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || host == null) {
            throw new IllegalArgumentException("仅支持 HTTP/HTTPS URL");
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        boolean allowed = ALLOWED_HOSTS.stream().anyMatch(h -> lowerHost.equals(h) || lowerHost.endsWith("." + h));
        if (!allowed) throw new IllegalArgumentException("不允许访问该数据源地址");
        try {
            java.net.InetAddress address = java.net.InetAddress.getByName(host);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()) throw new IllegalArgumentException("禁止访问内网地址");
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("无法解析目标地址", e);
        }
        return uri;
    }

    private URI resolveUrl(String href, String baseUrl) {
        try {
            URI base = validateUrl(baseUrl);
            URI resolved = base.resolve(href.trim());
            return validateUrl(resolved.toString());
        } catch (Exception e) {
            log.debug("忽略无效文章 URL: {}", href);
            return null;
        }
    }

    private String canonicalizeUrl(URI uri) {
        try {
            return new URI(uri.getScheme().toLowerCase(Locale.ROOT), uri.getUserInfo(),
                    uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), uri.getPath(), uri.getQuery(), null).toString();
        } catch (Exception e) {
            return uri.toString();
        }
    }

    private String canonicalizeUrlValue(String value) {
        if (value == null || value.isBlank()) return null;
        try { return canonicalizeUrl(validateUrl(value)); }
        catch (Exception e) { return value.trim(); }
    }


    private Integer findOrCreateCategory(String name) {
        Category existing = categoryMapper.selectOne(
                new LambdaQueryWrapper<Category>().eq(Category::getName, name));
        if (existing != null) return existing.getId();

        Category category = Category.builder().name(name).build();
        categoryMapper.insert(category);
        log.info("创建新分类: name={}, id={}", name, category.getId());
        return category.getId();
    }

    /**
     * 查找或创建标签
     */
    private Integer findOrCreateTag(String name) {
        Tag existing = tagMapper.selectOne(
                new LambdaQueryWrapper<Tag>().eq(Tag::getName, name));
        if (existing != null) return existing.getId();

        Tag tag = Tag.builder().name(name).build();
        tagMapper.insert(tag);
        log.info("创建新标签: name={}, id={}", name, tag.getId());
        return tag.getId();
    }

    /**
     * 解析时间字符串为 LocalDateTime
     */
    private LocalDateTime parseDateTime(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return LocalDateTime.now();
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDateTime.parse(timeStr.trim(), formatter);
            } catch (Exception ignored) {
            }
        }
        // 尝试匹配相对时间（如 "3小时前"）
        try {
            Pattern pattern = Pattern.compile("(\\d+)\\s*(分钟|小时|天)前");
            Matcher matcher = pattern.matcher(timeStr);
            if (matcher.find()) {
                int amount = Integer.parseInt(matcher.group(1));
                String unit = matcher.group(2);
                return switch (unit) {
                    case "分钟" -> LocalDateTime.now().minusMinutes(amount);
                    case "小时" -> LocalDateTime.now().minusHours(amount);
                    case "天" -> LocalDateTime.now().minusDays(amount);
                    default -> LocalDateTime.now();
                };
            }
        } catch (Exception ignored) {
        }
        return LocalDateTime.now();
    }

    /**
     * 通用正文区域识别 — 基于启发式算法
     */
    private String extractMainContent(Document doc) {
        // 移除无关元素
        doc.select("script, style, nav, header, footer, aside, .ad, .ads, .advertisement, .sidebar, .comment, .related").remove();

        // 按优先级尝试常见正文选择器
        String[] selectors = {
                "article", "[class*=article]", "[class*=content]", "[class*=detail]",
                "[class*=body]", "[class*=text]", "[class*=post]", "[class*=story]",
                ".news-content", "#content", "#article",
                "[class*=news]", "[class*=main]", "[class*=article-body]",
                "[class*=article-content]", "[class*=entry-content]",
                "[class*=post-content]", "[class*=story-body]",
                "[class*=article-text]", "[class*=content-body]"
        };

        for (String selector : selectors) {
            Elements elements = doc.select(selector);
            if (!elements.isEmpty()) {
                // 选择文本最长的块
                Element best = null;
                int maxLen = 0;
                for (Element el : elements) {
                    String text = el.text();
                    if (text.length() > maxLen) {
                        maxLen = text.length();
                        best = el;
                    }
                }
                if (best != null && maxLen > 200) {
                    return best.html();
                }
            }
        }

        // 回退：取 <p> 标签最多的容器
        Elements paragraphs = doc.select("p");
        if (!paragraphs.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Element p : paragraphs) {
                String text = p.text();
                if (text.length() > 30) { // 过滤短文本（通常是导航等）
                    sb.append(p.outerHtml());
                }
            }
            if (!sb.isEmpty()) return sb.toString();
        }

        // 回退：尝试其他块级元素
        String[] blockSelectors = {"div", "section", "main"};
        for (String selector : blockSelectors) {
            Elements blocks = doc.select(selector);
            if (!blocks.isEmpty()) {
                Element best = null;
                int maxLen = 0;
                for (Element el : blocks) {
                    String text = el.text();
                    if (text.length() > maxLen) {
                        maxLen = text.length();
                        best = el;
                    }
                }
                if (best != null && maxLen > 200) {
                    return best.html();
                }
            }
        }

        // 最终回退：body 的纯文本
        return doc.body() != null ? doc.body().html() : "";
    }

    /**
     * 清洗正文 HTML — 移除广告、脚本和噪声
     */
    private String cleanContentHtml(String html) {
        Document doc = Jsoup.parseBodyFragment(html);
        // 移除脚本、样式
        doc.select("script, style, iframe, noscript, ins").remove();
        // 移除广告相关
        doc.select("[class*=ad], [class*=ads], [id*=ad], [id*=ads], [class*=sponsor], [class*=promo]").remove();
        // 移除社交分享按钮
        doc.select("[class*=share], [class*=social], [class*=btn-share]").remove();
        // 移除相关推荐
        doc.select("[class*=recommend], [class*=related], [class*=hot-news]").remove();
        // 移除版权信息
        doc.select("[class*=copyright], [class*=disclaimer]").remove();
        return doc.body().html();
    }

    /**
     * 数据源配置记录
     */
    record SourceConfig(String name, String homeUrl, String listUrl) {}

    /**
     * 私有方法：执行 JSON API 请求并返回响应体
     */
    private String doFetchJson(String apiUrl, String referer) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(httpTimeout))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET();

            if (referer != null && !referer.isBlank()) {
                reqBuilder.header("Referer", referer);
            }

            HttpResponse<String> response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.error("doFetchJson HTTP {} for {}", response.statusCode(), apiUrl);
                return null;
            }
            return response.body();
        } catch (Exception e) {
            log.error("doFetchJson 异常: {}", e.getMessage());
            return null;
        }
    }
}
