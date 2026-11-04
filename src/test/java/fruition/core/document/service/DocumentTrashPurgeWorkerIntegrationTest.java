package fruition.core.document.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.document.repository.IngestCommandOutbox;
import fruition.core.purge.DataPurgeService;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 휴지통 보관 기간이 지난 문서·폴더만 DB와 저장소에서 지워지는지 실제 Postgres와 MinIO로 확인한다. */
@SpringBootTest(properties = "app.document-trash.purge-delay-ms=3600000")
@Import(TestcontainersConfiguration.class)
class DocumentTrashPurgeWorkerIntegrationTest {

    @Autowired DocumentTrashPurgeWorker worker;
    @Autowired DocumentService documentService;
    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate namedJdbc;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired MinioClient minio;
    @Autowired StorageProperties storage;
    @Autowired StringRedisTemplate redis;
    @Autowired IngestCommandOutbox ingestCommandOutbox;
    @Autowired DocumentCopyScrubber scrubber;

    static final String BODY = "비밀 본문";
    final String workspace = "ws_" + UUID.randomUUID();
    final String user = "user_" + UUID.randomUUID();
    /** 앱의 스케줄 실행은 실제 현재 시각을 쓴다. 미래 시각을 기준으로 삼아 테스트 행을 그 실행과 겹치지 않게 한다. */
    final Instant now = Instant.now().plus(Duration.ofDays(365));

    @Test
    void purgesOnlyExpiredTrashAndHandsOrphanedAssetsToCleanup() throws Exception {
        UUID folder = insertFolder(now.minus(Duration.ofDays(31)));
        String expired = insertDocument(folder, now.minus(Duration.ofDays(31)));
        String recent = insertDocument(null, now.minus(Duration.ofDays(29)));
        String active = insertDocument(null, null);
        UUID onlyExpired = insertAsset(expired);
        UUID shared = insertAsset(expired);
        jdbc.update("INSERT INTO document_asset_references(document_id, asset_id, created_at) VALUES (?, ?, now())",
                active, shared);

        int expiredBefore = expiredCount();
        assertThat(worker.purge(now)).isEqualTo(expiredBefore);

        assertThat(exists("documents", expired)).isFalse();
        assertThat(exists("folders", folder.toString())).isFalse();
        assertThat(exists("documents", recent)).isTrue();
        assertThat(exists("documents", active)).isTrue();
        assertThatThrownBy(() -> stat(sourceKey(expired))).isNotNull();
        stat(sourceKey(recent));
        assertThat(unreferencedSince(onlyExpired)).isNotNull();
        assertThat(unreferencedSince(shared)).isNull();

        assertThat(worker.purge(now)).isZero();
    }

    @Test
    void purgeRecordsDocumentPurgedCommandOnlyForPermanentlyDeletedDocuments() {
        String expired = insertDocument(null, now.minus(Duration.ofDays(31)));
        String recent = insertDocument(null, now.minus(Duration.ofDays(29)));
        String active = insertDocument(null, null);

        worker.purge(now);

        assertThat(purgedCommands(expired)).singleElement().satisfies(payload -> assertThat(payload)
                .contains("\"kind\":\"document_purged\"", "\"workspace_id\":\"" + workspace + "\"",
                        "\"document_id\":\"" + expired + "\"", "\"run_id\":"));
        // 휴지통에 있는 동안은 복구할 수 있어 AI 쪽 복사본을 지우라고 하지 않는다.
        assertThat(purgedCommands(recent)).isEmpty();
        assertThat(purgedCommands(active)).isEmpty();
    }

    @Test
    void rolledBackPurgeLeavesNoCommandAndKeepsDocument() {
        String expired = insertDocument(null, now.minus(Duration.ofDays(31)));

        transactionTemplate.executeWithoutResult(status -> {
            worker.purge(now);
            assertThat(exists("documents", expired)).isFalse();
            status.setRollbackOnly();
        });

        assertThat(purgedCommands(expired)).isEmpty();
        assertThat(exists("documents", expired)).isTrue();
    }

