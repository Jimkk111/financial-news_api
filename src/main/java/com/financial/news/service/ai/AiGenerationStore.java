package com.financial.news.service.ai;

import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.dto.request.AiChatRequest;
import com.financial.news.entity.AiMessage;
import com.financial.news.entity.AiSession;
import com.financial.news.mapper.AiMessageMapper;
import com.financial.news.mapper.AiSessionMapper;
import com.financial.news.utils.IdGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** Database is the queue and event log. All content/status changes lock the same task row. */
@Service
@RequiredArgsConstructor
public class AiGenerationStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AiSessionMapper sessions;
    private final AiMessageMapper messages;
    private final ObjectMapper json;

    public record Task(String id, int userId, int sessionId, String publicSessionId, int messageId,
                       String status, long lastSeq, String workerToken, String paramsJson,
                       boolean webSearch, String requestHash, String errorCode, boolean leaseValid) {
        public boolean terminal() { return Set.of("completed", "cancelled", "failed").contains(status); }
    }
    public record Event(long seq, String type, String payload) {}
    public record Snapshot(String generationId, String sessionId, int messageId, String status,
                           String content, String reasoning, Object sources, long lastSeq, String errorCode,
                           String retryOf, String supersededBy) {}

    private static final String SELECT_TASK = "SELECT *, lease_until > CURRENT_TIMESTAMP AS lease_valid FROM ai_generation_tasks";
    private static final RowMapper<Task> TASK_MAPPER = (rs, n) -> new Task(
            rs.getString("id"), rs.getInt("user_id"), rs.getInt("session_id"), rs.getString("public_session_id"),
            rs.getInt("message_id"), rs.getString("status"), rs.getLong("last_seq"), rs.getString("worker_token"),
            rs.getString("params_json"), rs.getBoolean("web_search"), rs.getString("request_hash"),
            rs.getString("error_code"), rs.getBoolean("lease_valid"));

    public Task create(int userId, AiChatRequest request, String key) {
        validateKey(key);
        String hash = hash(encode(Arrays.asList(request.getSessionId(), request.getMessages(), Boolean.TRUE.equals(request.getWebSearch()))));
        try {
            return transactions.execute(tx -> {
                Task existing = byKey(userId, key);
                if (existing != null) return checkHash(existing, hash);
                AiSession session;
                if (request.getSessionId() == null || request.getSessionId().isBlank()) {
                    session = AiSession.builder().sessionId(IdGenerator.generateSessionId()).userId(userId).build();
                    sessions.insert(session);
                } else {
                    session = sessions.selectBySessionId(request.getSessionId());
                    if (session == null) throw new BusinessException(ErrorCode.AI_SESSION_NOT_FOUND);
                    if (!session.getUserId().equals(userId)) throw new BusinessException(ErrorCode.AI_SESSION_NOT_OWNER);
                }
                // Same lock order as session deletion. Prevent overlapping generations in one session.
                lockSession(session.getId());
                assertNoActiveTask(session.getId());
                List<AiChatRequest.ChatMessage> context = request.getMessages();
                if (context.size() > 20) context = context.subList(context.size() - 20, context.size());
                AiChatRequest.ChatMessage current = context.get(context.size() - 1);
                if ("user".equalsIgnoreCase(current.getRole())) {
                    messages.insert(AiMessage.builder().sessionId(session.getId()).role("user").content(current.getContent()).build());
                }
                AiMessage assistant = AiMessage.builder().sessionId(session.getId()).role("assistant").content("").build();
                messages.insert(assistant);
                String id = "g_" + UUID.randomUUID();
                boolean search = Boolean.TRUE.equals(request.getWebSearch());
                List<OpenAiCompatibleClient.ChatParam> params = new ArrayList<>();
                if (search) params.add(new OpenAiCompatibleClient.ChatParam("system", "今天是 " + LocalDate.now()
                        + "。你是财经资讯助手，回答基于联网搜索结果，在引用信息处标注来源编号（如[1][2]），不要编造来源。"));
                for (AiChatRequest.ChatMessage m : context) params.add(new OpenAiCompatibleClient.ChatParam(m.getRole().toLowerCase(Locale.ROOT), m.getContent()));
                jdbc.update("""
                        INSERT INTO ai_generation_tasks
                        (id,user_id,session_id,public_session_id,message_id,idempotency_key,request_hash,params_json,web_search)
                        VALUES (?,?,?,?,?,?,?,?,?)
                        """, id, userId, session.getId(), session.getSessionId(), assistant.getId(), key, hash, encode(params), search);
                return task(id, false);
            });
        } catch (DuplicateKeyException e) {
            Task existing = byKey(userId, key);
            if (existing == null) throw e;
            return checkHash(existing, hash);
        } catch (BusinessException e) {
            // Concurrent retry may have waited for the session lock while the original committed.
            if (e.getErrorCode() == ErrorCode.CONFLICT) {
                Task existing = byKey(userId, key);
                if (existing != null) return checkHash(existing, hash);
            }
            throw e;
        }
    }

    private void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "需要有效的 Idempotency-Key");
        }
    }

    /** Retry a saved turn, without accepting new context or inserting another user message. */
    public Task retry(String sourceId, int userId, String key) {
        validateKey(key);
        String requestHash = hash("retry:" + sourceId);
        try {
            return transactions.execute(tx -> {
                Task existing = byKey(userId, key);
                if (existing != null) return checkHash(existing, requestHash);
                Task origin = owned(sourceId, userId);
                lockSession(origin.sessionId());
                origin = task(sourceId, true);
                authorize(origin, userId);
                if (!origin.terminal()) throw new BusinessException(ErrorCode.CONFLICT, "请先停止原任务并等待结束");
                if (!jdbc.queryForList("SELECT generation_id FROM ai_generation_retries WHERE source_generation_id=? FOR UPDATE", String.class, sourceId).isEmpty()) {
                    throw new BusinessException(ErrorCode.CONFLICT, "原任务已有重试，请使用最新任务");
                }
                assertNoActiveTask(origin.sessionId());
                // Rewriting an earlier turn after later messages exist would invalidate their context.
                if (!jdbc.queryForList("SELECT id FROM ai_messages WHERE session_id=? AND id>? LIMIT 1 FOR UPDATE",
                        Integer.class, origin.sessionId(), origin.messageId()).isEmpty()) {
                    throw new BusinessException(ErrorCode.CONFLICT, "只能重试会话最后一轮回复");
                }
                AiMessage assistant = AiMessage.builder().sessionId(origin.sessionId()).role("assistant").content("").build();
                messages.insert(assistant);
                String id = "g_" + UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO ai_generation_tasks
                        (id,user_id,session_id,public_session_id,message_id,idempotency_key,request_hash,params_json,web_search)
                        VALUES (?,?,?,?,?,?,?,?,?)
                        """, id, userId, origin.sessionId(), origin.publicSessionId(), assistant.getId(), key,
                        requestHash, origin.paramsJson(), origin.webSearch());
                jdbc.update("INSERT INTO ai_generation_retries(source_generation_id,generation_id) VALUES(?,?)", sourceId, id);
                return task(id, false);
            });
        } catch (DuplicateKeyException e) {
            Task existing = byKey(userId, key);
            if (existing == null) throw e;
            return checkHash(existing, requestHash);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.CONFLICT) {
                Task existing = byKey(userId, key);
                if (existing != null) return checkHash(existing, requestHash);
            }
            throw e;
        }
    }

    private Task checkHash(Task task, String hash) {
        if (!task.requestHash().equals(hash)) throw new BusinessException(ErrorCode.CONFLICT, "幂等键已用于其他请求");
        return task;
    }
    private Task byKey(int userId, String key) {
        return jdbc.query(SELECT_TASK + " WHERE user_id=? AND idempotency_key=?", TASK_MAPPER, userId, key).stream().findFirst().orElse(null);
    }
    private Task task(String id, boolean lock) {
        return jdbc.query(SELECT_TASK + " WHERE id=?" + (lock ? " FOR UPDATE" : ""), TASK_MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
    public Task owned(String id, int userId) {
        Task task = task(id, false);
        authorize(task, userId);
        return task;
    }
    private void authorize(Task task, int userId) {
        if (task.userId() != userId) throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    public void lockSession(int sessionId) {
        if (jdbc.queryForList("SELECT id FROM ai_sessions WHERE id=? FOR UPDATE", Integer.class, sessionId).isEmpty()) {
            throw new BusinessException(ErrorCode.AI_SESSION_NOT_FOUND);
        }
    }
    public void assertNoActiveTask(int sessionId) {
        List<String> ids = jdbc.queryForList("SELECT id FROM ai_generation_tasks WHERE session_id=? AND status IN ('queued','running','cancel_requested') FOR UPDATE", String.class, sessionId);
        if (!ids.isEmpty()) throw new BusinessException(ErrorCode.CONFLICT, "会话仍有生成任务，请先停止并等待结束");
    }

    public Task claim() {
        return transactions.execute(tx -> {
            List<Task> rows = jdbc.query(SELECT_TASK + " WHERE status='queued' ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED", TASK_MAPPER);
            if (rows.isEmpty()) return null;
            String id = rows.getFirst().id();
            jdbc.update("UPDATE ai_generation_tasks SET status='running',worker_token=?,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP) WHERE id=?", UUID.randomUUID().toString(), id);
            return task(id, false);
        });
    }

    public boolean renew(Task task) {
        return jdbc.update("""
                UPDATE ai_generation_tasks SET lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP)
                WHERE id=? AND worker_token=? AND status='running' AND lease_until > CURRENT_TIMESTAMP
                """, task.id(), task.workerToken()) == 1;
    }

    public List<OpenAiCompatibleClient.ChatParam> params(Task task) {
        try { return json.readValue(task.paramsJson(), new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalStateException("Invalid saved AI context", e); }
    }

    public void append(Task worker, String field, Object delta) {
        transactions.executeWithoutResult(tx -> {
            Task task = task(worker.id(), true);
            requireRunning(task, worker);
            switch (field) {
                case "content" -> jdbc.update("UPDATE ai_messages SET content=CONCAT(content,?) WHERE id=?", delta, task.messageId());
                case "reasoning" -> jdbc.update("UPDATE ai_messages SET reasoning_content=CONCAT(COALESCE(reasoning_content,''),?) WHERE id=?", delta, task.messageId());
                case "sources" -> {
                    String previous = jdbc.queryForObject("SELECT sources FROM ai_messages WHERE id=?", String.class, task.messageId());
                    List<Object> sources = new ArrayList<>();
                    if (previous != null) sources.addAll(decodeList(previous));
                    sources.addAll((List<?>) delta);
                    jdbc.update("UPDATE ai_messages SET sources=? WHERE id=?", encode(sources), task.messageId());
                }
                default -> throw new IllegalArgumentException("Unknown delta field");
            }
            event(task, "delta", Map.of(field, delta));
        });
    }

    private void requireRunning(Task task, Task worker) {
        if (!"running".equals(task.status()) || !Objects.equals(task.workerToken(), worker.workerToken()) || !task.leaseValid()) {
            throw new GenerationStoppedException();
        }
    }
    public static class GenerationStoppedException extends RuntimeException {}

    public Task cancel(String id, int userId) {
        return transactions.execute(tx -> {
            Task task = task(id, true);
            authorize(task, userId);
            if (task.terminal() || "cancel_requested".equals(task.status())) return task;
            jdbc.update("UPDATE ai_generation_tasks SET cancel_requested_at=CURRENT_TIMESTAMP,status=? WHERE id=?",
                    "queued".equals(task.status()) ? "cancelled" : "cancel_requested", id);
            if ("queued".equals(task.status())) event(task, "cancelled", Map.of("status", "cancelled"));
            return task(id, false);
        });
    }

    public void finish(Task worker, String errorCode) {
        transactions.executeWithoutResult(tx -> {
            lockSession(worker.sessionId());
            Task task = task(worker.id(), true);
            if (task.terminal() || !Objects.equals(task.workerToken(), worker.workerToken()) || !task.leaseValid()) return;
            String content = jdbc.queryForObject("SELECT content FROM ai_messages WHERE id=?", String.class, task.messageId());
            String status = "cancel_requested".equals(task.status()) ? "cancelled"
                    : errorCode != null || content == null || content.isBlank() ? "failed" : "completed";
            String error = "failed".equals(status) ? (errorCode != null ? errorCode : "AI_EMPTY_RESPONSE") : null;
            terminal(task, status, error);
            if (content != null && !content.isBlank()) jdbc.update("UPDATE ai_sessions SET title=COALESCE(title,?),updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    content.substring(0, Math.min(30, content.length())), task.sessionId());
        });
    }

    public void recoverExpired() {
        List<String> ids = jdbc.queryForList("SELECT id FROM ai_generation_tasks WHERE status IN ('running','cancel_requested') AND lease_until <= CURRENT_TIMESTAMP LIMIT 100", String.class);
        for (String id : ids) transactions.executeWithoutResult(tx -> {
            Task task = task(id, true);
            if (task.terminal() || task.leaseValid()) return;
            terminal(task, "cancel_requested".equals(task.status()) ? "cancelled" : "failed",
                    "cancel_requested".equals(task.status()) ? null : "WORKER_INTERRUPTED");
        });
    }

    private void terminal(Task task, String status, String error) {
        jdbc.update("UPDATE ai_generation_tasks SET status=?,error_code=?,lease_until=NULL WHERE id=?", status, error, task.id());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        if (error != null) { payload.put("errorCode", error); payload.put("error", "AI 生成未完成，已保留生成内容"); }
        event(task, status, payload);
    }
    private void event(Task task, String type, Map<String, ?> payload) {
        long seq = task.lastSeq() + 1;
        Map<String, Object> data = new LinkedHashMap<>(payload);
        data.put("generationId", task.id());
        data.put("seq", seq);
        data.put("type", type);
        jdbc.update("INSERT INTO ai_generation_events(generation_id,seq,type,payload) VALUES(?,?,?,?)", task.id(), seq, type, encode(data));
        jdbc.update("UPDATE ai_generation_tasks SET last_seq=? WHERE id=?", seq, task.id());
    }

    public Snapshot snapshot(String id, int userId) {
        return transactions.execute(tx -> {
            Task task = task(id, true);
            authorize(task, userId);
            return jdbc.queryForObject("""
                    SELECT content,reasoning_content,sources,
                    (SELECT source_generation_id FROM ai_generation_retries WHERE generation_id=?) AS retry_of,
                    (SELECT generation_id FROM ai_generation_retries WHERE source_generation_id=?) AS superseded_by
                    FROM ai_messages WHERE id=?
                    """, (rs,n) ->
                    new Snapshot(task.id(), task.publicSessionId(), task.messageId(), task.status(), rs.getString("content"),
                            rs.getString("reasoning_content"), rs.getString("sources") == null ? List.of() : decodeList(rs.getString("sources")),
                            task.lastSeq(), task.errorCode(), rs.getString("retry_of"), rs.getString("superseded_by")), task.id(), task.id(), task.messageId());
        });
    }
    public List<Event> events(String id, long afterSeq) {
        return jdbc.query("SELECT seq,type,payload FROM ai_generation_events WHERE generation_id=? AND seq>? ORDER BY seq LIMIT 200",
                (rs,n) -> new Event(rs.getLong("seq"),rs.getString("type"),rs.getString("payload")), id, afterSeq);
    }
    private List<Object> decodeList(String value) {
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalStateException("Invalid persisted JSON", e); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot encode generation data", e); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
