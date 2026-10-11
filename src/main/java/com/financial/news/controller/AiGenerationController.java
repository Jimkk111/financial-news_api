package com.financial.news.controller;

import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.common.Result;
import com.financial.news.dto.request.AiChatRequest;
import com.financial.news.security.JwtUserDetails;
import com.financial.news.service.ai.AiGenerationService;
import com.financial.news.service.ai.AiGenerationStore.Snapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/ai/generations")
@RequiredArgsConstructor
@Tag(name = "AI 生成任务")
@SecurityRequirement(name = "BearerAuth")
public class AiGenerationController {
    private final AiGenerationService generations;

    @PostMapping
    @Operation(summary = "创建持久生成任务，同一幂等键返回同一任务")
    public Result<Map<String, Object>> create(@Valid @RequestBody AiChatRequest request,
                                             @RequestHeader("Idempotency-Key") String key) {
        var task = generations.create(userId(), request, key);
        return Result.ok(Map.of("generationId", task.id(), "sessionId", task.publicSessionId(),
                "messageId", task.messageId(), "status", task.status(), "lastSeq", task.lastSeq()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "读取一致的生成内容快照")
    public Result<Snapshot> snapshot(@PathVariable String id) { return Result.ok(generations.snapshot(userId(), id)); }

    @PostMapping("/{id}/retry")
    @Operation(summary = "重试最后一轮回复，复用原上下文，不新增用户消息")
    public Result<Map<String, Object>> retry(@PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
        var task = generations.retry(userId(), id, key);
        return Result.ok(Map.of("generationId", task.id(), "sessionId", task.publicSessionId(),
                "messageId", task.messageId(), "status", task.status(), "lastSeq", task.lastSeq(), "retryOf", id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "主动停止生成，保留已保存内容")
    public Result<Snapshot> cancel(@PathVariable String id) { return Result.ok(generations.cancel(userId(), id)); }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅并补发生成事件，支持 Last-Event-ID")
    public SseEmitter events(@PathVariable String id, @RequestParam(defaultValue = "0") long afterSeq,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             HttpServletResponse response) {
        // On native EventSource reconnect the header takes precedence over the original URL cursor.
        long cursor = afterSeq;
        if (lastEventId != null && !lastEventId.isBlank()) {
            try { cursor = Long.parseLong(lastEventId); }
            catch (NumberFormatException e) { throw new BusinessException(ErrorCode.BAD_REQUEST); }
        }
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return generations.subscribe(userId(), id, cursor, false);
    }
    private int userId() { return JwtUserDetails.getCurrentUser().getUserId(); }
}
