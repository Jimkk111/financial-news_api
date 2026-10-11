package com.financial.news.service.ai;

import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.service.ai.AiGenerationStore.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiGenerationServiceTest {
    private final AiGenerationStore store = mock(AiGenerationStore.class);
    private final OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
    private AiGenerationService service;
    private final Task task = new Task("g-test", 7, 42, "session-test", 11, "running", 0,
            "worker-test", "[]", false, "hash", null, true);

    @BeforeEach void setup() { service = spy(new AiGenerationService(store, client)); }
    @AfterEach void cleanup() { service.shutdown(); }

    @Test void deliveryFailureDoesNotCancelOrLoseGeneration() throws Exception {
        CountDownLatch disconnected = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        SseEmitter broken = new SseEmitter() {
            @Override public void send(SseEventBuilder event) throws IOException {
                disconnected.countDown();
                throw new IOException("client disconnected");
            }
        };
        doReturn(broken).when(service).newEmitter();
        when(store.owned(task.id(), 7)).thenReturn(task);
        when(store.claim()).thenReturn(task).thenReturn(null);
        when(store.params(task)).thenReturn(List.of());
        doAnswer(i -> {
            assertTrue(disconnected.await(3, TimeUnit.SECONDS));
            var cancellation = i.getArgument(3, OpenAiCompatibleClient.StreamCancellation.class);
            assertFalse(cancellation.isCancelled());
            var callback = i.getArgument(1, OpenAiCompatibleClient.StreamCallback.class);
            callback.onReasoning("思考");
            callback.onContent("断线后的回复");
            return new OpenAiCompatibleClient.ChatResult("断线后的回复", "思考", List.of());
        }).when(client).stream(anyList(), any(), eq(false), any());
        doAnswer(i -> { finished.countDown(); return null; }).when(store).finish(task, null);
        service.subscribe(7, task.id(), 0, false);
        service.start();
        assertTrue(finished.await(4, TimeUnit.SECONDS));
        verify(store).append(task, "reasoning", "思考");
        verify(store).append(task, "content", "断线后的回复");
        verify(store, never()).cancel(anyString(), anyInt());
    }

    @Test void manualStopCancelsEvenWhileUpstreamIsSilent() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OpenAiCompatibleClient.StreamCancellation> token = new AtomicReference<>();
        when(store.claim()).thenReturn(task).thenReturn(null);
        when(store.params(task)).thenReturn(List.of());
        doAnswer(i -> {
            token.set(i.getArgument(3));
            entered.countDown();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!token.get().isCancelled() && System.nanoTime() < end) Thread.sleep(5);
            assertTrue(token.get().isCancelled());
            throw new OpenAiCompatibleClient.AiRemoteException("cancelled");
        }).when(client).stream(anyList(), any(), eq(false), any());
        doAnswer(i -> { finished.countDown(); return null; }).when(store).finish(eq(task), any());
        service.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        service.cancel(7, task.id());
        assertTrue(finished.await(3, TimeUnit.SECONDS));
        verify(store).cancel(task.id(), 7);
        verify(store, never()).append(any(), anyString(), any());
    }

    @Test void unauthorizedCancellationNeverTouchesUpstream() {
        when(store.cancel(task.id(), 99)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertThrows(BusinessException.class, () -> service.cancel(99, task.id()));
        verifyNoInteractions(client);
    }

    @Test void rejectsCursorBeyondCommittedEvents() {
        when(store.owned(task.id(), 7)).thenReturn(task);
        assertThrows(BusinessException.class, () -> service.subscribe(7, task.id(), 1, false));
    }
}
