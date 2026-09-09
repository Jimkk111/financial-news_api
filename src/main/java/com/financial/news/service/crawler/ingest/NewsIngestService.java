package com.financial.news.service.crawler.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financial.news.entity.Category;
import com.financial.news.entity.CrawlAudit;
import com.financial.news.entity.News;
import com.financial.news.mapper.CategoryMapper;
import com.financial.news.mapper.CrawlAuditMapper;
import com.financial.news.mapper.NewsMapper;
import com.financial.news.model.content.Block;
import com.financial.news.utils.ContentCodec;
import com.financial.news.utils.FingerprintUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
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
    private final CrawlAuditMapper crawlAuditMapper;
    private final QualityGate qualityGate;

    @Value("${crawler.ingest.max-articles-per-source:20}")
    private int maxPerSource;

    @Value("${crawler.ingest.near-dup-threshold:6}")
    private int nearDupThreshold;

    @Value("${crawler.ingest.recent-fingerprint-scan:500}")
    private int recentFingerprintScan;

    @Value("${crawler.ingest.cron-enabled-limit:20}")
    private int cronLimit;

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
        if (url != null && newsMapper.selectCount(new LambdaQueryWrapper<News>().eq(News::getUrl, url)) > 0) {
            stat.dupUrl++;
            audit(runId, connector.sourceKey(), url, ref.title(), CrawlAudit.DUP_URL, "URL已存在", null, ref.publishTime(), started);
            return;
        }

        // 2. 标题去重（精确 + 本轮内重复）
        String normalizedTitle = ref.title().replaceAll("\\s+", "");
        if (newsMapper.selectCount(new LambdaQueryWrapper<News>().eq(News::getTitle, ref.title())) > 0
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
        String contentHtml = cleanContentHtml(detail.contentHtml());
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
        String summary = detail.summaryHint() != null && !detail.summaryHint().isBlank()
                ? detail.summaryHint()
                : plainText.substring(0, Math.min(plainText.length(), 200));
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
                .categoryId(findOrCreateCategory(connector.defaultCategory()))
                .build();
        try {
            newsMapper.insert(news);
        } catch (DuplicateKeyException e) {
            stat.dupUrl++;
            audit(runId, connector.sourceKey(), url, title, CrawlAudit.DUP_URL, "并发写入冲突(URL唯一索引)", plainText.length(), publishTime, started);
            return;
        }
        recentFingerprints.add(fingerprint);
        stat.saved++;
        audit(runId, connector.sourceKey(), url, title, CrawlAudit.SAVED, "入库ID:" + news.getId(), plainText.length(), publishTime, started);
    }

    /** 最近 N 篇文章的指纹，用于本轮近似去重比对（须为可变列表：新入库的指纹会追加进来） */
    private List<Long> loadRecentFingerprints() {
        return newsMapper.selectList(new LambdaQueryWrapper<News>()
                        .select(News::getId, News::getContentFingerprint)
                        .isNotNull(News::getContentFingerprint)
                        .orderByDesc(News::getId)
                        .last("LIMIT " + recentFingerprintScan))
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

    /** 移除脚本/样式/内嵌广告类节点 */
    private String cleanContentHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        var doc = Jsoup.parseBodyFragment(html);
        doc.select("script, style, iframe, noscript, ins, [class*=ad], [id*=ad], [class*=share], [class*=recommend]").remove();
        return doc.body().html();
    }

    private Integer findOrCreateCategory(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Category existing = categoryMapper.selectOne(
                new LambdaQueryWrapper<Category>().eq(Category::getName, name).last("LIMIT 1"));
        if (existing != null) {
            return existing.getId();
        }
        Category category = Category.builder().name(name).build();
        categoryMapper.insert(category);
        return category.getId();
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
