package com.financial.news.service.crawler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 爬虫工作流编排器 — 协调多个专职 Agent 完成新闻采集任务
 * <p>AI 是整个工作流的核心，每个 Agent 阶段由 LLM 自主决策。
 * 编排器负责阶段间的数据传递和流程控制，不包含业务逻辑。</p>
 *
 * <h3>工作流</h3>
 * <pre>
 *   用户指令 → PlannerAgent(规划) → ScraperAgent(抓取) → ProcessorAgent(处理) → WriterAgent(入库)
 * </pre>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Component
public class CrawlerOrchestrator {

    private final PlannerAgent plannerAgent;
    private final ScraperAgent scraperAgent;
    private final ProcessorAgent processorAgent;
    private final WriterAgent writerAgent;

    @Value("${crawler.agent.max-articles-per-run:20}")
    private int maxArticlesPerRun;

    public CrawlerOrchestrator(PlannerAgent plannerAgent, ScraperAgent scraperAgent,
                               ProcessorAgent processorAgent, WriterAgent writerAgent) {
        this.plannerAgent = plannerAgent;
        this.scraperAgent = scraperAgent;
        this.processorAgent = processorAgent;
        this.writerAgent = writerAgent;
    }

    /**
     * 执行完整的多 Agent 爬取工作流
     *
     * @param userInstruction 用户自然语言指令
     * @return 执行结果摘要
     */
    public String execute(String userInstruction) {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setUserInstruction(userInstruction);

        log.info("========== 多 Agent 爬取工作流启动 ==========");
        log.info("用户指令: {}", userInstruction);

        // ====== 阶段 1: PlannerAgent — 制定爬取计划 ======
        String planResult = executePlanner(ctx);
        if (planResult == null) {
            return "工作流失败: PlannerAgent 无法制定爬取计划";
        }

        // ====== 阶段 2: ScraperAgent — 抓取页面 ======
        String scrapeResult = executeScraper(ctx);
        if (scrapeResult == null || ctx.getArticleList().isEmpty()) {
            return "工作流失败: ScraperAgent 未能抓取到任何文章\n" + ctx.getExecutionSummary();
        }

        // ====== 阶段 3: ProcessorAgent — 解析正文 ======
        executeProcessor(ctx);

        // ====== 阶段 4: WriterAgent — 入库 ======
        executeWriter(ctx);

        // ====== 输出结果 ======
        String summary = ctx.getExecutionSummary();
        log.info("========== 工作流完成 ==========");
        log.info(summary);
        for (String entry : ctx.getExecutionLog()) {
            log.info("  {}", entry);
        }

        return buildFinalReport(ctx);
    }

    /**
     * 阶段 1: 规划
     */
    private String executePlanner(WorkflowContext ctx) {
        long start = System.currentTimeMillis();
        ctx.log("PlannerAgent", "开始分析用户指令...");

        String prompt = buildPlannerPrompt(ctx);
        String plan;
        try {
            plan = plannerAgent.plan(prompt);
        } catch (Exception e) {
            log.error("PlannerAgent 执行异常", e);
            ctx.log("PlannerAgent", "异常: " + e.getMessage());
            return null;
        }

        ctx.setCrawlPlan(plan);
        ctx.setPlannerTime(System.currentTimeMillis() - start);
        ctx.log("PlannerAgent", "计划制定完成 (耗时" + ctx.getPlannerTime() + "ms)");
        log.info("PlannerAgent 输出:\n{}", plan);
        return plan;
    }

    /**
     * 阶段 2: 抓取
     */
    private String executeScraper(WorkflowContext ctx) {
        long start = System.currentTimeMillis();
        ctx.log("ScraperAgent", "开始抓取页面...");

        String prompt = buildScraperPrompt(ctx);
        String result;
        try {
            result = scraperAgent.scrape(prompt);
        } catch (Exception e) {
            log.error("ScraperAgent 执行异常", e);
            ctx.log("ScraperAgent", "异常: " + e.getMessage());
            return null;
        }

        // 从结果中解析文章列表
        ctx.setArticleList(extractArticleListFromResult(result));
        ctx.setTotalFetched(ctx.getArticleList().size());
        ctx.setScraperTime(System.currentTimeMillis() - start);
        ctx.log("ScraperAgent", "抓取完成: " + ctx.getArticleList().size() + " 篇文章 (耗时" + ctx.getScraperTime() + "ms)");
        log.info("ScraperAgent 输出:\n{}", result);
        return result;
    }

