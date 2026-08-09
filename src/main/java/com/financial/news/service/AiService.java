package com.financial.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.dto.request.AiChatRequest;
import com.financial.news.entity.AiMessage;
import com.financial.news.entity.AiSession;
import com.financial.news.mapper.AiMessageMapper;
import com.financial.news.mapper.AiSessionMapper;
import com.financial.news.utils.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * AI 服务
 * <p>提供 AI 会话管理、消息查询和 AI 对话功能（支持流式 SSE）</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService extends ServiceImpl<AiSessionMapper, AiSession> {

    private final AiSessionMapper aiSessionMapper;
    private final AiMessageMapper aiMessageMapper;

    @Value("${ai.api-key:}") private String apiKey;
    @Value("${ai.api-base-url:https://api.openai.com/v1}") private String apiBaseUrl;
    @Value("${ai.model:gpt-3.5-turbo}") private String model;
    @Value("${ai.max-tokens:2000}") private int maxTokens;
    @Value("${ai.temperature:0.7}") private double temperature;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** 获取用户的 AI 会话列表 */
    public List<Map<String, Object>> listSessions(Integer userId) {
        List<AiSession> sessions = aiSessionMapper.selectList(
                new LambdaQueryWrapper<AiSession>().eq(AiSession::getUserId, userId).orderByDesc(AiSession::getUpdatedAt));
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiSession s : sessions) {
            AiMessage lastMsg = aiMessageMapper.selectOne(
                    new LambdaQueryWrapper<AiMessage>().eq(AiMessage::getSessionId, s.getId()).orderByDesc(AiMessage::getCreatedAt).last("LIMIT 1"));
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sessionId", s.getSessionId());
            map.put("title", s.getTitle());
            map.put("createdAt", s.getCreatedAt());
            map.put("updatedAt", s.getUpdatedAt());
            map.put("lastMessage", lastMsg != null && lastMsg.getContent().length() > 100
                    ? lastMsg.getContent().substring(0, 100) : (lastMsg != null ? lastMsg.getContent() : null));
            result.add(map);
        }
        return result;
    }

    /** 创建新会话 */
    public String createSession(Integer userId) {
        String sessionId = IdGenerator.generateSessionId();
        aiSessionMapper.insert(AiSession.builder().sessionId(sessionId).userId(userId).build());
        return sessionId;
    }

    /** 获取会话的消息列表 */
    public List<AiMessage> getMessages(String sessionId, Integer userId) {
        AiSession session = findSession(sessionId, userId);
        return aiMessageMapper.selectList(
                new LambdaQueryWrapper<AiMessage>().eq(AiMessage::getSessionId, session.getId()).orderByAsc(AiMessage::getCreatedAt));
    }

    /** 更新会话标题 */
    public void updateSessionTitle(String sessionId, Integer userId, String title) {
        AiSession session = findSession(sessionId, userId);
        session.setTitle(title.length() > 30 ? title.substring(0, 30) : title);
        aiSessionMapper.updateById(session);
    }

    /** 删除会话（级联删除消息） */
    @Transactional
    public void deleteSession(String sessionId, Integer userId) {
        AiSession session = findSession(sessionId, userId);
        aiMessageMapper.delete(new LambdaQueryWrapper<AiMessage>().eq(AiMessage::getSessionId, session.getId()));
        aiSessionMapper.deleteById(session.getId());
    }

    /** AI 对话（非流式） */
    @Transactional
    public Map<String, Object> chat(Integer userId, AiChatRequest request) {
        AiSession session = resolveSession(userId, request.getSessionId());
        // 保存用户消息
        List<AiChatRequest.ChatMessage> msgs = request.getMessages();
        List<AiChatRequest.ChatMessage> sendMsgs = msgs.size() > 20
                ? msgs.subList(msgs.size() - 20, msgs.size()) : msgs;

        for (AiChatRequest.ChatMessage m : sendMsgs) {
            aiMessageMapper.insert(AiMessage.builder().sessionId(session.getId()).role(m.getRole()).content(m.getContent()).build());
        }

        // 调用 AI API
        String aiResponse = callAiApi(sendMsgs);
        aiMessageMapper.insert(AiMessage.builder().sessionId(session.getId()).role("assistant").content(aiResponse).build());

        // 首次对话自动生成标题
        if (session.getTitle() == null) {
            session.setTitle(aiResponse.length() > 30 ? aiResponse.substring(0, 30) : aiResponse);
            aiSessionMapper.updateById(session);
        } else {
            session.setUpdatedAt(java.time.LocalDateTime.now());
            aiSessionMapper.updateById(session);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", aiResponse);
        result.put("sessionId", session.getSessionId());
        return result;
    }

    /** AI 对话（流式 SSE） */
    public SseEmitter chatStream(Integer userId, AiChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时
        CompletableFuture.runAsync(() -> {
            try {
                AiSession session = resolveSession(userId, request.getSessionId());
                List<AiChatRequest.ChatMessage> msgs = request.getMessages();
                List<AiChatRequest.ChatMessage> sendMsgs = msgs.size() > 20
                        ? msgs.subList(msgs.size() - 20, msgs.size()) : msgs;

                // 保存用户消息
                for (AiChatRequest.ChatMessage m : sendMsgs) {
                    aiMessageMapper.insert(AiMessage.builder().sessionId(session.getId()).role(m.getRole()).content(m.getContent()).build());
                }

                // 调用 AI API (流式)
                StringBuilder fullResponse = new StringBuilder();
                callAiApiStream(sendMsgs, chunk -> {
                    try {
                        emitter.send(SseEmitter.event().data(Map.of("content", chunk)));
                    } catch (IOException e) {
                        log.error("SSE 发送失败", e);
                    }
                    fullResponse.append(chunk);
                });

                // 保存 AI 回复
                aiMessageMapper.insert(AiMessage.builder().sessionId(session.getId()).role("assistant").content(fullResponse.toString()).build());
                emitter.send(SseEmitter.event().data(Map.of("sessionId", session.getSessionId())));
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                log.error("AI 流式对话异常", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private AiSession resolveSession(Integer userId, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            AiSession s = aiSessionMapper.selectOne(new LambdaQueryWrapper<AiSession>().eq(AiSession::getSessionId, sessionId));
            if (s != null && s.getUserId().equals(userId)) return s;
        }
        String newId = IdGenerator.generateSessionId();
        AiSession s = AiSession.builder().sessionId(newId).userId(userId).build();
        aiSessionMapper.insert(s);
        return s;
    }

    private AiSession findSession(String sessionId, Integer userId) {
        AiSession s = aiSessionMapper.selectOne(new LambdaQueryWrapper<AiSession>().eq(AiSession::getSessionId, sessionId));
        if (s == null) throw new BusinessException(ErrorCode.AI_SESSION_NOT_FOUND);
        if (!s.getUserId().equals(userId)) throw new BusinessException(ErrorCode.AI_SESSION_NOT_OWNER);
        return s;
    }

    private String callAiApi(List<AiChatRequest.ChatMessage> messages) {
        try {
            List<Map<String, String>> msgs = messages.stream()
                    .map(m -> Map.of("role", m.getRole(), "content", m.getContent())).toList();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", msgs);
            body.put("max_tokens", maxTokens);
            body.put("temperature", temperature);

            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = new com.fasterxml.jackson.databind.ObjectMapper().readValue(resp.body(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
            Map<String, Object> choice = choices.get(0);
            Map<String, String> message = (Map<String, String>) choice.get("message");
            return message.get("content");
        } catch (Exception e) {
            log.error("AI API 调用失败", e);
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "AI 服务调用失败: " + e.getMessage());
        }
    }

    private void callAiApiStream(List<AiChatRequest.ChatMessage> messages, java.util.function.Consumer<String> chunkConsumer) {
        // 流式实现简化版：使用非流式结果模拟流式输出
        String fullResponse = callAiApi(messages);
        for (int i = 0; i < fullResponse.length(); i++) {
            chunkConsumer.accept(String.valueOf(fullResponse.charAt(i)));
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }
}
