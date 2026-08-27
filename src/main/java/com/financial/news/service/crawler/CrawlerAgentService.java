package com.financial.news.service.crawler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 爬虫 Agent 服务
 * <p>封装多 Agent 工作流的调用流程，提供统一的爬取入口。</p>
 *
 * <h3>工作流概览</h3>
 * <pre>
 *   用户输入 "帮我爬取东方财富的新闻"
 *          ↓
 *   CrawlerOrchestrator 编排多 Agent 协同工作
 *     ├─ PlannerAgent: 分析指令 → 制定爬取计划
 *     ├─ ScraperAgent: 按计划抓取页面 → 提取文章列表
 *     ├─ ProcessorAgent: 逐篇解析正文 → 结构化内容
 *     └─ WriterAgent: 清洗分类 → 入库保存
 * </pre>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrawlerAgentService {

    private final CrawlerOrchestrator orchestrator;

    /**
     * 执行爬取任务（同步）
     *
     * @param userId      用户 ID
     * @param instruction 自然语言爬取指令
     * @param sessionId   会话 ID（此版本不使用，保留接口兼容）
     * @return 执行结果，包含 Agent 回复
     */
    public Map<String, Object> execute(Integer userId, String instruction, String sessionId) {
        log.info("========== 爬取任务提交 ==========");
        log.info("userId={}, instruction={}", userId, instruction);

        // 执行多 Agent 工作流
        String agentResponse;
        try {
            agentResponse = orchestrator.execute(instruction);
        } catch (Exception e) {
            log.error("爬取工作流执行异常", e);
            agentResponse = "爬取任务执行失败: " + e.getMessage();
        }

        log.info("爬取任务完成: {}", agentResponse);

        // 返回结果（不再保存到 ai_messages，工作流日志由 orchestrator 通过 log 记录）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", agentResponse);
        result.put("sessionId", sessionId);
        return result;
    }

    /**
     * 流式执行爬取任务（当前为同步执行后一次性返回）
     */
    public Map<String, Object> executeStream(Integer userId, String instruction, String sessionId) {
        return execute(userId, instruction, sessionId);
    }
}
