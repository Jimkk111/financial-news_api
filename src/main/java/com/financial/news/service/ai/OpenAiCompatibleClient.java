package com.financial.news.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI 兼容接口直连客户端
 *
 * <p>思考型模型（MiMo / DeepSeek-R1 等）在流式 delta 中以 {@code reasoning_content} 字段
 * 返回思考链，而 langchain4j 0.35 依赖的 openai4j 不映射该字段，反序列化时会静默丢弃，
 * 导致前端在模型思考期间收不到任何数据。因此 AI 对话链路绕开 langchain4j 直连 HTTP，
 * 同时解析 {@code reasoning_content} 与 {@code content}。</p>
 *
 * <p>多轮对话回传历史时只回传 user/assistant 的 {@code content}，
 * 不回传 {@code reasoning_content}（思考链不属于对话上下文）。</p>
 */
@Slf4j
@Component
public class OpenAiCompatibleClient {

    /** 对话入参：role 固定小写（user/assistant/system），内容为纯文本 */
    public record ChatParam(String role, String content) {}

    /** 一次完成的对话结果：正文与思考链（思考链可能为空，取决于模型是否思考） */
    public record ChatResult(String content, String reasoning) {}

    /**
     * 流式回调。回调内抛出的任何异常都会中止上游请求并以该异常结束 {@link #stream}，
     * 用于客户端断连时及时停止生成计费。
     */
    public interface StreamCallback {
        /** 思考链增量（reasoning_content） */
        default void onReasoning(String delta) {}

        /** 正文增量（content） */
        default void onContent(String delta) {}
    }

    /** 上游调用失败（网络错误 / 非 2xx / 流中断），message 已脱敏为可记日志的摘要 */
    public static class AiRemoteException extends RuntimeException {
        public AiRemoteException(String message) { super(message); }
        public AiRemoteException(String message, Throwable cause) { super(message, cause); }
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    /** 上游错误响应体只保留前 500 字符进异常消息，避免日志与异常膨胀 */
    private static final int ERROR_BODY_LIMIT = 500;
    /** 流式整体等待上限，与 SseEmitter 超时（300s）留出余量 */
    private static final long STREAM_AWAIT_SECONDS = 280;

    @Value("${ai.api-key:}") private String apiKey;
    @Value("${ai.api-base-url:https://api.openai.com/v1}") private String apiBaseUrl;
    @Value("${ai.model:gpt-3.5-turbo}") private String model;
    @Value("${ai.max-tokens:2000}") private int maxTokens;
    @Value("${ai.temperature:0.7}") private double temperature;

    private final ObjectMapper mapper = new ObjectMapper();
    private volatile OkHttpClient httpClient;

    /** 连接 15s / 读 240s（思考期间 reasoning 增量持续到达会不断重置）；callTimeout 由各方法按需覆盖 */
    private OkHttpClient client() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(240, TimeUnit.SECONDS)
                            .writeTimeout(15, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return httpClient;
    }

    /**
     * 非流式对话，阻塞至完整响应。
     * callTimeout 60s 覆盖整个请求周期，与旧 langchain4j 非流式超时一致。
     */
    public ChatResult chat(List<ChatParam> messages) {
        Request request = buildRequest(messages, false);
        OkHttpClient callScoped = client().newBuilder()
                .callTimeout(60, TimeUnit.SECONDS)
                .build();
        try (Response response = callScoped.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new AiRemoteException("AI 接口返回 " + response.code()
                        + (response.body() != null ? ": " + readBodyLimited(response) : ""));
            }
            JsonNode message = mapper.readTree(response.body().string())
                    .path("choices").path(0).path("message");
            return new ChatResult(
                    message.path("content").asText(""),
                    message.path("reasoning_content").asText(""));
        } catch (IOException e) {
            throw new AiRemoteException("AI 接口调用失败: " + rootMessage(e), e);
        }
    }

    /**
     * 流式对话，阻塞至生成完成；思考链与正文增量通过 callback 逐段推送。
     * 上游失败或 callback 抛异常时中止请求并以异常结束。
     */
    public ChatResult stream(List<ChatParam> messages, StreamCallback callback) {
        Request request = buildRequest(messages, true);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();

        EventSourceListener listener = new EventSourceListener() {
            @Override
            public void onEvent(EventSource source, String id, String type, String data) {
                try {
                    if ("[DONE]".equals(data.trim())) {
                        done.countDown();
                        source.cancel();
                        return;
                    }
                    JsonNode delta = mapper.readTree(data).path("choices").path(0).path("delta");
                    // 部分 OpenAI 兼容实现（vLLM/中转站）最后会发一个 choices 为空的 usage 块，path() 天然跳过
                    String reasoningDelta = delta.path("reasoning_content").asText("");
                    if (!reasoningDelta.isEmpty()) {
                        reasoning.append(reasoningDelta);
                        callback.onReasoning(reasoningDelta);
                    }
                    String contentDelta = delta.path("content").asText("");
                    if (!contentDelta.isEmpty()) {
                        content.append(contentDelta);
                        callback.onContent(contentDelta);
                    }
                } catch (Exception e) {
                    // callback 抛异常（如 SSE 客户端已断连）或响应体畸形：中止上游，向调用方透传
                    failure.compareAndSet(null, e);
                    done.countDown();
                    source.cancel();
                }
            }

            @Override
            public void onClosed(EventSource source) {
                done.countDown();
            }

            @Override
            public void onFailure(EventSource source, Throwable t, Response response) {
                if (response != null) {
                    int code = response.code();
                    String body = readBodyLimited(response);
                    response.close();
                    failure.compareAndSet(null, new AiRemoteException(
                            "AI 流式接口返回 " + code + (body.isEmpty() ? "" : ": " + body)));
                } else {
                    failure.compareAndSet(null, t != null ? t
                            : new AiRemoteException("AI 流式接口连接失败"));
                }
                done.countDown();
            }
        };

        EventSource eventSource = EventSources.createFactory(client()).newEventSource(request, listener);
        try {
            if (!done.await(STREAM_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                eventSource.cancel();
                throw new AiRemoteException("AI 流式响应等待超时");
            }
        } catch (InterruptedException e) {
            eventSource.cancel();
            Thread.currentThread().interrupt();
            throw new AiRemoteException("AI 流式响应等待被中断", e);
        }

        Throwable t = failure.get();
        if (t instanceof AiRemoteException ae) {
            throw ae;
        }
        if (t != null) {
            throw new AiRemoteException("AI 流式调用失败: " + rootMessage(t), t);
        }
        return new ChatResult(content.toString(), reasoning.toString());
    }

    private Request buildRequest(List<ChatParam> messages, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", stream);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        ArrayNode arr = body.putArray("messages");
        for (ChatParam m : messages) {
            ObjectNode node = arr.addObject();
            node.put("role", m.role());
            node.put("content", m.content());
        }
        return new Request.Builder()
                .url(apiBaseUrl.replaceAll("/$", "") + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
    }

    private String readBodyLimited(Response response) {
        try {
            if (response.body() == null) {
                return "";
            }
            String text = response.body().string();
            return text.length() > ERROR_BODY_LIMIT ? text.substring(0, ERROR_BODY_LIMIT) + "..." : text;
        } catch (IOException e) {
            return "";
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : t.getClass().getSimpleName();
    }
}
