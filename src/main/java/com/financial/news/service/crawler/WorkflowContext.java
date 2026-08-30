package com.financial.news.service.crawler;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 多 Agent 工作流共享上下文及执行状态。 */
@Data
public class WorkflowContext {
    private final String runId = "crawl-" + UUID.randomUUID();
    private Integer userId;
    private String userInstruction;
    private String crawlPlan;
    private List<Map<String, Object>> articleList = new ArrayList<>();
    private List<Map<String, Object>> processedArticles = new ArrayList<>();
    private List<String> saveResults = new ArrayList<>();
    private int plannedArticles;
    private int totalFetched;
    private int totalSaved;
    private int totalSkipped;
    private int totalFailed;
    private long plannerTime;
    private long scraperTime;
    private long processorTime;
    private long writerTime;
    private String currentStage;
    private Status status = Status.RUNNING;
    private final List<String> errors = new ArrayList<>();
    private final List<String> executionLog = new ArrayList<>();

    public enum Status { RUNNING, SUCCEEDED, FAILED }

    public void log(String stage, String message) {
        currentStage = stage;
        executionLog.add(String.format("[%s] %s: %s",
                java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")), stage, message));
    }

    public void fail(String stage, String message) {
        status = Status.FAILED;
        currentStage = stage;
        errors.add(stage + ": " + message);
        log(stage, "失败: " + message);
    }

    public String getExecutionSummary() {
        return String.format("爬取%s: 计划=%d篇, 抓取=%d篇, 入库=%d篇, 跳过=%d篇, 失败=%d篇 (耗时: 规划%dms+抓取%dms+处理%dms+入库%dms)",
                status == Status.SUCCEEDED ? "完成" : "失败", plannedArticles, totalFetched,
                totalSaved, totalSkipped, totalFailed, plannerTime, scraperTime, processorTime, writerTime);
    }

    public Map<String, Object> statistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("planned", plannedArticles);
        stats.put("fetched", totalFetched);
        stats.put("saved", totalSaved);
        stats.put("skipped", totalSkipped);
        stats.put("failed", totalFailed);
        return stats;
    }
}
