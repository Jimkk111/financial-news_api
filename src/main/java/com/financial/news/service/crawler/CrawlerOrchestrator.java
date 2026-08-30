package com.financial.news.service.crawler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 协调规划、抓取、处理和写入阶段，并为调用方提供可观察的执行状态。 */
@Slf4j
@Component
public class CrawlerOrchestrator {
    private static final ObjectMapper MAPPER = new ObjectMapper();
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

    public WorkflowContext execute(Integer userId, String instruction, Consumer<WorkflowContext> progress) {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setUserId(userId);
        ctx.setUserInstruction(instruction);
        notify(progress, ctx, "PlannerAgent", "开始制定采集计划");
        try {
            long started = System.currentTimeMillis();
            String plan = plannerAgent.plan(buildPlannerPrompt(instruction));
            ctx.setPlannerTime(System.currentTimeMillis() - started);
            validatePlan(plan, ctx);
            ctx.setCrawlPlan(plan);

            notify(progress, ctx, "ScraperAgent", "开始抓取文章列表");
            started = System.currentTimeMillis();
            List<Map<String, Object>> articles = parseArticleList(scraperAgent.scrape(buildScraperPrompt(plan)), "抓取结果");
            ctx.setScraperTime(System.currentTimeMillis() - started);
            articles = articles.subList(0, Math.min(articles.size(), Math.max(1, maxArticlesPerRun)));
            ctx.setArticleList(articles);
            ctx.setTotalFetched(articles.size());
            if (articles.isEmpty()) throw new BusinessException(ErrorCode.CRAWLER_EXECUTION_FAILED, "未抓取到符合条件的文章");

            notify(progress, ctx, "ProcessorAgent", "开始提取正文与去重");
            started = System.currentTimeMillis();
            List<Map<String, Object>> processed = parseArticleList(
                    processorAgent.process(buildProcessorPrompt(plan, articles)), "文章处理结果");
            ctx.setProcessorTime(System.currentTimeMillis() - started);
            ctx.setProcessedArticles(processed);
            if (processed.isEmpty()) throw new BusinessException(ErrorCode.CRAWLER_EXECUTION_FAILED, "没有可入库的有效文章");

            notify(progress, ctx, "WriterAgent", "开始事务化入库");
            started = System.currentTimeMillis();
            parseSaveResults(ctx, writerAgent.write(buildWriterPrompt(processed)));
            ctx.setWriterTime(System.currentTimeMillis() - started);
            if (ctx.getTotalSaved() == 0 && ctx.getTotalSkipped() == 0) {
                throw new BusinessException(ErrorCode.CRAWLER_EXECUTION_FAILED, "文章入库全部失败");
            }
            ctx.setStatus(WorkflowContext.Status.SUCCEEDED);
            notify(progress, ctx, "Completed", ctx.getExecutionSummary());
            return ctx;
        } catch (BusinessException e) {
            ctx.fail(ctx.getCurrentStage() == null ? "Crawler" : ctx.getCurrentStage(), e.getMessage());
            notify(progress, ctx, "Error", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("爬虫工作流 {} 执行失败", ctx.getRunId(), e);
            ctx.fail(ctx.getCurrentStage() == null ? "Crawler" : ctx.getCurrentStage(), "工作流执行异常");
            notify(progress, ctx, "Error", "工作流执行异常");
            throw new BusinessException(ErrorCode.CRAWLER_EXECUTION_FAILED, "爬虫任务执行失败", e);
        }
    }

    private void notify(Consumer<WorkflowContext> progress, WorkflowContext ctx, String stage, String message) {
        ctx.log(stage, message);
        if (progress != null) progress.accept(ctx);
    }

    private void validatePlan(String plan, WorkflowContext ctx) {
        try {
            String json = extractJsonFromLLMOutput(plan);
            JsonNode node = MAPPER.readTree(json);
            if (!node.isObject() || !node.path("sources").isArray() || node.path("sources").isEmpty()) {
                throw new IllegalArgumentException("计划未包含可用数据源");
            }
            ctx.setPlannedArticles(Math.min(Math.max(1, node.path("maxArticles").asInt(maxArticlesPerRun)), maxArticlesPerRun));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CRAWLER_EXECUTION_FAILED, "PlannerAgent 返回的计划格式无效", e);
        }
    }

