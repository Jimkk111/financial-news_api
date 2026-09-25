package com.financial.news.service.crawler.ingest;

import com.financial.news.entity.Category;
import com.financial.news.entity.CrawlAudit;
import com.financial.news.entity.News;
import com.financial.news.entity.NewsTag;
import com.financial.news.entity.Tag;
import com.financial.news.mapper.CategoryMapper;
import com.financial.news.mapper.CrawlAuditMapper;
import com.financial.news.mapper.NewsMapper;
import com.financial.news.mapper.NewsTagMapper;
import com.financial.news.mapper.TagMapper;
import com.financial.news.model.content.Block;
import com.financial.news.model.content.ParagraphBlock;
import com.financial.news.service.NewsService;
import com.financial.news.utils.ContentCodec;
import com.financial.news.utils.FingerprintUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 确定性新闻采集流水线
 * <p>纯 Java 编排（无 LLM）：连接器列表 → URL/标题/指纹三层去重 → 详情抓取 →
 * 质量门禁 → 入库 + 审计。单篇失败只影响单篇，天然部分成功；
 * 每篇结果落 crawl_audit，"探测到未入库"从此可查询。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsIngestService {

    private final List<SourceConnector> connectors;
    private final NewsMapper newsMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final NewsTagMapper newsTagMapper;
    private final CrawlAuditMapper crawlAuditMapper;
    private final QualityGate qualityGate;
    private final AiTaggingService aiTaggingService;
    private final StringRedisTemplate redisTemplate;

    @Value("${crawler.ingest.max-articles-per-source:20}")
    private int maxPerSource;

    @Value("${crawler.ingest.near-dup-threshold:6}")
    private int nearDupThreshold;

    @Value("${crawler.ingest.recent-fingerprint-scan:500}")
    private int recentFingerprintScan;

    @Value("${crawler.ingest.cron-enabled-limit:20}")
    private int cronLimit;

    @Value("${crawler.ingest.min-content-length:200}")
    private int minContentLength;

    /** 回填候选的正文长度阈值：门禁长度加余量，"刚好过线"的疑似摘要型记录也纳入 */
    private int backfillThreshold() {
        return minContentLength + 100;
    }

    /**
     * 定时采集入口，cron 由 crawler.ingest.cron 配置（"-" 表示关闭，默认关闭）
     */
    @Scheduled(cron = "${crawler.ingest.cron:-}")
    public void scheduledIngest() {
        try {
            IngestReport report = ingest(null, cronLimit);
            log.info("定时采集完成: {}", report.summary());
        } catch (Exception e) {
            log.error("定时采集执行失败", e);
        }
    }

    /**
     * 执行采集
     *
     * @param sourceKey 指定数据源（null 表示全部启用的源）
     * @param limit     每源文章数上限
     */
    public IngestReport ingest(String sourceKey, Integer limit) {
        int perSource = limit != null ? Math.min(limit, 50) : maxPerSource;
        IngestReport report = new IngestReport();
        report.runId = "ingest-" + System.currentTimeMillis();

        List<SourceConnector> targets = connectors.stream()
                .filter(c -> (sourceKey == null || c.sourceKey().equals(sourceKey)) && c.enabled())
                .toList();
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("未找到启用的数据源: " + sourceKey);
        }

        List<Long> recentFingerprints = loadRecentFingerprints();
        for (SourceConnector connector : targets) {
            processSource(report, connector, perSource, recentFingerprints);
        }
        report.finishedAt = LocalDateTime.now();
        log.info("采集完成 {}: {}", report.runId, report.summary());
        return report;
    }

    private void processSource(IngestReport report, SourceConnector connector, int limit, List<Long> recentFingerprints) {
        SourceStat stat = report.statOf(connector.sourceKey());
        List<ArticleRef> refs;
        try {
            refs = connector.listLatest(limit);
        } catch (Exception e) {
            log.error("[{}] 列表获取失败", connector.sourceKey(), e);
            stat.errors.add("列表获取失败: " + e.getMessage());
            return;
        }
        stat.listed = refs.size();
        if (refs.isEmpty()) {
            stat.errors.add("列表为空（接口变更或无更新）");
            return;
        }

        Set<String> seenTitles = new HashSet<>();
        for (ArticleRef ref : refs) {
            long started = System.currentTimeMillis();
            try {
                processArticle(report.runId, connector, ref, recentFingerprints, seenTitles, stat, started);
            } catch (Exception e) {
                stat.failed++;
                audit(report.runId, connector.sourceKey(), ref.url(), ref.title(),
                        CrawlAudit.FAILED, safeMessage(e), null, ref.publishTime(), started);
            }
        }
    }

    private void processArticle(String runId, SourceConnector connector, ArticleRef ref, List<Long> recentFingerprints,
                                Set<String> seenTitles, SourceStat stat, long started) throws Exception {
        String url = canonicalizeUrl(ref.url());

        // 1. URL 精确去重（uk_url 唯一索引兜底）
        if (url != null && newsMapper.countByUrl(url) > 0) {
            stat.dupUrl++;
            audit(runId, connector.sourceKey(), url, ref.title(), CrawlAudit.DUP_URL, "URL已存在", null, ref.publishTime(), started);
            return;
        }

        // 2. 标题去重（精确 + 本轮内重复）
        String normalizedTitle = ref.title().replaceAll("\\s+", "");
        if (newsMapper.countByTitle(ref.title()) > 0
                || !seenTitles.add(normalizedTitle)) {
            stat.dupTitle++;
            audit(runId, connector.sourceKey(), url, ref.title(), CrawlAudit.DUP_TITLE, "标题已存在", null, ref.publishTime(), started);
            return;
        }

        // 3. 详情抓取（失败抛出，外层记录 FAILED）
        ArticleDetail detail = connector.fetchDetail(ref);
        String title = detail.title() != null && !detail.title().isBlank() ? detail.title().trim() : ref.title();
        LocalDateTime publishTime = detail.publishTime() != null ? detail.publishTime() : ref.publishTime();

        // 4. 正文提取与清洗 → 块级 JSON
        String contentHtml = absolutizeImages(cleanContentHtml(detail.contentHtml()), url);
        String plainText = Jsoup.parse(contentHtml).text();

        // 5. 质量门禁（时间缺失/正文过短/导航噪声一律拒绝）
        QualityGate.Result gate = qualityGate.check(contentHtml, plainText, publishTime);
        if (!gate.pass()) {
            stat.rejected++;
            audit(runId, connector.sourceKey(), url, title, CrawlAudit.REJECTED, gate.reason(), plainText.length(), publishTime, started);
            return;
        }

        // 6. 正文指纹近似去重（跨源转载识别）
        long fingerprint = FingerprintUtil.simhash(plainText);
        for (Long recent : recentFingerprints) {
            if (recent != null && FingerprintUtil.hammingDistance(fingerprint, recent) <= nearDupThreshold) {
                stat.dupContent++;
                audit(runId, connector.sourceKey(), url, title, CrawlAudit.DUP_CONTENT, "正文近似重复", plainText.length(), publishTime, started);
                return;
            }
        }

        // 7. 入库
        List<Block> blocks = ContentCodec.normalize(ContentCodec.fromHtml(contentHtml));
        if (blocks.isEmpty()) {
            stat.rejected++;
            audit(runId, connector.sourceKey(), url, title, CrawlAudit.REJECTED, "正文无法结构化", plainText.length(), publishTime, started);
            return;
        }
        String summary = cleanSummary(detail.summaryHint() != null && !detail.summaryHint().isBlank()
                ? detail.summaryHint()
                : plainText.substring(0, Math.min(plainText.length(), 200)));
        // 7. AI 分类打标（LLM 仅做内容增强；关闭/失败降级为来源默认分类）
        AiTaggingService.TaggingResult tagging = aiTaggingService.classify(title, plainText);
        Integer categoryId = tagging != null && tagging.category() != null
                ? findOrCreateCategory(tagging.category())
                : findOrCreateCategory(connector.defaultCategory());

        News news = News.builder()
                .title(title)
                .summary(summary)
                .content(contentHtml)
                .contentJson(blocks)
                .publishTime(publishTime)
                .source(connector.sourceName())
                .url(url)
                .contentFingerprint(fingerprint)
                .views(0)
                .hasImage(detail.imageUrl() != null && !detail.imageUrl().isBlank())
                .imageUrl(detail.imageUrl())
                .categoryId(categoryId)
                .build();
        try {
            newsMapper.insert(news);
        } catch (DuplicateKeyException e) {
            stat.dupUrl++;
            audit(runId, connector.sourceKey(), url, title, CrawlAudit.DUP_URL, "并发写入冲突(URL唯一索引)", plainText.length(), publishTime, started);
            return;
        }
        recentFingerprints.add(fingerprint);
        applyTags(news.getId(), tagging != null ? tagging.tags() : null);
        stat.saved++;
        audit(runId, connector.sourceKey(), url, title, CrawlAudit.SAVED,
                "入库ID:" + news.getId() + (tagging != null ? "; AI分类:" + tagging.category() + "; AI标签:" + String.join("/", tagging.tags()) : ""),
                plainText.length(), publishTime, started);
    }

    /** AI 标签入库：逐个容错，单个标签失败不影响文章与其他标签 */
    private void applyTags(Integer newsId, List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return;
        }
        for (String name : tagNames) {
            try {
                Tag existing = tagMapper.selectFirstByName(name);
                Integer tagId;
                if (existing != null) {
                    tagId = existing.getId();
                } else {
                    Tag tag = Tag.builder().name(name).build();
                    tagMapper.insert(tag);
                    evictListCache(NewsService.CACHE_NEWS_TAGS);
                    tagId = tag.getId();
                }
                newsTagMapper.insert(NewsTag.builder().newsId(newsId).tagId(tagId).build());
            } catch (Exception e) {
                log.warn("标签入库失败: name={}, {}", name, e.getMessage());
            }
        }
    }

    /**
     * 存量正文回填：重抓"缺正文"记录并更新
     * <p>候选条件：content_json 为空或 content 过短（软删排除）。按记录的 source
     * 匹配连接器按 URL 重抓详情，重走清洗/门禁/结构化后仅更新正文相关列；
     * 单篇失败保留原值并记审计，不影响其他记录。</p>
     *
     * @param sourceKeyOrName 数据源标识或展示名（null 为全部，按记录来源分派）
     * @param limit           本批处理上限（≤100）
     */
    public IngestReport backfill(String sourceKeyOrName, Integer limit) {
        int per = limit != null ? Math.min(limit, 100) : 20;
        IngestReport report = new IngestReport();
        report.runId = "backfill-" + System.currentTimeMillis();

        Map<String, SourceConnector> byNameOrKey = new HashMap<>();
        for (SourceConnector c : connectors) {
            byNameOrKey.put(c.sourceName(), c);
            byNameOrKey.put(c.sourceKey(), c);
        }
        SourceConnector only = null;
        if (sourceKeyOrName != null) {
            only = byNameOrKey.get(sourceKeyOrName);
            if (only == null) {
                throw new IllegalArgumentException("未找到数据源: " + sourceKeyOrName);
            }
        }

        List<News> candidates = newsMapper.selectBackfillCandidates(backfillThreshold(), per);
        for (News news : candidates) {
            SourceConnector connector = only != null ? only : byNameOrKey.get(news.getSource());
            if (connector == null || news.getUrl() == null) {
                continue; // 用户发布或来源已下线的记录，无法重抓
            }
            SourceStat stat = report.statOf(connector.sourceKey());
            long started = System.currentTimeMillis();
            try {
                ArticleRef ref = connector.refFromUrl(news.getUrl(), news.getTitle(), news.getPublishTime());
                ArticleDetail detail = connector.fetchDetail(ref);
                String contentHtml = absolutizeImages(cleanContentHtml(detail.contentHtml()), news.getUrl());
                String plainText = Jsoup.parse(contentHtml).text();

                QualityGate.Result gate = qualityGate.check(contentHtml, plainText, news.getPublishTime());
                if (!gate.pass()) {
                    stat.rejected++;
                    audit(report.runId, connector.sourceKey(), news.getUrl(), news.getTitle(),
                            CrawlAudit.REJECTED, "回填被门禁拒绝:" + gate.reason(), plainText.length(), news.getPublishTime(), started);
                    continue;
                }
                List<Block> blocks = ContentCodec.normalize(ContentCodec.fromHtml(contentHtml));
                if (blocks.isEmpty()) {
                    stat.rejected++;
                    audit(report.runId, connector.sourceKey(), news.getUrl(), news.getTitle(),
                            CrawlAudit.REJECTED, "回填正文无法结构化", plainText.length(), news.getPublishTime(), started);
                    continue;
                }
                String summary = detail.summaryHint() != null && !detail.summaryHint().isBlank()
                        ? detail.summaryHint()
                        : plainText.substring(0, Math.min(plainText.length(), 200));
                String imageUrl = detail.imageUrl() != null && !detail.imageUrl().isBlank()
                        ? detail.imageUrl() : news.getImageUrl();
                int oldLength = news.getContent() == null ? 0 : news.getContent().length();

                newsMapper.updateContent(News.builder()
                        .id(news.getId())
                        .summary(summary)
                        .content(contentHtml)
                        .contentJson(blocks)
                        .imageUrl(imageUrl)
                        .hasImage(imageUrl != null && !imageUrl.isBlank())
                        .build());
                stat.saved++;
                audit(report.runId, connector.sourceKey(), news.getUrl(), news.getTitle(), CrawlAudit.SAVED,
                        String.format("回填更新:正文 %d → %d 字", oldLength, plainText.length()),
                        plainText.length(), news.getPublishTime(), started);
            } catch (Exception e) {
                stat.failed++;
                audit(report.runId, connector.sourceKey(), news.getUrl(), news.getTitle(),
                        CrawlAudit.FAILED, "回填失败:" + safeMessage(e), null, news.getPublishTime(), started);
            }
        }
        report.finishedAt = LocalDateTime.now();
        log.info("存量回填完成 {}: {}", report.runId, report.summary());
        return report;
    }

    /**
     * 存量正文重结构化：用已入库的 content 重新清洗/结构化，不重新抓取
     * <p>适用两类记录：(a) 带尾部固定文案（新浪二维码/责编、东财声明）的 HTML 正文，
     * 重跑清洗链即可剥离；(b) 旧 Agent 时代的无标签整页文本转储，先剥导航前缀与
     * 页脚后缀再按句分段。每篇更新 content/content_json/summary，image 等其余列不动。</p>
     *
     * @param sourceKeyOrName 数据源标识或展示名（null 为全部）
     * @param limit           本批处理上限（≤500）
     */
    public IngestReport renormalize(String sourceKeyOrName, Integer limit) {
        int per = limit != null ? Math.min(limit, 500) : 200;
        IngestReport report = new IngestReport();
        report.runId = "renormalize-" + System.currentTimeMillis();

        Map<String, SourceConnector> byNameOrKey = new HashMap<>();
        for (SourceConnector c : connectors) {
            byNameOrKey.put(c.sourceName(), c);
            byNameOrKey.put(c.sourceKey(), c);
        }
        String sourceName = null;
        if (sourceKeyOrName != null) {
            SourceConnector c = byNameOrKey.get(sourceKeyOrName);
            if (c == null) {
                throw new IllegalArgumentException("未找到数据源: " + sourceKeyOrName);
            }
            sourceName = c.sourceName();
        }

        List<News> candidates = newsMapper.selectRenormalizeCandidates(sourceName, per);
        for (News news : candidates) {
            SourceStat stat = report.statOf(news.getSource() != null ? news.getSource() : "unknown");
            long started = System.currentTimeMillis();
            try {
                String content = news.getContent();
                if (content == null || content.isBlank()) {
                    continue;
                }
                String newContent;
                List<Block> blocks;
                if (content.contains("<")) {
                    newContent = cleanContentHtml(content);
                    blocks = ContentCodec.normalize(ContentCodec.fromHtml(newContent));
                } else {
                    String text = cleanLegacyTextDump(content);
                    blocks = ContentCodec.fromPlainText(text);
                    // content 列契约是 HTML：纯文本也按段包裹，避免与无标签旧转储的筛选条件再次混淆
                    StringBuilder sb = new StringBuilder();
                    for (Block b : blocks) {
                        if (b instanceof ParagraphBlock p) {
                            sb.append("<p>").append(p.getHtml()).append("</p>\n");
                        }
                    }
                    newContent = sb.toString();
                }
                if (blocks.isEmpty()) {
                    stat.rejected++;
                    audit(report.runId, "renormalize", news.getUrl(), news.getTitle(),
                            CrawlAudit.REJECTED, "重结构化后无内容", null, news.getPublishTime(), started);
                    continue;
                }
                String summary = cleanSummary(ContentCodec.toPlainText(blocks));
                if (summary != null && summary.length() > 200) {
                    summary = summary.substring(0, 200);
                }
                int oldLen = content.length();
                newsMapper.updateContent(News.builder()
                        .id(news.getId())
                        .summary(summary)
                        .content(newContent)
                        .contentJson(blocks)
                        .imageUrl(news.getImageUrl())
                        .hasImage(news.getHasImage())
                        .build());
                stat.saved++;
                audit(report.runId, "renormalize", news.getUrl(), news.getTitle(), CrawlAudit.SAVED,
                        String.format("重结构化:%d字,%d块", newContent.length(), blocks.size()),
                        newContent.length(), news.getPublishTime(), started);
            } catch (Exception e) {
                stat.failed++;
                audit(report.runId, "renormalize", news.getUrl(), news.getTitle(),
                        CrawlAudit.FAILED, "重结构化失败:" + safeMessage(e), null, news.getPublishTime(), started);
            }
        }
        report.finishedAt = LocalDateTime.now();
        log.info("存量重结构化完成 {}: {}", report.runId, report.summary());
        return report;
    }

    /** 最近 N 篇文章的指纹，用于本轮近似去重比对（须为可变列表：新入库的指纹会追加进来） */    private List<Long> loadRecentFingerprints() {
        return newsMapper.selectRecentFingerprints(recentFingerprintScan)
                .stream()
                .map(News::getContentFingerprint)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /** URL 归一化：小写 host、去 fragment 与常见跟踪参数 */
    private String canonicalizeUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            String query = uri.getQuery();
            if (query != null) {
                List<String> kept = new ArrayList<>();
                for (String p : query.split("&")) {
                    String key = p.split("=")[0].toLowerCase();
                    if (!key.startsWith("utm_") && !key.equals("spm") && !key.equals("from")) {
                        kept.add(p);
                    }
                }
                query = kept.isEmpty() ? null : String.join("&", kept);
            }
            return new URI(uri.getScheme().toLowerCase(), uri.getUserInfo(),
                    uri.getHost() == null ? null : uri.getHost().toLowerCase(), uri.getPort(),
                    uri.getPath(), query, null).toString();
        } catch (Exception e) {
            return value.trim();
        }
    }

    /** 移除脚本/内嵌框架与广告类噪声节点，并剥离尾部固定文案（新浪二维码/责编、东财声明） */
    private String cleanContentHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        var doc = Jsoup.parseBodyFragment(html);
        ContentNoiseFilter.remove(doc);
        return BoilerplateStripper.strip(doc.body().html());
    }

    /** 摘要只保留单空格分隔的纯文本（去掉全角空格与换行，列表卡片观感） */
    private String cleanSummary(String summary) {
        if (summary == null) {
            return null;
        }
        String cleaned = summary.replaceAll("[\\s\\u3000]+", " ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * 旧 Agent 时代整页文本转储的清理：剥导航/推广前缀与页脚后缀，只留正文
     */
    private String cleanLegacyTextDump(String text) {
        String t = text.strip();
        // 前缀：导航菜单 + "东方财富APP … 分享到您的 朋友圈"推广块之后才是正文
        int promo = t.indexOf("朋友圈");
        if (promo >= 0 && t.indexOf("东方财富APP") >= 0 && promo < t.length() / 2) {
            t = t.substring(promo + "朋友圈".length()).strip();
        }
        // 后缀：页脚备案/版权/声明块
        for (String marker : new String[]{"官方网站", "信息网络传播视听节目许可证", "版权所有",
                "郑重声明", "免责声明", "违法和不良信息举报"}) {
            int idx = t.indexOf(marker);
            if (idx > 0) {
                t = t.substring(0, idx).strip();
            }
        }
        return t;
    }

    /** 图片 src 归一为绝对地址：相对/协议相对路径在详情页之外无法加载 */
    private String absolutizeImages(String html, String baseUri) {
        if (html == null || html.isBlank() || baseUri == null || baseUri.isBlank()) {
            return html == null ? "" : html;
        }
        var doc = Jsoup.parseBodyFragment(html, baseUri);
        for (Element img : doc.select("img")) {
            String abs = img.absUrl("src");
            if (!abs.isBlank()) {
                img.attr("src", abs);
            }
        }
        return doc.body().html();
    }

    private Integer findOrCreateCategory(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Category existing = categoryMapper.selectFirstByName(name);
        if (existing != null) {
            return existing.getId();
        }
        Category category = Category.builder().name(name).build();
        categoryMapper.insert(category);
        evictListCache(NewsService.CACHE_NEWS_CATEGORIES);
        return category.getId();
    }

    /** 新建分类/标签后失效列表缓存，保证 /api/news/categories|tags 立即可见；Redis 不可用只告警不阻断入库 */
    private void evictListCache(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("分类/标签缓存失效失败（key={}）: {}", key, e.getMessage());
        }
    }

    private void audit(String runId, String source, String url, String title, String status, String reason,
                       Integer contentLength, LocalDateTime publishTime, long started) {
        try {
            crawlAuditMapper.insert(CrawlAudit.builder()
                    .runId(runId)
                    .source(source)
                    .articleUrl(url)
                    .title(title != null && title.length() > 300 ? title.substring(0, 300) : title)
                    .status(status)
                    .reason(reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason)
                    .contentLength(contentLength)
                    .publishTime(publishTime)
                    .durationMs((int) (System.currentTimeMillis() - started))
                    .build());
        } catch (Exception e) {
            log.warn("审计记录写入失败: {}", e.getMessage());
        }
    }

    private String safeMessage(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }

    /** 采集汇总报告 */
    public static class IngestReport {
        public String runId;
        public LocalDateTime finishedAt;
        private final Map<String, SourceStat> stats = new LinkedHashMap<>();

        public SourceStat statOf(String sourceKey) {
            return stats.computeIfAbsent(sourceKey, k -> new SourceStat());
        }

        public Map<String, SourceStat> getStats() {
            return stats;
        }

        public String summary() {
            int saved = 0, skipped = 0, failed = 0;
            for (SourceStat s : stats.values()) {
                saved += s.saved;
                skipped += s.dupUrl + s.dupTitle + s.dupContent + s.rejected;
                failed += s.failed;
            }
            return String.format("saved=%d, skipped=%d, failed=%d", saved, skipped, failed);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("runId", runId);
            map.put("summary", summary());
            map.put("stats", stats);
            return map;
        }
    }

    /** 单源统计 */
    public static class SourceStat {
        public int listed;
        public int saved;
        public int dupUrl;
        public int dupTitle;
        public int dupContent;
        public int rejected;
        public int failed;
        public List<String> errors = new ArrayList<>();
    }
}
