package com.financial.news.service;

import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.dto.request.AiChatRequest;
import com.financial.news.entity.AiMessage;
import com.financial.news.entity.AiSession;
import com.financial.news.mapper.AiMessageMapper;
import com.financial.news.mapper.AiSessionMapper;
import com.financial.news.service.ai.OpenAiCompatibleClient;
import com.financial.news.service.ai.OpenAiCompatibleClient.ChatParam;
import com.financial.news.service.ai.OpenAiCompatibleClient.ChatResult;
import com.financial.news.service.ai.OpenAiCompatibleClient.Source;
import com.financial.news.service.ai.OpenAiCompatibleClient.StreamCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.news.utils.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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

    /** sources 落库序列化（ai_messages.sources 存 JSON 数组字符串，前端历史回显直接可用） */
    private static final ObjectMapper SOURCES_CODEC = new ObjectMapper();

    /** SSE 流式推送专用线程池，避免占用公共 ForkJoinPool 和请求线程 */
    private final java.util.concurrent.ExecutorService sseExecutor =
            new java.util.concurrent.ThreadPoolExecutor(
                    2, 8, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(64),
                    r -> {
                        Thread t = new Thread(r, "ai-sse-" + r.hashCode());
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    /** 心跳调度器：思考期间若无任何 token 到达，定期发 SSE 注释行防止链路被中间层/前端空闲超时掐断 */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ai-sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /** 心跳间隔：小于前端 30s 空闲超时，留足余量 */
    private static final int HEARTBEAT_INTERVAL_SECONDS = 15;

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

    /**
     * AI 对话（流式 SSE）
     * <p>事件契约（均为 {@code data:} JSON）：</p>
     * <ul>
     *   <li>{@code {"sessionId":"..."}} —— 请求受理即发送（提交响应头，前端可立即感知）</li>
     *   <li>{@code {"sources":[...]}} —— 联网搜索引用来源，随上游首包一次性到达（可有多批）</li>
     *   <li>{@code {"reasoning":"增量"}} —— 思考链增量，正文开始前持续到达</li>
     *   <li>{@code {"content":"增量"}} —— 正文增量</li>
     *   <li>{@code {"error":"..."}} —— 上游返回了内容但正文为空等业务失败，随后仍会发 [DONE]</li>
     *   <li>{@code [DONE]} —— 正常结束</li>
     * </ul>
     * <p>思考期等静默阶段每 15s 发一条 SSE 注释行（: keep-alive）保活。</p>
     */
    public SseEmitter chatStream(Integer userId, AiChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时
        CompletableFuture.runAsync(() -> {
            ScheduledFuture<?> heartbeat = null;
            long startMs = System.currentTimeMillis();
            java.util.concurrent.atomic.AtomicLong firstEventMs = new java.util.concurrent.atomic.AtomicLong(0);
            Runnable markFirstEvent = () -> firstEventMs.compareAndSet(0, System.currentTimeMillis());
            // 声明在 try 外：客户端断开时 catch 里也要统计已收正文量
            StringBuilder content = new StringBuilder();
            try {
                AiSession session = resolveSession(userId, request.getSessionId());
                // 早冲刷：受理即回传 sessionId 提交响应头，避免长时间 pending 且前端空闲计时从此时起算
                sendEvent(emitter, Map.of("sessionId", session.getSessionId()));

                // 保存本轮用户消息。请求中的其余消息仅作为 AI 上下文，避免历史消息重复入库。
                List<AiChatRequest.ChatMessage> sendMsgs = limitContextMessages(request.getMessages());
                saveCurrentUserMessage(session, sendMsgs);
                boolean webSearch = Boolean.TRUE.equals(request.getWebSearch());
                log.info("AI 流式对话开始: session={}, webSearch={}", session.getSessionId(), webSearch);

                // 心跳兜底：用 data 事件而非 SSE 注释行，防中间层（网关/CDN）吞注释导致前端空闲超时
                heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
                    try {
                        emitter.send(SseEmitter.event().data(Map.of("heartbeat", true)));
                    } catch (Exception e) {
                        log.debug("SSE 心跳发送失败（客户端可能已断开）: {}", e.getMessage());
                    }
                }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

                StringBuilder reasoning = new StringBuilder();
                List<Source> sources = new ArrayList<>();
                aiClient.stream(toParams(sendMsgs, webSearch), new StreamCallback() {
                    @Override
                    public void onReasoning(String delta) {
                        markFirstEvent.run();
                        reasoning.append(delta);
                        sendEvent(emitter, Map.of("reasoning", delta));
                    }

                    @Override
                    public void onContent(String delta) {
                        markFirstEvent.run();
                        content.append(delta);
                        sendEvent(emitter, Map.of("content", delta));
                    }

                    @Override
                    public void onSources(List<Source> chunkSources) {
                        markFirstEvent.run();
                        sources.addAll(chunkSources);
                        sendEvent(emitter, Map.of("sources", chunkSources));
                    }
                }, webSearch);

                if (content.toString().isBlank()) {
                    // 思考型模型可能耗尽 max_tokens 只剩思考链：以流内 error 事件告知前端，连接正常收尾
                    sendEvent(emitter, Map.of("error", "AI 响应内容为空"));
                } else {
                    aiMessageMapper.insert(AiMessage.builder()
                            .sessionId(session.getId()).role("assistant")
                            .content(content.toString()).reasoningContent(reasoning.toString())
                            .sources(toSourcesJson(sources))
                            .build());
                    touchSession(session, content.toString());
                }
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
                log.info("AI 流式对话完成: session={}, webSearch={}, 首 token {}ms, 正文 {} 字, 思考链 {} 字, 来源 {} 条, 总耗时 {}ms",
                        session.getSessionId(), webSearch,
                        firstEventMs.get() == 0 ? -1 : firstEventMs.get() - startMs,
                        content.length(), reasoning.length(), sources.size(),
                        System.currentTimeMillis() - startMs);
            } catch (SseClientDisconnectedException e) {
                // 设计内行为（用户停止生成/离开页面）：上游已随之中止，不按错误处理，
                // 也不 completeWithError——对已断连接派发 ERROR 只会产生容器噪音日志
                log.info("AI 流式对话客户端断开, 已中止上游生成: 首 token {}ms, 已收正文 {} 字, 总耗时 {}ms",
                        firstEventMs.get() == 0 ? -1 : firstEventMs.get() - startMs,
                        content.length(), System.currentTimeMillis() - startMs);
                try {
                    emitter.complete();
                } catch (Exception ignore) {
                    // 连接已死，忽略
                }
            } catch (Exception e) {
                log.error("AI 流式对话异常: 首 token {}ms, 总耗时 {}ms",
                        firstEventMs.get() == 0 ? -1 : firstEventMs.get() - startMs,
                        System.currentTimeMillis() - startMs, e);
                emitter.completeWithError(e);
            } finally {
                if (heartbeat != null) {
                    heartbeat.cancel(false);
                }
            }
        }, sseExecutor);
        return emitter;
    }

    /** 客户端断开标记：sendEvent 失败时抛出，用于与上游真实异常区分（设计内行为，不按 ERROR 处理） */
    private static class SseClientDisconnectedException extends RuntimeException {
        SseClientDisconnectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 发送 SSE data 事件；客户端断开后的 send 失败以运行时异常抛出，
     * 经流式 callback 传播中止上游生成（停止计费），由外层统一处理。
     */
    private void sendEvent(SseEmitter emitter, Object payload) {
        try {
            emitter.send(SseEmitter.event().data(payload));
        } catch (IOException | IllegalStateException e) {
            throw new SseClientDisconnectedException("SSE 客户端已断开: " + e.getMessage(), e);
        }
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