    private List<Map<String, Object>> parseArticleList(String payload, String label) {
        try {
            String json = extractJsonArrayFromLLMOutput(payload);
            JsonNode node = MAPPER.readTree(json);
            if (!node.isArray()) throw new IllegalArgumentException("不是 JSON 数组");
            List<Map<String, Object>> result = MAPPER.readValue(node.traverse(MAPPER), new TypeReference<>() {});
            List<Map<String, Object>> valid = new ArrayList<>();
            for (Map<String, Object> article : result) {
                Object title = article.get("title");
                Object url = article.get("url");
                if (title instanceof String t && !t.isBlank() && url instanceof String u && !u.isBlank()) valid.add(article);
            }
            return valid;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CRAWLER_EXECUTION_FAILED, label + "格式无效", e);
        }
    }

    private void parseSaveResults(WorkflowContext ctx, String payload) {
        try {
            String json = extractJsonArrayFromLLMOutput(payload);
            JsonNode results = MAPPER.readTree(json);
            for (JsonNode item : results) {
                String result = item.path("result").asText("error:Agent 未返回结果");
                ctx.getSaveResults().add(result);
                if (result.startsWith("saved:")) ctx.setTotalSaved(ctx.getTotalSaved() + 1);
                else if (result.startsWith("skipped:")) ctx.setTotalSkipped(ctx.getTotalSkipped() + 1);
                else ctx.setTotalFailed(ctx.getTotalFailed() + 1);
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CRAWLER_EXECUTION_FAILED, "WriterAgent 返回的入库结果格式无效", e);
        }
    }

    private String buildPlannerPrompt(String instruction) {
        return """
                你是一个专业的新闻采集规划师。你的任务是分析用户的爬取指令，制定详细的爬取计划。

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
                请严格按以下 JSON 格式输出爬取计划，不要输出任何其他内容，不要用markdown代码块包裹：
                {"sources":[{"name":"数据源名称","key":"数据源key(如eastmoney)","type":"ssr或api","listUrl":"列表页URL","linkSelector":"链接选择器","titleSelector":"标题选择器","timeSelector":"时间选择器","contentSelector":"正文选择器","baseUrl":"用于补全相对链接的基础URL","channel":"频道名(API类型用)"}],"maxArticles":20,"reasoning":"选择这些数据源的原因"}

                ## 用户指令
                %s
                """.formatted(instruction);
    }

    /** 从 LLM 输出中提取 JSON（处理 markdown 代码块、多余文本等） */
    private String extractJsonFromLLMOutput(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("输出为空");
        String stripped = text.strip();
        // 尝试直接解析
        if (stripped.startsWith("{") || stripped.startsWith("[")) return stripped;
        // 从 markdown 代码块中提取
        int codeStart = stripped.indexOf("```");
        if (codeStart >= 0) {
            int jsonStart = stripped.indexOf('{', codeStart);
            int jsonEnd = stripped.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) return stripped.substring(jsonStart, jsonEnd + 1);
        }
        // 通用提取：找第一个 { 和最后一个 }
        int start = stripped.indexOf('{');
        int end = stripped.lastIndexOf('}');
        if (start >= 0 && end > start) return stripped.substring(start, end + 1);
        throw new IllegalArgumentException("未找到 JSON 对象");
    }

    /** 从 LLM 输出中提取 JSON 数组（处理 markdown 代码块、多余文本等） */
    private String extractJsonArrayFromLLMOutput(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("输出为空");
        String stripped = text.strip();
        if (stripped.startsWith("[")) return stripped;
        int start = stripped.indexOf('[');
        int end = stripped.lastIndexOf(']');
        if (start >= 0 && end > start) return stripped.substring(start, end + 1);
        throw new IllegalArgumentException("未找到 JSON 数组");
    }
    private String buildScraperPrompt(String plan) {
        return """
                你是一个专业的网页爬取执行者。你的任务是根据爬取计划，调用工具抓取页面并提取文章列表。

                ## 工具使用规则
                - **SSR 类型**: 先调用 fetchPage(url=列表页URL)，再调用 extractArticleList(url=同一URL, linkSelector, titleSelector, timeSelector, baseUrl)
                - **API 类型(华尔街见闻)**: 直接调用 fetchWallstreetArticles(channel, limit)
                - 不要把 HTML 字符串传给工具，必须传 URL

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

                只输出 JSON 数组，不要输出其他内容，不要用markdown代码块包裹。
                """.formatted(plan);
    }

    private String buildProcessorPrompt(String plan, List<Map<String, Object>> articles) {
        return """
                你是一个专业的文章内容处理者。你的任务是逐篇获取文章详情，提取完整正文，并检查是否已存在。

                ## 工具使用规则（SSR文章）
                1. fetchPage(url=文章URL)
                2. parseArticleContent(url=同一URL, contentSelector, titleSelector, timeSelector)
                3. checkNewsExists(title=标题)

                ## 工具使用规则（API文章 - 华尔街见闻）
                1. fetchWallstreetArticleDetail(articleId=文章ID)
                2. checkNewsExists(title=标题)

                ## 爬取计划
                %s

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

                只输出 JSON 数组，不要输出其他内容，不要用markdown代码块包裹。
                """.formatted(plan, toJson(articles));
    }

    private String buildWriterPrompt(List<Map<String, Object>> articles) {
        return """
                你是一个专业的新闻入库操作者。你的任务是将处理后的文章数据保存到数据库。

                ## 工具使用规则
                - 对 exists=true 的文章，直接输出 skipped:已存在
                - 对不存在的文章，调用 saveNews(title, url, summary, publishTimeStr, source, categoryName, tagNamesStr) 入库
                - 如果分类/标签不存在，先调用 createCategory/createTag 创建

                ## 待入库的文章列表
                %s

                逐篇处理，最后输出一个 JSON 数组，每项包含:
                - title: 标题
                - result: "saved:ID" 或 "skipped:已存在" 或 "error:原因"

                只输出 JSON 数组，不要输出其他内容，不要用markdown代码块包裹。
                """.formatted(toJson(articles));
    }
    private String toJson(Object value) { try { return MAPPER.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
}