    @Test
    void purgeRemovesBodyFromFinishedRunCommandsOnlyForThatDocument() throws Exception {
        String expired = insertDocument(null, now.minus(Duration.ofDays(31)));
        String active = insertDocument(null, null);
        String edit = insertRun("completed", "agent", """
                {"kind":"agent","document_id":"%s","message":"질문","editor_snapshot":{"markdown":"%s","target":{"type":"whole_document"}}}
                """.formatted(expired, BODY));
        String chatWiki = insertRun("cancelled", "chat_wiki", """
                {"kind":"chat_wiki","document_id":"%s","input_markdown":"%s","input_blocks":[{"text":"%s"}]}
                """.formatted(expired, BODY, BODY));
        String retryable = insertRun("rollback_failed", "agent", """
                {"kind":"agent","document_id":"%s","editor_snapshot":{"markdown":"%s"}}
                """.formatted(expired, BODY));
        String other = insertRun("completed", "agent", """
                {"kind":"agent","document_id":"%s","editor_snapshot":{"markdown":"%s"}}
                """.formatted(active, BODY));

        worker.purge(now);

        JsonNode scrubbed = command(edit);
        assertThat(scrubbed.toString()).doesNotContain(BODY);
        assertThat(scrubbed.path("message").asText()).isEqualTo("질문");
        assertThat(scrubbed.path("editor_snapshot").path("target").path("type").asText()).isEqualTo("whole_document");
        // 해시가 달라졌음을 AI 취소 API에 알린다.
        assertThat(scrubbed.path("document_purged").asBoolean()).isTrue();
        assertThat(command(chatWiki).toString()).doesNotContain(BODY);
        assertThat(command(retryable).toString()).doesNotContain(BODY);
        assertThat(command(other).toString()).contains(BODY);
        assertThat(command(other).has("document_purged")).isFalse();
    }

    @Test
    void runningCommandIsKeptUntilItEndsThenScrubbedByNextRun() throws Exception {
        String expired = insertDocument(null, now.minus(Duration.ofDays(31)));
        String body = """
                {"kind":"agent","document_id":"%s","editor_snapshot":{"markdown":"%s"}}
                """.formatted(expired, BODY);
        String running = insertRun("running", "agent", body);
        String cancelling = insertRun("cancel_requested", "agent", body);
        String rollingBack = insertRun("rolling_back", "agent", body);

        worker.purge(now);

        // 진행 중인 실행의 취소는 원본 command 해시로 AI에 등록하므로 건드리면 깨진다.
        for (String run : List.of(running, cancelling, rollingBack)) {
            assertThat(command(run).toString()).contains(BODY);
        }

        jdbc.update("UPDATE ai_task_runs SET status = 'completed' WHERE id = ?", running);
        worker.purge(now);

        assertThat(command(running).toString()).doesNotContain(BODY);
        assertThat(command(cancelling).toString()).contains(BODY);
    }

    @Test
    void purgeRemovesBodyFromApplyProjectionsOnlyForThatDocument() {
        String expired = insertDocument(null, now.minus(Duration.ofDays(31)));
        String active = insertDocument(null, null);
        String result = """
                {"action":"markdown_edit","edit":{"replacement_markdown":"%s"},"generated_markdown":{"markdown":"%s"},
                 "chat":{"answer":"답변","evidence_snippets":[{"text":"%s"}]}}
                """.formatted(BODY, BODY, BODY);
        String ready = insertProjection(expired, "ready", result);
        String consumed = insertProjection(expired, "consumed", result);
        String failed = insertProjection(expired, "failed", result);
        String kept = insertProjection(active, "ready", result);

        worker.purge(now);

        Map<String, Object> readyRow = projection(ready);
        assertThat(readyRow.get("ready_markdown")).isNull();
        assertThat(readyRow.get("status")).isEqualTo("failed");
        assertThat(readyRow.get("error_code")).isEqualTo("document_purged");
        assertThat(readyRow.get("result").toString()).doesNotContain(BODY).contains("markdown_edit", "답변");
        Map<String, Object> consumedRow = projection(consumed);
        assertThat(consumedRow.get("status")).isEqualTo("consumed");
        assertThat(consumedRow.get("ready_markdown")).isNull();
        assertThat(consumedRow.get("result").toString()).doesNotContain(BODY);
        assertThat(projection(failed).get("ready_markdown")).isNull();
        Map<String, Object> keptRow = projection(kept);
        assertThat(keptRow.get("status")).isEqualTo("ready");
        assertThat(keptRow.get("ready_markdown")).isEqualTo(BODY);
        assertThat(keptRow.get("result").toString()).contains(BODY);
    }

    @Test
    void queuedProjectionIsScrubbedByNextRunAfterResultArrives() {
        String expired = insertDocument(null, now.minus(Duration.ofDays(31)));
        String queued = insertProjection(expired, "queued", null);

        worker.purge(now);
        assertThat(projection(queued).get("status")).isEqualTo("queued");

        // 늦게 온 결과가 본문을 다시 넣어도 다음 실행이 지운다.
        jdbc.update("UPDATE agent_apply_projections SET status = 'ready', ready_markdown = ? WHERE run_id = ?", BODY, queued);
        worker.purge(now);

        assertThat(projection(queued).get("ready_markdown")).isNull();
        assertThat(projection(queued).get("error_code")).isEqualTo("document_purged");
    }