    /**
     * 阶段 3: 处理
     */
    private void executeProcessor(WorkflowContext ctx) {
        long start = System.currentTimeMillis();
        ctx.log("ProcessorAgent", "开始逐篇解析正文...");

        // 按最大数量限制截取
        int limit = Math.min(ctx.getArticleList().size(), maxArticlesPerRun);
        List<Map<String, Object>> toProcess = new ArrayList<>(ctx.getArticleList().subList(0, limit));

        String prompt = buildProcessorPrompt(ctx, toProcess);
        String result;
        try {
            result = processorAgent.process(prompt);
        } catch (Exception e) {
            log.error("ProcessorAgent 执行异常", e);
            ctx.log("ProcessorAgent", "异常: " + e.getMessage());
            ctx.setProcessorTime(System.currentTimeMillis() - start);
            return;
        }

        ctx.setProcessedArticles(extractProcessedListFromResult(result));
        ctx.setProcessorTime(System.currentTimeMillis() - start);
        ctx.log("ProcessorAgent", "处理完成: " + ctx.getProcessedArticles().size() + " 篇 (耗时" + ctx.getProcessorTime() + "ms)");
        log.info("ProcessorAgent 输出:\n{}", result);
    }

    /**
     * 阶段 4: 入库
     */
    private void executeWriter(WorkflowContext ctx) {
        long start = System.currentTimeMillis();
        ctx.log("WriterAgent", "开始入库...");

        if (ctx.getProcessedArticles().isEmpty()) {
            ctx.log("WriterAgent", "无文章需要入库");
            return;
        }

        String prompt = buildWriterPrompt(ctx);
        String result;
        try {
            result = writerAgent.write(prompt);
        } catch (Exception e) {
            log.error("WriterAgent 执行异常", e);
            ctx.log("WriterAgent", "异常: " + e.getMessage());
            ctx.setWriterTime(System.currentTimeMillis() - start);
            return;
        }

        // 统计结果
        parseSaveResults(ctx, result);
        ctx.setWriterTime(System.currentTimeMillis() - start);
        ctx.log("WriterAgent", "入库完成: 保存" + ctx.getTotalSaved() + "篇, 跳过" + ctx.getTotalSkipped() + "篇 (耗时" + ctx.getWriterTime() + "ms)");
        log.info("WriterAgent 输出:\n{}", result);
    }

    // ======================== Prompt 构建 ========================

    private String buildPlannerPrompt(WorkflowContext ctx) {
        return """
                你是一个专业的新闻采集规划师。你的任务是分析用户的爬取指令，制定详细的爬取计划。

                ## 你的职责
                - 理解用户想要爬取什么内容（哪些网站、什么类型的新闻、多少篇）
                - 选择合适的数据源和 CSS 选择器
                - 输出结构化的爬取计划

                ## 可用数据源

                ### 东方财富 (eastmoney) — SSR渲染，推荐
                - 列表页: https://finance.eastmoney.com/a/cgsxw.html
                - 列表选择器: "ul.news_list li a" 或 "div.text a"
                - 文章正文选择器: "#ContentBody"
                - 标题选择器: ".title"
                - 类型: ssr

                ### 新浪财经 (sina) — SSR渲染
                - 列表页: https://finance.sina.com.cn/
                - 选择器: 可留空自动识别
                - 类型: ssr

                ### 同花顺 (10jqka) — SSR渲染
                - 列表页: https://news.10jqka.com.cn/
                - 选择器: 可留空自动识别
                - 类型: ssr

                ### 华尔街见闻 (wallstreetcn) — SPA，需用API
                - 频道: global-channel（见闻首页）, a-stock-channel（A股）
                - 类型: api
                - 专用工具: fetchWallstreetArticles(channel, limit), fetchWallstreetArticleDetail(articleId)

                ### 财联社 (cls) — SPA，无法爬取
                - 已全面转为SPA架构，请跳过

                ## 输出格式
                请严格按以下 JSON 格式输出爬取计划：
                ```json
                {
                  "sources": [
                    {
                      "name": "数据源名称",
                      "key": "数据源key(如eastmoney)",
                      "type": "ssr 或 api",
                      "listUrl": "列表页URL",
                      "linkSelector": "链接选择器",
                      "titleSelector": "标题选择器",
                      "timeSelector": "时间选择器",
                      "contentSelector": "正文选择器",
                      "baseUrl": "用于补全相对链接的基础URL"
                    }
                  ],
                  "maxArticles": 20,
                  "reasoning": "选择这些数据源的原因"
                }
                ```
                只输出 JSON，不要输出其他内容。

                ## 用户指令
                %s
                """.formatted(ctx.getUserInstruction());
    }

