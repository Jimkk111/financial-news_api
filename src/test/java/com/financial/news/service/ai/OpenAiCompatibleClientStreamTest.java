package com.financial.news.service.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleClientStreamTest {
    private HttpServer server;
    private OpenAiCompatibleClient client;
    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        client = new OpenAiCompatibleClient();
        ReflectionTestUtils.setField(client, "apiBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
        ReflectionTestUtils.setField(client, "model", "test-model");
        ReflectionTestUtils.setField(client, "maxTokens", 100);
    }
    @AfterEach void cleanup() { release.countDown(); server.stop(0); executor.shutdownNow(); }

    @Test void cancelSilentStreamWithoutWaitingForAnotherToken() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(": waiting\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            connected.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        var cancellation = new OpenAiCompatibleClient.StreamCancellation();
        Future<?> stream = executor.submit(() -> client.stream(List.of(), new OpenAiCompatibleClient.StreamCallback() {}, false, cancellation));
        assertTrue(connected.await(3, TimeUnit.SECONDS));
        cancellation.cancel();
        var error = assertThrows(ExecutionException.class, () -> stream.get(2, TimeUnit.SECONDS));
        assertInstanceOf(OpenAiCompatibleClient.AiRemoteException.class, error.getCause());
    }

    @Test void prematureEofDoesNotLookLikeSuccessfulCompletion() {
        serve("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n");
        StringBuilder partial = new StringBuilder();
        assertThrows(OpenAiCompatibleClient.AiRemoteException.class, () -> client.stream(List.of(),
                new OpenAiCompatibleClient.StreamCallback() {
                    @Override public void onContent(String delta) { partial.append(delta); }
                }, false));
        assertEquals("partial", partial.toString());
    }

    @Test void completedStreamRetainsReasoningAndContent() {
        serve("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\",\"content\":\"reply\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
        var result = client.stream(List.of(), new OpenAiCompatibleClient.StreamCallback() {}, false);
        assertEquals("thinking", result.reasoning());
        assertEquals("reply", result.content());
    }

    private void serve(String data) {
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            byte[] body = data.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }
}