    @Test
    void purgeRemovesJournalChangesSoRollbackCannotRestoreBody() {
        String expired = insertDocument(null, now.minus(Duration.ofDays(31)));
        String active = insertDocument(null, null);
        String run = insertRun("cancel_requested", "document", "{\"kind\":\"document\"}");
        String projectionRun = insertProjection(expired, "consumed", null);
        insertChange(run, "document_content_versions", "{\"document_id\":\"%s\",\"version\":1}".formatted(expired));
        insertChange(run, "document_edit_states", "{\"document_id\":\"%s\"}".formatted(expired));
        insertChange(run, "document_edit_writes", "{\"document_id\":\"%s\",\"write_id\":\"w\"}".formatted(expired));
        insertChange(run, "documents", "{\"id\":\"%s\"}".formatted(expired));
        insertChange(projectionRun, "agent_apply_projections", "{\"run_id\":\"%s\"}".formatted(projectionRun));
        insertChange(run, "document_content_versions", "{\"document_id\":\"%s\",\"version\":1}".formatted(active));
        insertChange(run, "documents", "{\"id\":\"%s\"}".formatted(active));

        worker.purge(now);

        assertThat(jdbc.queryForList("SELECT row_key->>'document_id' FROM ai_task_changes WHERE run_id = ? "
                + "AND table_name = 'document_content_versions'", String.class, run)).containsExactly(active);
        assertThat(jdbc.queryForList("SELECT row_key->>'id' FROM ai_task_changes WHERE run_id = ? "
                + "AND table_name = 'documents'", String.class, run)).containsExactly(active);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_changes WHERE run_id = ? AND table_name "
                + "IN ('document_edit_states', 'document_edit_writes')", Integer.class, run)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_changes WHERE run_id = ?",
                Integer.class, projectionRun)).isZero();
    }

    @Test
    void rolledBackPurgeKeepsCoreCopies() throws Exception {
        String expired = insertDocument(null, now.minus(Duration.ofDays(31)));
        String run = insertRun("completed", "agent", """
                {"kind":"agent","document_id":"%s","editor_snapshot":{"markdown":"%s"}}
                """.formatted(expired, BODY));
        String projectionRun = insertProjection(expired, "ready", null);
        insertChange(run, "document_edit_states", "{\"document_id\":\"%s\"}".formatted(expired));

        transactionTemplate.executeWithoutResult(status -> {
            worker.purge(now);
            status.setRollbackOnly();
        });

        assertThat(command(run).toString()).contains(BODY);
        assertThat(projection(projectionRun).get("ready_markdown")).isEqualTo(BODY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_changes WHERE run_id = ?", Integer.class, run)).isOne();
    }

    @Test
    void failedObjectDeletionLeavesNoCommand() {
        String expired = insertDocument(null, now.minus(Duration.ofDays(31)));
        StorageProperties missingBucket = new StorageProperties();
        missingBucket.setBucket("missing-" + UUID.randomUUID().toString().substring(0, 8));
        DocumentTrashPurgeWorker failing = new DocumentTrashPurgeWorker(namedJdbc, transactionTemplate,
                new DataPurgeService(jdbc, transactionTemplate, minio, missingBucket), ingestCommandOutbox, scrubber, Duration.ofDays(30));

        failing.purge(now);

        assertThat(purgedCommands(expired)).isEmpty();
    }

    @Test
    void failedObjectDeletionKeepsRowsForNextRun() throws Exception {
        String expired = insertDocument(null, now.minus(Duration.ofDays(31)));
        StorageProperties missingBucket = new StorageProperties();
        missingBucket.setBucket("missing-" + UUID.randomUUID().toString().substring(0, 8));
        DocumentTrashPurgeWorker failing = new DocumentTrashPurgeWorker(namedJdbc, transactionTemplate,
                new DataPurgeService(jdbc, transactionTemplate, minio, missingBucket), ingestCommandOutbox, scrubber, Duration.ofDays(30));

        failing.purge(now);
        assertThat(exists("documents", expired)).isTrue();
        stat(sourceKey(expired));

        worker.purge(now);
        assertThat(exists("documents", expired)).isFalse();
        assertThatThrownBy(() -> stat(sourceKey(expired))).isNotNull();
    }

    @Test
    void concurrentRunsDeleteEachDocumentOnce() {
        for (int i = 0; i < 5; i++) {
            insertDocument(null, now.minus(Duration.ofDays(40)));
        }
        int expired = expiredCount();
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> worker.purge(now));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> worker.purge(now));

        assertThat(first.join() + second.join()).isEqualTo(expired);
        assertThat(expiredCount()).isZero();
    }

    @Test
    void trashListShowsPurgeTime() {
        Instant deletedAt = now.minus(Duration.ofDays(3));
        insertDocument(null, deletedAt);
        redis.opsForValue().set("authz:role:v2:" + workspace + ":" + user, "{\"role\":\"OWNER\",\"permissions\":[]}");

        assertThat(documentService.trash(workspace, user).documents())
                .singleElement()
                .satisfies(item -> assertThat(item.purgeAt()).isEqualTo(item.deletedAt().plus(Duration.ofDays(30))));
    }

    /** 다른 테스트가 남긴 휴지통 문서도 기준 시각으로는 만료이므로 함께 센다. */
    private String insertRun(String status, String kind, String command) {
        String id = "run_" + UUID.randomUUID();
        jdbc.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind, status, command) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))",
                id, workspace, user, kind, status, command);
        return id;
    }

    private String insertProjection(String documentId, String status, String result) {
        String id = "run_" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_apply_projections(run_id, workspace_id, user_id, document_id, base_version,
                    apply_operation_id, status, result, ready_markdown)
                VALUES (?, ?, ?, ?, 1, ?, ?, CAST(? AS jsonb), ?)
                """, id, workspace, user, documentId, "apply_" + id, status, result,
                "queued".equals(status) ? null : BODY);
        jdbc.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind, status) VALUES (?, ?, ?, 'agent', 'completed')",
                id, workspace, user);
        return id;
    }

    private void insertChange(String run, String table, String key) {
        jdbc.update("INSERT INTO ai_task_changes(run_id, table_name, row_key, before_value) "
                + "VALUES (?, ?, CAST(? AS jsonb), CAST(? AS jsonb))", run, table, key, "{\"markdown\":\"" + BODY + "\"}");
    }

    private JsonNode command(String run) throws Exception {
        return new ObjectMapper().readTree(jdbc.queryForObject(
                "SELECT command::text FROM ai_task_runs WHERE id = ?", String.class, run));
    }

    private Map<String, Object> projection(String run) {
        return jdbc.queryForMap("SELECT status, error_code, ready_markdown, result::text AS result "
                + "FROM agent_apply_projections WHERE run_id = ?", run);
    }

    private java.util.List<String> purgedCommands(String documentId) {
        return jdbc.queryForList("""
                SELECT payload FROM ai_command_outbox
                WHERE payload::jsonb->>'kind' = 'document_purged' AND payload::jsonb->>'document_id' = ?
                """, String.class, documentId);
    }

    private int expiredCount() {
        return jdbc.queryForObject("SELECT count(*) FROM documents WHERE deleted_at <= ?", Integer.class,
                Timestamp.from(now.minus(Duration.ofDays(30))));
    }

    private UUID insertFolder(Instant deletedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO folders(id, workspace_id, parent_folder_id, name, sort_order, current_version,
                    created_at, updated_at, deleted_at)
                VALUES (?, ?, NULL, ?, 0, 1, now(), now(), ?)
                """, id, workspace, "폴더-" + id, timestamp(deletedAt));
        return id;
    }

    private String insertDocument(UUID folder, Instant deletedAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO documents(
                    id, byte_size, content_hash, filename, display_name, normalized_filename,
                    mime_type, source_uri, status, uploaded_at, updated_at, user_id, workspace_id,
                    current_content_hash, current_version, document_role, sort_order, folder_id, deleted_at, deleted_by
                ) VALUES (?, 1, 'hash', ?, 'a', ?, 'application/pdf', ?, 'completed', now(), now(), ?, ?,
                          'hash', 1, 'ORIGINAL', 0, ?, ?, ?)
                """, id, id + ".pdf", id + ".pdf", "s3://" + storage.getBucket() + "/" + sourceKey(id), user, workspace,
                folder, timestamp(deletedAt), deletedAt == null ? null : user);
        try {
            minio.putObject(PutObjectArgs.builder().bucket(storage.getBucket()).object(sourceKey(id))
                    .stream(new ByteArrayInputStream(new byte[]{1}), 1, -1).build());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    private UUID insertAsset(String documentId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document_assets(id, workspace_id, uploaded_by, original_filename, content_type, byte_size,
                    width, height, content_hash, storage_key, created_at)
                VALUES (?, ?, ?, 'a.png', 'image/png', 1, 1, 1, 'hash', ?, now())
                """, id, workspace, user, "assets/" + workspace + "/" + id + "/content");
        jdbc.update("INSERT INTO document_asset_references(document_id, asset_id, created_at) VALUES (?, ?, now())",
                documentId, id);
        return id;
    }

    private boolean exists(String table, String id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id::text = ?", Integer.class, id) == 1;
    }

    private Timestamp unreferencedSince(UUID assetId) {
        return jdbc.queryForObject("SELECT unreferenced_since FROM document_assets WHERE id = ?", Timestamp.class, assetId);
    }

    private void stat(String key) throws Exception {
        minio.statObject(StatObjectArgs.builder().bucket(storage.getBucket()).object(key).build());
    }

    private static String sourceKey(String documentId) {
        return "sources/documents/" + documentId + "/original";
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
