package com.financial.news.service;

import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.dto.request.AiChatRequest;
import com.financial.news.entity.AiMessage;
import com.financial.news.entity.AiSession;
import com.financial.news.mapper.AiMessageMapper;
import com.financial.news.mapper.AiSessionMapper;
import com.financial.news.service.ai.OpenAiCompatibleClient;
import com.financial.news.service.ai.AiGenerationService;
import com.financial.news.service.ai.OpenAiCompatibleClient.ChatParam;
import com.financial.news.service.ai.OpenAiCompatibleClient.ChatResult;
import com.financial.news.service.ai.OpenAiCompatibleClient.Source;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.news.utils.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

/**
 * AI 服务
 * <p>提供 AI 会话管理、消息查询和 AI 对话功能（支持流式 SSE 与思考链透传）</p>
 *
 * <p>对话链路通过 {@link OpenAiCompatibleClient} 直连 OpenAI 兼容接口：
 * langchain4j/openai4j 不映射思考型模型的 reasoning_content 字段会静默丢弃思考链，
 * 导致模型思考期间前端长时间无响应。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final AiSessionMapper aiSessionMapper;
    private final AiMessageMapper aiMessageMapper;
    private final OpenAiCompatibleClient aiClient;
    private final AiGenerationService generations;

    /** sources 落库序列化（ai_messages.sources 存 JSON 数组字符串，前端历史回显直接可用） */
    private static final ObjectMapper SOURCES_CODEC = new ObjectMapper();

    /** 获取用户的 AI 会话列表（批量取各会话最后一条消息，避免 N+1） */
    public List<Map<String, Object>> listSessions(Integer userId) {
        List<AiSession> sessions = aiSessionMapper.selectByUserId(userId);
        if (sessions.isEmpty()) {
            return List.of();
        }

        Map<Integer, AiMessage> lastMsgBySession = new HashMap<>();
        List<Integer> sessionIds = sessions.stream().map(AiSession::getId).toList();
        for (AiMessage m : aiMessageMapper.selectLastMessages(sessionIds)) {
            lastMsgBySession.put(m.getSessionId(), m);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (AiSession s : sessions) {
            AiMessage lastMsg = lastMsgBySession.get(s.getId());
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
        return aiMessageMapper.selectBySessionId(session.getId());
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
        generations.guardSessionDeletion(session.getId());
        aiMessageMapper.deleteBySessionId(session.getId());
        aiSessionMapper.deleteById(session.getId());
    }

    /**
     * AI 对话（非流式）
     * <p>AI 远程调用不放在事务内：调用期间不占用数据库连接；
     * 用户消息先落库，AI 失败时回复不入库并抛 503（前端可重试）</p>
     */
    public Map<String, Object> chat(Integer userId, AiChatRequest request) {
        AiSession session = resolveSession(userId, request.getSessionId());
        // 保存本轮用户消息。请求中的其余消息仅作为 AI 上下文，避免历史消息重复入库。
        List<AiChatRequest.ChatMessage> sendMsgs = limitContextMessages(request.getMessages());
        saveCurrentUserMessage(session, sendMsgs);

        boolean webSearch = Boolean.TRUE.equals(request.getWebSearch());
        // 调用 AI API（事务外）
        ChatResult aiResult = callAi(toParams(sendMsgs, webSearch), webSearch);

        aiMessageMapper.insert(AiMessage.builder()
                .sessionId(session.getId()).role("assistant")
                .content(aiResult.content()).reasoningContent(aiResult.reasoning())
                .sources(toSourcesJson(aiResult.sources()))
                .build());
        touchSession(session, aiResult.content());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", aiResult.content());
        result.put("sessionId", session.getSessionId());
        if (aiResult.reasoning() != null && !aiResult.reasoning().isBlank()) {
            result.put("reasoning", aiResult.reasoning());
        }
        if (!aiResult.sources().isEmpty()) {
            result.put("sources", aiResult.sources());
        }
        return result;
    }

    /** Legacy SSE payloads are retained; disconnecting now only removes the subscription. */
    public SseEmitter chatStream(Integer userId, AiChatRequest request) {
        return chatStream(userId, request, null);
    }

    public SseEmitter chatStream(Integer userId, AiChatRequest request, String idempotencyKey) {
        return generations.legacyStream(userId, request, idempotencyKey);
    }

    /** 首轮对话用回复生成标题，后续轮次刷新更新时间 */
    private void touchSession(AiSession session, String aiResponse) {
        if (session.getTitle() == null) {
            session.setTitle(aiResponse.length() > 30 ? aiResponse.substring(0, 30) : aiResponse);
        } else {
            session.setUpdatedAt(java.time.LocalDateTime.now());
        }
        aiSessionMapper.updateById(session);
    }

    /**
     * 项目消息 → 直连客户端入参（role 归一小写）。
     * 联网搜索时在首位注入系统提示：当前日期（搜索时效定向）+ 引用编号要求。
     */
    private List<ChatParam> toParams(List<AiChatRequest.ChatMessage> messages, boolean webSearch) {
        List<ChatParam> params = new ArrayList<>();
        if (webSearch) {
            String today = java.time.LocalDate.now().toString();
            params.add(new ChatParam("system",
                    "今天是 " + today + "。你是财经资讯助手，回答基于联网搜索结果，"
                            + "在引用信息处标注来源编号（如[1][2]），不要编造来源。"));
        }
        for (AiChatRequest.ChatMessage m : messages) {
            String role = m.getRole() == null ? "user" : m.getRole().toLowerCase();
            params.add(new ChatParam(role, m.getContent()));
        }
        return params;
    }

    /**
     * 调用 AI（非流式）。上游异常统一映射为 AI_SERVICE_UNAVAILABLE，不透出内部实现细节。
     */
    private ChatResult callAi(List<ChatParam> params, boolean webSearch) {
        ChatResult result;
        try {
            result = aiClient.chat(params, webSearch);
        } catch (Exception e) {
            log.error("AI API 调用失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
        if (result.content() == null || result.content().isBlank()) {
            log.error("AI API 响应内容为空");
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
        return result;
    }

    /** sources 落库为 JSON 数组字符串；空列表存 null（列可空，历史数据不受影响） */
    private String toSourcesJson(List<Source> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        try {
            return SOURCES_CODEC.writeValueAsString(sources);
        } catch (Exception e) {
            log.warn("sources 序列化失败，按空存储: {}", e.getMessage());
            return null;
        }
    }

    private List<AiChatRequest.ChatMessage> limitContextMessages(List<AiChatRequest.ChatMessage> messages) {
        return messages.size() > 20 ? messages.subList(messages.size() - 20, messages.size()) : messages;
    }

    private void saveCurrentUserMessage(AiSession session, List<AiChatRequest.ChatMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        AiChatRequest.ChatMessage currentMessage = messages.get(messages.size() - 1);
        if ("user".equalsIgnoreCase(currentMessage.getRole())) {
            aiMessageMapper.insert(AiMessage.builder()
                    .sessionId(session.getId())
                    .role(currentMessage.getRole())
                    .content(currentMessage.getContent())
                    .build());
        }
    }

    private AiSession resolveSession(Integer userId, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            AiSession s = aiSessionMapper.selectBySessionId(sessionId);
            if (s != null && s.getUserId().equals(userId)) return s;
        }
        String newId = IdGenerator.generateSessionId();
        AiSession s = AiSession.builder().sessionId(newId).userId(userId).build();
        aiSessionMapper.insert(s);
        return s;
    }

    private AiSession findSession(String sessionId, Integer userId) {
        AiSession s = aiSessionMapper.selectBySessionId(sessionId);
        if (s == null) throw new BusinessException(ErrorCode.AI_SESSION_NOT_FOUND);
        if (!s.getUserId().equals(userId)) throw new BusinessException(ErrorCode.AI_SESSION_NOT_OWNER);
        return s;
    }
}