    private String buildScraperPrompt(WorkflowContext ctx) {
        return """
                你是一个专业的网页爬取执行者。你的任务是根据爬取计划，抓取页面并提取文章列表。

                ## 你的职责
                - 根据计划中的数据源配置，抓取列表页
                - 提取文章链接、标题、时间
                - 输出干净的文章列表

                ## 工具使用规则
                - **SSR 类型**: 先调用 fetchPage(url=列表页URL)，再调用 extractArticleList(url=同一URL, linkSelector, titleSelector, timeSelector, baseUrl)
                - **API 类型(华尔街见闻)**: 直接调用 fetchWallstreetArticles(channel, limit)
                - fetchPage 返回缓存摘要，后续工具通过同一 URL 从缓存获取 HTML
                - 不要把 HTML 字符串传给工具的第一个参数，必须传 URL

                ## 爬取计划
                %s

                请根据计划逐个数据源执行抓取，最后输出一个 JSON 数组，每篇文章包含:
                - title: 标题
                - url: 文章URL（如果是API类型，用文章ID）
                - source: 来源
                - time: 发布时间
                - type: 数据源类型(ssr/api)
                - contentSelector: 正文选择器（SSR类型）
                - titleSelector: 标题选择器（SSR类型）
                - channel: 频道名（API类型）

                只输出 JSON 数组，不要输出其他内容。
                """.formatted(ctx.getCrawlPlan());
    }

    private String buildProcessorPrompt(WorkflowContext ctx, List<Map<String, Object>> articles) {
        String articleListJson;
        try {
            articleListJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(articles);
        } catch (Exception e) {
            articleListJson = "[]";
        }

        return """
                你是一个专业的文章内容处理者。你的任务是逐篇获取文章详情，提取完整正文，并检查是否已存在。

                ## 你的职责
                - 对每篇文章，先 fetchPage 获取页面，再 parseArticleContent 提取正文
                - 华尔街见闻文章用 fetchWallstreetArticleDetail(articleId) 直接获取
                - 检查每篇是否已存在（checkNewsExists）
                - 输出处理后的完整文章数据

                ## 工具使用规则（SSR文章 - 必须严格按顺序）
                1. fetchPage(url=文章URL) → 返回 "OK|url=...|size=..."
                2. parseArticleContent(url=同一URL, contentSelector, titleSelector, timeSelector) → 返回摘要
                3. checkNewsExists(title=标题) → 返回 exists:true/false

                ## 工具使用规则（API文章 - 华尔街见闻）
                1. fetchWallstreetArticleDetail(articleId=文章ID) → 返回详情
                2. checkNewsExists(title=标题) → 返回 exists:true/false

                ## 注意
                - parseArticleContent 内部已自动生成 contentJson 并缓存，无需调用 htmlToContentJson
                - saveNews 的 url 参数会从缓存取数据，不要手动传递 htmlContent/contentJson

                ## 待处理的文章列表
                %s

                对每篇文章执行上述流程，最后输出 JSON 数组，每篇包含:
                - title: 标题
                - url: 文章URL（用于 saveNews 的 url 参数）
                - summary: 正文摘要（前200字）
                - time: 发布时间
                - source: 来源
                - categoryName: 分类名称
                - tagNames: 标签名称（逗号分隔）
                - exists: 是否已存在(true/false)
                - blocks: 内容块数量

                只输出 JSON 数组，不要输出其他内容。
                """.formatted(articleListJson);
    }

