package com.financial.news.service.ai;

import com.financial.news.common.BusinessException;
import com.financial.news.config.AuditTimeFillInterceptor;
import com.financial.news.dto.request.AiChatRequest;
import com.financial.news.mapper.AiMessageMapper;
import com.financial.news.mapper.AiSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Runs against an isolated MySQL server (CI uses a MySQL 8 service). Never uses application config. */
@EnabledIfEnvironmentVariable(named = "AI_GENERATION_TEST_JDBC_URL", matches = ".+")
class AiGenerationStoreMysqlTest {
    private DriverManagerDataSource admin;
    private String schema;
    private JdbcTemplate jdbc;
    private AiGenerationStore store;

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("AI_GENERATION_TEST_JDBC_URL");
        String user = System.getenv().getOrDefault("AI_GENERATION_TEST_DB_USER", "root");
        String password = System.getenv().getOrDefault("AI_GENERATION_TEST_DB_PASSWORD", "");
        admin = new DriverManagerDataSource(url, user, password);
        schema = "ai_generation_test_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(admin).execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        int query = url.indexOf('?');
        String base = query < 0 ? url : url.substring(0, query);
        String options = query < 0 ? "" : url.substring(query);
        var ds = new DriverManagerDataSource(base.replaceAll("/+$", "") + "/" + schema + options, user, password);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE ai_sessions (id INT AUTO_INCREMENT PRIMARY KEY, session_id VARCHAR(50) UNIQUE, user_id INT, title VARCHAR(100), created_at DATETIME, updated_at DATETIME)");
        jdbc.execute("CREATE TABLE ai_messages (id INT AUTO_INCREMENT PRIMARY KEY, session_id INT, role VARCHAR(20), content TEXT NOT NULL, reasoning_content MEDIUMTEXT, sources JSON, created_at DATETIME, FOREIGN KEY(session_id) REFERENCES ai_sessions(id) ON DELETE CASCADE)");
        ResourceDatabasePopulator migration = new ResourceDatabasePopulator();
        migration.setSqlScriptEncoding("UTF-8");
        migration.addScript(new ClassPathResource("db/migration/v900-ai-generations.sql"));
        DatabasePopulatorUtils.execute(migration, ds);
        DatabasePopulatorUtils.execute(migration, ds); // Startup migration must be idempotent.
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setPlugins(new AuditTimeFillInterceptor());
        factory.setMapperLocations(new ClassPathResource("mapper/AiMessageMapper.xml"), new ClassPathResource("mapper/AiSessionMapper.xml"));
        SqlSessionTemplate mybatis = new SqlSessionTemplate(factory.getObject());
        store = new AiGenerationStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)),
                mybatis.getMapper(AiSessionMapper.class), mybatis.getMapper(AiMessageMapper.class), new ObjectMapper());
    }

    @AfterEach void cleanup() {
        if (schema != null && schema.matches("ai_generation_test_[a-f0-9]{32}")) {
            new JdbcTemplate(admin).execute("DROP DATABASE " + schema);
        }
    }

    @Test void persistedContentAndEventsCanBeResumedAfterCompletion() {
        var created = store.create(7, request(null, "问题"), "request-1");
        var worker = store.claim();
        assertEquals(created.id(), worker.id());
        store.append(worker, "reasoning", "先思考");
        store.append(worker, "content", "第一段");
        store.append(worker, "content", "第二段");
        store.append(worker, "sources", List.of(new OpenAiCompatibleClient.Source("引用", "https://example.com", "", "", "", "")));
        var snapshot = store.snapshot(created.id(), 7);
        assertEquals("第一段第二段", snapshot.content());
        assertEquals("先思考", snapshot.reasoning());
        assertEquals(4, snapshot.lastSeq());
        assertEquals(List.of(3L, 4L), store.events(created.id(), 2).stream().map(AiGenerationStore.Event::seq).toList());
        store.finish(worker, null);
        assertEquals("completed", store.snapshot(created.id(), 7).status());
        assertEquals("completed", store.events(created.id(), 4).getFirst().type());
        assertEquals("completed", store.cancel(created.id(), 7).status());
    }

    @Test void cancellationPreservesPartialOutputAndRejectsLateDeltas() {
        var created = store.create(7, request(null, "问题"), "request-1");
        var worker = store.claim();
        store.append(worker, "content", "保留的部分");
        assertEquals("cancel_requested", store.cancel(created.id(), 7).status());
        assertFalse(store.renew(worker));
        assertThrows(AiGenerationStore.GenerationStoppedException.class, () -> store.append(worker, "content", "不应写入"));
        store.finish(worker, "AI_UPSTREAM_FAILED");
        var snapshot = store.snapshot(created.id(), 7);
        assertEquals("cancelled", snapshot.status());
        assertEquals("保留的部分", snapshot.content());
        assertEquals(2, snapshot.lastSeq());
        assertNull(snapshot.errorCode());
        assertEquals("cancelled", store.cancel(created.id(), 7).status());
    }

    @Test void queuedCancellationDoesNotStartAndIdempotencyDoesNotDuplicateMessages() {
        var request = request(null, "问题");
        var first = store.create(7, request, "same-key");
        assertEquals(first.id(), store.create(7, request, "same-key").id());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_messages", Integer.class));
        assertThrows(BusinessException.class, () -> store.create(7, request(null, "不同问题"), "same-key"));
        assertEquals("cancelled", store.cancel(first.id(), 7).status());
        assertNull(store.claim());
        assertEquals(1, store.snapshot(first.id(), 7).lastSeq());
    }

    @Test void concurrentCreationWithSameKeyCommitsOnlyOneTask() throws Exception {
        var first = store.create(7, request(null, "创建会话"), "initial");
        store.cancel(first.id(), 7);
        var request = request(first.publicSessionId(), "并发重试");
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            var a = threads.submit(() -> store.create(7, request, "concurrent"));
            var b = threads.submit(() -> store.create(7, request, "concurrent"));
            assertEquals(a.get(5, TimeUnit.SECONDS).id(), b.get(5, TimeUnit.SECONDS).id());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_generation_tasks", Integer.class));
            assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM ai_messages", Integer.class));
        } finally { threads.shutdownNow(); }
    }

    @Test void staleWorkerCannotWriteAndRecoveryKeepsPartialContent() {
        var created = store.create(7, request(null, "问题"), "request-1");
        var worker = store.claim();
        store.append(worker, "content", "已生成");
        jdbc.update("UPDATE ai_generation_tasks SET lease_until=TIMESTAMPADD(SECOND,-60,CURRENT_TIMESTAMP) WHERE id=?", created.id());
        assertFalse(store.renew(worker));
        assertThrows(AiGenerationStore.GenerationStoppedException.class, () -> store.append(worker, "content", "过期写入"));
        store.recoverExpired();
        store.finish(worker, null);
        var snapshot = store.snapshot(created.id(), 7);
        assertEquals("failed", snapshot.status());
        assertEquals("WORKER_INTERRUPTED", snapshot.errorCode());
        assertEquals("已生成", snapshot.content());
    }

    @Test void permissionsAndSessionOverlapAreEnforced() {
        var first = store.create(7, request(null, "问题"), "request-1");
        assertThrows(BusinessException.class, () -> store.snapshot(first.id(), 99));
        assertThrows(BusinessException.class, () -> store.cancel(first.id(), 99));
        assertThrows(BusinessException.class, () -> store.create(99, request(first.publicSessionId(), "越权"), "request-2"));
        assertThrows(BusinessException.class, () -> store.create(7, request(first.publicSessionId(), "重叠生成"), "request-3"));
        assertEquals("queued", store.owned(first.id(), 7).status());
    }

    @Test void failedEventInsertRollsBackContentAndCursorTogether() {
        var created = store.create(7, request(null, "问题"), "rollback");
        var worker = store.claim();
        jdbc.execute("CREATE TRIGGER reject_test_event BEFORE INSERT ON ai_generation_events FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test write failure'");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> store.append(worker, "content", "不能提交"));
        var snapshot = store.snapshot(created.id(), 7);
        assertEquals("", snapshot.content());
        assertEquals(0, snapshot.lastSeq());
        assertTrue(store.events(created.id(), 0).isEmpty());
    }

    @Test void simultaneousCancelAndDeltaHaveOneConsistentCutoff() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                var created = store.create(7, request(null, "问题"), "race-" + i);
                var worker = store.claim();
                CountDownLatch start = new CountDownLatch(1);
                Future<?> append = threads.submit(() -> {
                    start.await();
                    try { store.append(worker, "content", "已提交"); }
                    catch (AiGenerationStore.GenerationStoppedException expected) { }
                    return null;
                });
                Future<?> cancel = threads.submit(() -> { start.await(); return store.cancel(created.id(), 7); });
                start.countDown();
                append.get(5, TimeUnit.SECONDS); cancel.get(5, TimeUnit.SECONDS);
                store.finish(worker, null);
                var snapshot = store.snapshot(created.id(), 7);
                assertEquals("cancelled", snapshot.status());
                assertTrue(snapshot.content().isEmpty() || snapshot.content().equals("已提交"));
                assertEquals(snapshot.content().isEmpty() ? 1 : 2, snapshot.lastSeq());
                assertThrows(AiGenerationStore.GenerationStoppedException.class, () -> store.append(worker, "content", "迟到内容"));
            }
        } finally { threads.shutdownNow(); }
    }

    @Test void expiredCancelRequestIsFinalizedAsCancelled() {
        var created = store.create(7, request(null, "问题"), "cancel-recovery");
        var worker = store.claim();
        store.append(worker, "content", "保留");
        store.cancel(created.id(), 7);
        jdbc.update("UPDATE ai_generation_tasks SET lease_until=TIMESTAMPADD(SECOND,-60,CURRENT_TIMESTAMP) WHERE id=?", created.id());
        store.recoverExpired();
        assertEquals("cancelled", store.snapshot(created.id(), 7).status());
        assertEquals("保留", store.snapshot(created.id(), 7).content());
    }

    private AiChatRequest request(String session, String content) {
        AiChatRequest request = new AiChatRequest();
        AiChatRequest.ChatMessage message = new AiChatRequest.ChatMessage();
        message.setRole("user"); message.setContent(content);
        request.setSessionId(session); request.setMessages(List.of(message));
        return request;
    }
}
