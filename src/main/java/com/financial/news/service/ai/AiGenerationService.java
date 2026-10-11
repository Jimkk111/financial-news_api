package com.financial.news.service.ai;

import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.dto.request.AiChatRequest;
import com.financial.news.service.ai.AiGenerationStore.Task;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Generation workers never send to a browser. Subscriptions only read committed events. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGenerationService {
    private final AiGenerationStore store;
    private final OpenAiCompatibleClient client;
    private final ExecutorService workers = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().name("ai-generation-", 0).factory());
    private final ScheduledExecutorService coordinator = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("ai-generation-coordinator").factory());
    private final ScheduledExecutorService delivery = Executors.newScheduledThreadPool(4, Thread.ofPlatform().daemon().name("ai-subscription-", 0).factory());
    private final ConcurrentMap<String, OpenAiCompatibleClient.StreamCancellation> active = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Task> claimed = new ConcurrentHashMap<>();
    private final java.util.Set<Runnable> subscriptions = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        // ApplicationReady is after the schema migration. The database queue survives restarts.
        coordinator.scheduleWithFixedDelay(this::coordinate, 0, 5, TimeUnit.SECONDS);
    }

    private void coordinate() {
        if (stopping) return;
        try {
            for (Task task : claimed.values()) {
                if (!store.renew(task)) {
                    OpenAiCompatibleClient.StreamCancellation cancellation = active.get(task.id());
                    if (cancellation != null) cancellation.cancel();
                }
            }
            store.recoverExpired();
            while (!stopping && claimed.size() < 4) {
                Task task = store.claim();
                if (task == null) break;
                var cancellation = new OpenAiCompatibleClient.StreamCancellation();
                claimed.put(task.id(), task);
                active.put(task.id(), cancellation);
                workers.submit(() -> run(task, cancellation));
            }
        } catch (Exception e) {
            // A failed renewal does not authorize further writes; the lease gates every write.
            log.warn("AI generation coordinator failed ({})", e.getClass().getSimpleName());
        }
    }

    private void run(Task task, OpenAiCompatibleClient.StreamCancellation cancellation) {
        String error = null;
        try {
            client.stream(store.params(task), new OpenAiCompatibleClient.StreamCallback() {
                @Override public void onContent(String delta) { store.append(task, "content", delta); }
                @Override public void onReasoning(String delta) { store.append(task, "reasoning", delta); }
                @Override public void onSources(List<OpenAiCompatibleClient.Source> sources) { store.append(task, "sources", sources); }
            }, task.webSearch(), cancellation);
        } catch (Exception e) {
            error = "AI_UPSTREAM_FAILED";
            if (!cancellation.isCancelled()) log.warn("AI generation {} ended ({})", task.id(), e.getClass().getSimpleName());
        } finally {
            try { store.finish(task, error); }
            catch (Exception e) { log.warn("Cannot finalize AI generation {} ({})", task.id(), e.getClass().getSimpleName()); }
            active.remove(task.id());
            claimed.remove(task.id());
        }
    }

    public Task create(int userId, AiChatRequest request, String key) {
        return store.create(userId, request, key);
    }
    public Task retry(int userId, String sourceId, String key) { return store.retry(sourceId, userId, key); }
    public AiGenerationStore.Snapshot snapshot(int userId, String id) { return store.snapshot(id, userId); }

    public AiGenerationStore.Snapshot cancel(int userId, String id) {
        store.cancel(id, userId); // Must commit before closing the upstream connection.
        var cancellation = active.get(id);
        if (cancellation != null) cancellation.cancel();
        return store.snapshot(id, userId);
    }

    public SseEmitter legacyStream(int userId, AiChatRequest request, String key) {
        Task task = create(userId, request, key == null ? UUID.randomUUID().toString() : key);
        return subscribe(userId, task.id(), 0, true);
    }

    public SseEmitter subscribe(int userId, String id, long afterSeq, boolean legacy) {
        Task initial = store.owned(id, userId);
        if (afterSeq < 0 || afterSeq > initial.lastSeq()) throw new BusinessException(ErrorCode.BAD_REQUEST);
        SseEmitter emitter = newEmitter();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        Runnable close = () -> {
            closed.set(true);
            ScheduledFuture<?> f = future.get();
            if (f != null) f.cancel(false);
        };
        subscriptions.add(close);
        emitter.onCompletion(() -> { close.run(); subscriptions.remove(close); });
        emitter.onTimeout(() -> { close.run(); subscriptions.remove(close); emitter.complete(); });
        emitter.onError(e -> { close.run(); subscriptions.remove(close); });
        Runnable pump = new Runnable() {
            private long cursor = afterSeq;
            private long lastSent = System.nanoTime();
            private boolean initialized;
            @Override public void run() {
                if (closed.get()) return;
                try {
                    if (!initialized) {
                        emitter.send(SseEmitter.event().data(Map.of("sessionId", initial.publicSessionId(),
                                "generationId", id, "messageId", initial.messageId(), "status", initial.status())));
                        initialized = true;
                    }
                    // Every poll starts from the last successfully sent committed event; no replay/live gap.
                    List<AiGenerationStore.Event> events = store.events(id, cursor);
                    for (var event : events) {
                        var builder = SseEmitter.event().id(Long.toString(event.seq())).data(event.payload(), org.springframework.http.MediaType.APPLICATION_JSON);
                        if (!legacy) builder.name(event.type());
                        emitter.send(builder);
                        cursor = event.seq();
                        lastSent = System.nanoTime();
                    }
                    Task latest = store.owned(id, userId);
                    if (latest.terminal() && cursor >= latest.lastSeq()) {
                        if (legacy) emitter.send(SseEmitter.event().data("[DONE]"));
                        else emitter.send(SseEmitter.event().name("end").data(Map.of("status", latest.status(), "lastSeq", latest.lastSeq())));
                        close.run(); subscriptions.remove(close); emitter.complete();
                    } else if (System.nanoTime() - lastSent >= TimeUnit.SECONDS.toNanos(15)) {
                        emitter.send(SseEmitter.event().data(Map.of("heartbeat", true)));
                        lastSent = System.nanoTime();
                    }
                } catch (Exception e) {
                    // Delivery errors must not escape into the generation callback.
                    close.run(); subscriptions.remove(close);
                    try { emitter.complete(); } catch (Exception ignored) { }
                }
            }
        };
        future.set(delivery.scheduleWithFixedDelay(pump, 0, 250, TimeUnit.MILLISECONDS));
        if (closed.get()) close.run();
        return emitter;
    }

    SseEmitter newEmitter() { return new SseEmitter(300_000L); } // Only the subscription expires.

    /** Call inside the existing session-deletion transaction. */
    public void guardSessionDeletion(int sessionId) {
        store.lockSession(sessionId);
        store.assertNoActiveTask(sessionId);
    }

    @PreDestroy
    public void shutdown() {
        stopping = true;
        coordinator.shutdownNow();
        subscriptions.forEach(Runnable::run);
        delivery.shutdownNow();
        active.values().forEach(OpenAiCompatibleClient.StreamCancellation::cancel);
        workers.shutdown();
    }
}
