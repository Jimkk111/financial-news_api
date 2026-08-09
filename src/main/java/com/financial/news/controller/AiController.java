package com.financial.news.controller;

import com.financial.news.common.Result;
import com.financial.news.dto.request.AiChatRequest;
import com.financial.news.dto.request.AiSessionUpdateRequest;
import com.financial.news.entity.AiMessage;
import com.financial.news.security.JwtUserDetails;
import com.financial.news.service.AiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * AI 控制器
 */
@Tag(name = "AI 模块", description = "AI 会话管理、消息查询、对话")
@RestController @RequestMapping("/api/ai") @RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    @Operation(summary = "健康检查")
    @GetMapping("/health")
    public Result<Map<String,String>> health() {
        return Result.ok(Map.of("status","healthy"));
    }

    @Operation(summary = "获取会话列表") @GetMapping("/sessions") @SecurityRequirement(name = "BearerAuth")
    public Result<List<Map<String,Object>>> sessions() {
        return Result.ok(aiService.listSessions(JwtUserDetails.getCurrentUser().getUserId()));
    }

    @Operation(summary = "创建新会话") @PostMapping("/sessions") @SecurityRequirement(name = "BearerAuth")
    public Result<Map<String,String>> createSession() {
        String id = aiService.createSession(JwtUserDetails.getCurrentUser().getUserId());
        return Result.ok(Map.of("session_id", id));
    }

    @Operation(summary = "获取会话消息列表") @GetMapping("/sessions/{sessionId}/messages") @SecurityRequirement(name = "BearerAuth")
    public Result<List<AiMessage>> messages(@PathVariable String sessionId) {
        return Result.ok(aiService.getMessages(sessionId, JwtUserDetails.getCurrentUser().getUserId()));
    }

    @Operation(summary = "更新会话标题") @PutMapping("/sessions/{sessionId}") @SecurityRequirement(name = "BearerAuth")
    public Result<Void> updateTitle(@PathVariable String sessionId, @Valid @RequestBody AiSessionUpdateRequest req) {
        aiService.updateSessionTitle(sessionId, JwtUserDetails.getCurrentUser().getUserId(), req.getTitle());
        return Result.okMsg("更新成功");
    }

    @Operation(summary = "删除会话") @DeleteMapping("/sessions/{sessionId}") @SecurityRequirement(name = "BearerAuth")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        aiService.deleteSession(sessionId, JwtUserDetails.getCurrentUser().getUserId());
        return Result.okMsg("删除成功");
    }

    @Operation(summary = "AI 对话") @PostMapping("/chat") @SecurityRequirement(name = "BearerAuth")
    public Result<Map<String,Object>> chat(@Valid @RequestBody AiChatRequest request) {
        JwtUserDetails u = JwtUserDetails.getCurrentUser();
        if (Boolean.TRUE.equals(request.getStream())) {
            throw new IllegalArgumentException("流式请使用 SSE 端点: POST /api/ai/chat/stream");
        }
        return Result.ok(aiService.chat(u.getUserId(), request));
    }

    @Operation(summary = "AI 对话（流式 SSE）")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE) @SecurityRequirement(name = "BearerAuth")
    public SseEmitter chatStream(@Valid @RequestBody AiChatRequest request) {
        return aiService.chatStream(JwtUserDetails.getCurrentUser().getUserId(), request);
    }
}
