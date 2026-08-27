package com.financial.news.service.crawler;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多 Agent 工作流共享上下文
 * <p>各 Agent 阶段之间通过此对象传递数据，避免 LLM 在工具间传递大文本。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
public class WorkflowContext {

    /** 原始用户指令 */
    private String userInstruction;

    /** ====== PlannerAgent 输出 ====== */

    /** 爬取计划 JSON（PlannerAgent 生成） */
    private String crawlPlan;

    /** ====== ScraperAgent 输出 ====== */

    /** 抓取到的文章列表 JSON（每项含 title, url, source 等） */
    private List<Map<String, Object>> articleList = new ArrayList<>();

    /** ====== ProcessorAgent 输出 ====== */

    /** 处理后的文章数据（含 title, contentJson, htmlContent, summary, tags 等） */
    private List<Map<String, Object>> processedArticles = new ArrayList<>();

    /** ====== WriterAgent 输出 ====== */

    /** 入库结果（saved:ID 或 duplicate:URL 或 error:...） */
    private List<String> saveResults = new ArrayList<>();

    /** 统计信息 */
    private int totalFetched;
    private int totalSaved;
    private int totalSkipped;
    private int totalFailed;

    /** 各阶段耗时（毫秒） */
    private long plannerTime;
    private long scraperTime;
    private long processorTime;
    private long writerTime;

    /** 执行日志（替代 ai_messages 存储） */
    private final List<String> executionLog = new ArrayList<>();

    public void log(String stage, String message) {
        String entry = String.format("[%s] %s: %s",
                java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")),
                stage, message);
        executionLog.add(entry);
    }

    public String getExecutionSummary() {
        return String.format(
                "爬取完成: 计划=%d篇, 抓取=%d篇, 入库=%d篇, 跳过=%d篇, 失败=%d篇 (耗时: 规划%dms+抓取%dms+处理%dms+入库%dms)",
                totalFetched, articleList.size(), totalSaved, totalSkipped, totalFailed,
                plannerTime, scraperTime, processorTime, writerTime);
    }
}
