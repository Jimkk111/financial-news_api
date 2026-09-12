package com.financial.news.service;

import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.dto.request.AiChatRequest;
import com.financial.news.entity.AiMessage;
import com.financial.news.entity.AiSession;
import com.financial.news.mapper.AiMessageMapper;
import com.financial.news.mapper.AiSessionMapper;
import com.financial.news.utils.IdGenerator;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
public class AiService {

    private final AiSessionMapper aiSessionMapper;
    private final AiMessageMapper aiMessageMapper;

    @Value("${ai.api-key:}") private String apiKey;
    @Value("${ai.api-base-url:https://api.openai.com/v1}") private String apiBaseUrl;
    @Value("${ai.model:gpt-3.5-turbo}") private String model;
    @Value("${ai.max-tokens:2000}") private int maxTokens;
    @Value("${ai.temperature:0.7}") private double temperature;

    /** LangChain4j 模型懒加载：@Value 注入完成后首次调用时按配置构建 */
    private volatile ChatLanguageModel chatLanguageModel;
    private volatile StreamingChatLanguageModel streamingChatLanguageModel;

    private ChatLanguageModel chatModel() {
        if (chatLanguageModel == null) {
            synchronized (this) {
                if (chatLanguageModel == null) {
                    chatLanguageModel = OpenAiChatModel.builder()
                            .apiKey(apiKey)
                            .baseUrl(apiBaseUrl)
                            .modelName(model)
                            .maxTokens(maxTokens)
                            .temperature(temperature)
                            .timeout(java.time.Duration.ofSeconds(60))
                            .build();
                }
            }
        }
        return chatLanguageModel;
    }

    private StreamingChatLanguageModel streamingModel() {
        if (streamingChatLanguageModel == null) {
            synchronized (this) {
                if (streamingChatLanguageModel == null) {
                    streamingChatLanguageModel = OpenAiStreamingChatModel.builder()
                            .apiKey(apiKey)
                            .baseUrl(apiBaseUrl)
                            .modelName(model)
                            .maxTokens(maxTokens)
                            .temperature(temperature)
                            // 覆盖整个流式生成周期（SSE Emitter 超时 300s 之内）
                            .timeout(java.time.Duration.ofSeconds(240))
                            .build();
                }
            }
        }
        return streamingChatLanguageModel;
    }

    /** 项目消息角色 → LangChain4j 消息 */
    private List<ChatMessage> toLangChainMessages(List<AiChatRequest.ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>();
        for (AiChatRequest.ChatMessage m : messages) {
            String role = m.getRole() == null ? "user" : m.getRole().toLowerCase();
            switch (role) {
                case "assistant" -> result.add(dev.langchain4j.data.message.AiMessage.from(m.getContent()));
                case "system" -> result.add(SystemMessage.from(m.getContent()));
                default -> result.add(UserMessage.from(m.getContent()));
            }
        }
        return result;
    }

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
        List<AiChatRequest.ChatMessage> msgs = request.getMessages();
        List<AiChatRequest.ChatMessage> sendMsgs = limitContextMessages(msgs);
        saveCurrentUserMessage(session, sendMsgs);

        // 调用 AI API（事务外）
        String aiResponse = callAiApi(sendMsgs);

        aiMessageMapper.insert(AiMessage.builder().sessionId(session.getId()).role("assistant").content(aiResponse).build());

        // 首次对话自动生成标题
        if (session.getTitle() == null) {
            session.setTitle(aiResponse.length() > 30 ? aiResponse.substring(0, 30) : aiResponse);
        } else {
            session.setUpdatedAt(java.time.LocalDateTime.now());
        }
        aiSessionMapper.updateById(session);

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
                // 保存本轮用户消息。请求中的其余消息仅作为 AI 上下文，避免历史消息重复入库。
                List<AiChatRequest.ChatMessage> msgs = request.getMessages();
                List<AiChatRequest.ChatMessage> sendMsgs = limitContextMessages(msgs);
                saveCurrentUserMessage(session, sendMsgs);

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
        }, sseExecutor);
        return emitter;
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

    /**
     * 调用 AI（LangChain4j 非流式），返回完整回复文本。
     * 上游异常统一映射为 AI_SERVICE_UNAVAILABLE，不透出内部实现细节。
     */
    protected String callAiApi(List<AiChatRequest.ChatMessage> messages) {
        try {
            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatModel().generate(toLangChainMessages(messages));
            String content = response.content().text();
            if (content == null || content.isBlank()) {
                log.error("AI API 响应内容为空");
                throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
            }
            return content;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("AI API 调用失败", e);
            // 不携带底层异常消息，避免向上游/内部实现细节泄露
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * 调用 AI 流式接口（LangChain4j StreamingChatLanguageModel，底层 stream:true），
     * 逐 token 回调；generate 为异步回调模型，用 CountDownLatch 等待生成完成。
     */
    protected void streamAiApi(List<AiChatRequest.ChatMessage> messages, java.util.function.Consumer<String> tokenConsumer) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        streamingModel().generate(toLangChainMessages(messages), new StreamingResponseHandler<dev.langchain4j.data.message.AiMessage>() {
            @Override
            public void onNext(String token) {
                if (token != null && !token.isEmpty()) {
                    tokenConsumer.accept(token);
                }
            }

            @Override
            public void onComplete(Response<dev.langchain4j.data.message.AiMessage> response) {
                done.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                done.countDown();
            }
        });

        try {
            if (!done.await(280, TimeUnit.SECONDS)) {
                log.error("AI 流式响应等待超时");
                throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }

        Throwable t = error.get();
        if (t != null) {
            log.error("AI 流式调用失败", t);
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * 优先真流式（边生成边推）；流式尚未发出任何内容即失败时，
     * 回退为非流式接口 + 分块推送，保证上游不支持 stream 时功能仍可用。
     */
    private void callAiApiStream(List<AiChatRequest.ChatMessage> messages, java.util.function.Consumer<String> chunkConsumer) {
        StringBuilder received = new StringBuilder();
        try {
            streamAiApi(messages, chunk -> {
                received.append(chunk);
                chunkConsumer.accept(chunk);
            });
            return;
        } catch (Exception e) {
            if (received.length() > 0) {
                throw e;   // 已推送部分内容，无法干净回退
            }
            log.warn("AI 流式接口不可用，回退为非流式分块推送: {}", e.getMessage());
        }
        String fullResponse = callAiApi(messages);
        for (int i = 0; i < fullResponse.length(); i += 5) {
            chunkConsumer.accept(fullResponse.substring(i, Math.min(i + 5, fullResponse.length())));
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }
}