    private String buildWriterPrompt(WorkflowContext ctx) {
        String processedJson;
        try {
            processedJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(ctx.getProcessedArticles());
        } catch (Exception e) {
            processedJson = "[]";
        }

        return """
                你是一个专业的新闻入库操作者。你的任务是将处理后的文章数据保存到数据库。

                ## 你的职责
                - 跳过已存在的文章（exists=true）
                - 对不存在的文章，调用 saveNews 入库
                - 如果需要新的分类或标签，先调用 createCategory/createTag 创建
                - 可以调用 listCategories/listTags 查看已有分类和标签

                ## 工具使用规则
                - saveNews(title, url, summary, publishTimeStr, source, categoryName, tagNamesStr)
                  - url 必须传文章页面 URL，它会从缓存自动获取 htmlContent 和 contentJson
                  - 不要传 htmlContent 或 contentJson 参数
                - 如果分类不存在: createCategory(categoryName) → created:ID
                - 如果标签不存在: createTag(tagName) → created:ID
                - 查看已有分类: listCategories()
                - 查看已有标签: listTags()

                ## 待入库的文章列表
                %s

                逐篇处理，最后输出一个 JSON 数组，每项包含:
                - title: 标题
                - result: "saved:ID" 或 "skipped:已存在" 或 "error:原因"

                只输出 JSON 数组，不要输出其他内容。
                """.formatted(processedJson);
    }

    // ======================== 结果解析 ========================

    private List<Map<String, Object>> extractArticleListFromResult(String result) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (result == null || result.isBlank()) return list;
        try {
            // 尝试从结果中提取 JSON 数组
            String json = extractJsonArray(result);
            if (json != null) {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                list = mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            }
        } catch (Exception e) {
            log.warn("解析 ScraperAgent 结果失败: {}", e.getMessage());
        }
        return list;
    }

    private List<Map<String, Object>> extractProcessedListFromResult(String result) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (result == null || result.isBlank()) return list;
        try {
            String json = extractJsonArray(result);
            if (json != null) {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                list = mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            }
        } catch (Exception e) {
            log.warn("解析 ProcessorAgent 结果失败: {}", e.getMessage());
        }
        return list;
    }

    private void parseSaveResults(WorkflowContext ctx, String result) {
        if (result == null || result.isBlank()) return;
        try {
            String json = extractJsonArray(result);
            if (json != null) {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var items = mapper.readTree(json);
                for (var item : items) {
                    String res = item.path("result").asText("");
                    ctx.getSaveResults().add(res);
                    if (res.startsWith("saved:")) ctx.setTotalSaved(ctx.getTotalSaved() + 1);
                    else if (res.startsWith("skipped:")) ctx.setTotalSkipped(ctx.getTotalSkipped() + 1);
                    else ctx.setTotalFailed(ctx.getTotalFailed() + 1);
                }
            }
        } catch (Exception e) {
            log.warn("解析 WriterAgent 结果失败: {}", e.getMessage());
        }
    }

    /**
     * 从 LLM 输出中提取 JSON 数组（跳过 markdown 代码块标记等）
     */
    private String extractJsonArray(String text) {
        if (text == null) return null;
        // 尝试直接解析
        text = text.strip();
        if (text.startsWith("[")) return text;
        // 尝试从 markdown 代码块中提取
        int start = text.indexOf("[");
        int end = text.lastIndexOf("]");
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    // ======================== 报告生成 ========================

    private String buildFinalReport(WorkflowContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 爬取工作流执行报告\n\n");
        sb.append(ctx.getExecutionSummary()).append("\n\n");

        if (!ctx.getProcessedArticles().isEmpty()) {
            sb.append("### 入库文章\n\n");
            int idx = 1;
            for (Map<String, Object> article : ctx.getProcessedArticles()) {
                String title = (String) article.getOrDefault("title", "未知");
                String time = (String) article.getOrDefault("time", "");
                Object exists = article.get("exists");
                String status = Boolean.TRUE.equals(exists) ? "已存在" : "待保存";
                sb.append(String.format("%d. **%s** (%s) [%s]\n", idx++, title, time, status));
            }
            sb.append("\n");
        }

        if (!ctx.getSaveResults().isEmpty()) {
            sb.append("### 入库结果\n\n");
            for (String r : ctx.getSaveResults()) {
                sb.append("- ").append(r).append("\n");
            }
        }

        return sb.toString();
    }
}
