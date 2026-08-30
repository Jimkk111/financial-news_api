package com.financial.news.service.crawler;

import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** 爬虫工作流服务，统一处理同步和 SSE 执行。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrawlerAgentService {
    private final ObjectProvider<CrawlerOrchestrator> orchestratorProvider;

    public Map<String, Object> execute(Integer userId, String instruction, String sessionId) {
        WorkflowContext context = run(userId, instruction);
        return result(context, sessionId);
    }

    public SseEmitter executeStream(Integer userId, String instruction, String sessionId) {
        SseEmitter emitter = new SseEmitter(300_000L);
        CompletableFuture.runAsync(() -> {
            try {
                WorkflowContext context = run(userId, instruction, progress -> send(emitter, progress));
                sendEvent(emitter, "completed", result(context, sessionId));
                sendEvent(emitter, "done", "[DONE]");
                emitter.complete();
            } catch (Exception e) {
                log.error("爬虫 SSE 工作流执行失败", e);
                sendEvent(emitter, "error", Map.of("code", ErrorCode.CRAWLER_EXECUTION_FAILED.getCode(), "message", safeMessage(e)));
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private WorkflowContext run(Integer userId, String instruction) {
        return run(userId, instruction, null);
    }

    private WorkflowContext run(Integer userId, String instruction, java.util.function.Consumer<WorkflowContext> progress) {
        CrawlerOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
        if (orchestrator == null) {
            throw new BusinessException(ErrorCode.CRAWLER_NOT_CONFIGURED);
        }
        return orchestrator.execute(userId, instruction, progress);
    }

    private Map<String, Object> result(WorkflowContext context, String sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", context.getExecutionSummary());
        result.put("sessionId", sessionId);
        result.put("runId", context.getRunId());
        result.put("status", context.getStatus().name().toLowerCase());
        result.put("success", context.getStatus() == WorkflowContext.Status.SUCCEEDED);
        result.put("statistics", context.statistics());
        if (!context.getErrors().isEmpty()) result.put("errors", context.getErrors());
        return result;
    }

    private void send(SseEmitter emitter, WorkflowContext context) {
        sendEvent(emitter, "progress", Map.of(
                "runId", context.getRunId(),
                "stage", context.getCurrentStage() == null ? "Crawler" : context.getCurrentStage(),
                "message", context.getExecutionLog().isEmpty() ? "" : context.getExecutionLog().get(context.getExecutionLog().size() - 1),
                "statistics", context.statistics()));
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException e) {
            throw new IllegalStateException("SSE 客户端已断开", e);
        }
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "爬虫任务执行失败" : e.getMessage();
    }
}
