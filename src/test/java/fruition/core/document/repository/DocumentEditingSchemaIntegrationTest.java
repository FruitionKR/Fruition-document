package fruition.core.document.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.dto.DocumentContentSaveResponse;
import fruition.core.document.dto.DocumentDuplicateResponse;
import fruition.core.document.dto.DocumentExportResult;
import fruition.core.document.dto.DocumentLifecycleRequest;
import fruition.core.document.service.DocumentAssetStorageCoordinator;
import fruition.core.document.service.DocumentAssetValidator;
import fruition.core.document.service.DocumentExportService;
import fruition.core.document.service.DocumentService;
import fruition.core.agent.dto.AgentToolExecuteRequest;
import fruition.core.agent.repository.PipelineAgentToolAuthorizationClient;
import fruition.core.agent.service.AgentToolService;
import fruition.core.agent.repository.AgentRunCommandRepository;
import fruition.core.aihistory.service.AgentApplyOperationStore;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.service.DocumentRestoreApplier;
import fruition.core.aihistory.service.OperationRecorder;
import fruition.core.aihistory.service.PreviewTokenSigner;
import fruition.core.aihistory.service.RestoreExecuteService;
import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.dto.DocumentRestorePlan;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.document.dto.DocumentRenameResponse;
import fruition.shared.idempotency.IdempotencyService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DocumentEditingSchemaIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    PostgreSQLContainer<?> postgresContainer;

    @Autowired
    DocumentRepository documentRepository;

    @Autowired
    DocumentService documentService;

    @Autowired
    AgentToolService agentToolService;

    @MockBean
    PipelineAgentToolAuthorizationClient agentToolAuthorizationClient;

    @SpyBean
    IdempotencyService idempotencyService;

    @Autowired
    DocumentExportService documentExportService;

    @Autowired
    AgentRunCommandRepository agentRunCommandRepository;

    @Autowired
    AiCommandOutboxWriter aiCommandOutboxWriter;

    @Autowired
    AgentApplyOperationStore agentApplyOperationStore;

    @SpyBean
    OperationRecorder operationRecorder;

    @SpyBean
    OperationChangeRepository operationChangeRepository;

    @SpyBean
    DocumentRestoreApplier documentRestoreApplier;

    @Autowired
    RestoreExecuteService restoreExecuteService;

    @Autowired
    PreviewTokenSigner previewTokenSigner;

    @Autowired
    OperationLogRepository operationLogRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetSpies() {
        reset(operationRecorder, operationChangeRepository);
        reset(documentRestoreApplier);
        reset(idempotencyService);
    }

    @Test
    void agentRenameSuccessReplaysTheCompletedReceipt() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String userId = "user_agent_rename_ok_" + suffix;
        String workspaceId = "ws_agent_rename_ok_" + suffix;
        String documentId = "doc_agent_rename_ok_" + suffix;
        String idempotencyKey = "idem_agent_rename_ok_" + suffix;
        insertWorkspaceMember(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "기존.md", "hash", "EDITABLE");

        AgentToolExecuteRequest request = agentRenameRequest(
                workspaceId, userId, documentId, idempotencyKey, "새 이름");
        DocumentRenameResponse first = (DocumentRenameResponse) agentToolService.execute(
                "rename_document", request);

        assertThat(first.filename()).isEqualTo("새 이름.md");
        assertThat(first.displayName()).isEqualTo("새 이름");
        assertThat(first.currentVersion()).isEqualTo(2L);
        assertThat(agentToolService.execute("rename_document", request)).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT filename FROM documents WHERE id = ?", String.class, documentId))
                .isEqualTo("새 이름.md");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_name FROM documents WHERE id = ?", String.class, documentId))
                .isEqualTo("새 이름");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_version FROM documents WHERE id = ?", Long.class, documentId))
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE user_id = ? "
                        + "AND endpoint_scope = ? AND idempotency_key = ? AND status = 'COMPLETED'",
                Integer.class, userId,
                "POST:/internal/agent/tools/execute/rename_document", idempotencyKey))
                .isEqualTo(1);
    }

    @Test
    void agentRenameRollsBackDocumentAndReceiptWhenCompletionSaveFails() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String userId = "user_agent_rename_fail_" + suffix;
        String workspaceId = "ws_agent_rename_fail_" + suffix;
        String documentId = "doc_agent_rename_fail_" + suffix;
        String idempotencyKey = "idem_agent_rename_fail_" + suffix;
        insertWorkspaceMember(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "기존.md", "hash", "EDITABLE");

        doThrow(new IllegalStateException("late idempotency save failure"))
                .when(idempotencyService).save(
                        eq(userId),
                        eq("POST:/internal/agent/tools/execute/rename_document"),
                        eq(idempotencyKey),
                        anyString(),
                        eq(200),
                        eq(documentId),
                        any(DocumentRenameResponse.class));

        assertThatThrownBy(() -> agentToolService.execute(
                "rename_document", agentRenameRequest(
                        workspaceId, userId, documentId, idempotencyKey, "새 이름")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("late idempotency save failure");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT filename FROM documents WHERE id = ?", String.class, documentId))
                .isEqualTo("기존.md");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_name FROM documents WHERE id = ?", String.class, documentId))
                .isEqualTo("기존");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_version FROM documents WHERE id = ?", Long.class, documentId))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE user_id = ? "
                        + "AND endpoint_scope = ? AND idempotency_key = ?",
                Integer.class, userId,
                "POST:/internal/agent/tools/execute/rename_document", idempotencyKey))
                .isZero();
    }

    @Test
    void saveContentRequiresNewTransactionSurvivesOuterRollback() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String userId = "user_sync_off_" + suffix;
        String workspaceId = "ws_sync_off_" + suffix;
        String documentId = "doc_sync_off_" + suffix;
        insertWorkspaceMember(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "문서.md", "old-hash", "EDITABLE");
        jdbcTemplate.update("""
                INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at)
                VALUES (?, '기존', 'old-hash', 1, now(), now())
                """, documentId);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            documentService.saveContent(workspaceId, userId, documentId,
                    "변경", 1L, "write-sync-off-" + suffix, "manual");
            status.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT revision FROM document_edit_states WHERE document_id = ?", Long.class, documentId))
                .isEqualTo(2L);
    }

    @Test
    void restoreRollsBackBodyAndReceiptWhenOperationChangeFailsLate() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String userId = "user_restore_fail_" + suffix;
        String workspaceId = "ws_restore_fail_" + suffix;
        String documentId = "doc_restore_fail_" + suffix;
        String restoreOperationId = "op_restore_fail_" + suffix;
        insertWorkspaceMember(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "문서.md", "current-hash", "EDITABLE");
        jdbcTemplate.update("""
                INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at)
                VALUES (?, '현재 본문', 'current-hash', 1, now(), now())
                """, documentId);
        jdbcTemplate.update("""
                INSERT INTO document_content_versions(document_id, version, markdown, content_hash, created_by, created_at)
                VALUES (?, 1, '복원 본문', 'restore-hash', ?, now())
                """, documentId, userId);
        doThrow(new IllegalStateException("late operation change failure"))
                .when(operationChangeRepository).save(any());

        OperationLog restore = OperationLog.applying(
                restoreOperationId, workspaceId, userId, documentId, null, "{}", Instant.now());
        assertThatThrownBy(() -> documentRestoreApplier.apply(
                restore, new fruition.core.aihistory.dto.DocumentRestorePlan(documentId, 1, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("late operation change failure");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT markdown FROM document_edit_states WHERE document_id = ?", String.class, documentId))
                .isEqualTo("현재 본문");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revision FROM document_edit_states WHERE document_id = ?", Long.class, documentId))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_writes WHERE document_id = ?", Integer.class, documentId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_outbox WHERE document_id = ?", Integer.class, documentId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_content_versions WHERE document_id = ?", Integer.class, documentId))
                .isEqualTo(1);
    }

    @Test
    void restoreSuccessWritesOneOperationChangeAndOneReceipt() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String userId = "user_restore_ok_" + suffix;
        String workspaceId = "ws_restore_ok_" + suffix;
        String documentId = "doc_restore_ok_" + suffix;
        String restoreOperationId = "op_restore_ok_" + suffix;
        insertWorkspaceMember(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "문서.md", "current-hash", "EDITABLE");
        jdbcTemplate.update("""
                INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at)
                VALUES (?, '현재 본문', 'current-hash', 1, now(), now())
                """, documentId);
        jdbcTemplate.update("""
                INSERT INTO document_content_versions(document_id, version, markdown, content_hash, created_by, created_at)
                VALUES (?, 1, '복원 본문', 'restore-hash', ?, now())
                """, documentId, userId);
        jdbcTemplate.update("""
                INSERT INTO ai_operation_logs(
                    operation_id, workspace_id, user_id, operation_type, target_document_id,
                    status, changed_resource_count, created_at
                ) VALUES (?, ?, ?, 'restore', ?, 'applying', 0, now())
                """, restoreOperationId, workspaceId, userId, documentId);

        OperationLog restore = OperationLog.applying(
                restoreOperationId, workspaceId, userId, documentId, null, "{}", Instant.now());
        long version = documentRestoreApplier.apply(
                restore, new fruition.core.aihistory.dto.DocumentRestorePlan(documentId, 1, 1));

        assertThat(version).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revision FROM document_edit_states WHERE document_id = ?", Long.class, documentId))
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_writes WHERE document_id = ?", Integer.class, documentId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_outbox WHERE document_id = ?", Integer.class, documentId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_operation_changes WHERE operation_id = ?", Integer.class, restoreOperationId))
                .isEqualTo(1);
        verify(operationChangeRepository, times(1)).save(any());
    }

    @Test
    void documentRestoreRetriesClaimAndApplyAsOneTransaction() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String userId = "user_restore_retry_" + suffix;
        String workspaceId = "ws_restore_retry_" + suffix;
        String documentId = "doc_restore_retry_" + suffix;
        String targetOperationId = "op_restore_target_" + suffix;
        insertWorkspaceMember(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "문서.md", "current-hash", "EDITABLE");
        jdbcTemplate.update("""
                UPDATE documents
                SET current_version = 2, current_content_hash = 'current-hash'
                WHERE id = ?
                """, documentId);
        jdbcTemplate.update("""
                INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at)
                VALUES (?, '현재 본문', 'current-hash', 2, now(), now())
                """, documentId);
        jdbcTemplate.update("""
                INSERT INTO document_content_versions(document_id, version, markdown, content_hash, created_by, created_at)
                VALUES (?, 1, '복원 본문', 'restore-hash', ?, now()),
                       (?, 2, '현재 본문', 'current-hash', ?, now())
                """, documentId, userId, documentId, userId);
        OperationLog target = OperationLog.completed(
                targetOperationId, workspaceId, userId, fruition.core.aihistory.domain.OperationType.document_edit,
                documentId, "편집", 1, Instant.now());
        operationLogRepository.save(target);
        operationChangeRepository.save(new OperationChange(
                targetOperationId, ResourceType.document, documentId, 1L, 2L,
                ChangeType.updated, "편집", null, null));

        DocumentRestorePlan plan = new DocumentRestorePlan(documentId, 2, 1);
        String previewToken = previewTokenSigner.sign(targetOperationId, plan);
        AtomicBoolean injectTransientFailure = new AtomicBoolean(true);
        AtomicInteger applyInvocations = new AtomicInteger();
        doAnswer(invocation -> {
            Object applied = invocation.callRealMethod();
            applyInvocations.incrementAndGet();
            if (injectTransientFailure.getAndSet(false)) {
                throw new org.springframework.dao.DuplicateKeyException("injected restore failure");
            }
            return applied;
        }).when(documentRestoreApplier).apply(any(), any());

        var response = restoreExecuteService.execute(workspaceId, userId, targetOperationId, previewToken);

        assertThat(response.status()).isEqualTo("succeeded");
        assertThat(applyInvocations).hasValue(2);
        verify(documentRestoreApplier, times(2)).apply(any(), any());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_operation_logs WHERE restored_from = ?",
                Integer.class, targetOperationId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_operation_logs WHERE restored_from = ? AND status = 'succeeded'",
                Integer.class, targetOperationId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_operation_logs WHERE restored_from = ? "
                        + "AND status IN ('applying', 'failed')",
                Integer.class, targetOperationId)).isZero();
        String restoreOperationId = jdbcTemplate.queryForObject(
                "SELECT operation_id FROM ai_operation_logs WHERE restored_from = ? AND status = 'succeeded'",
                String.class, targetOperationId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_operation_changes WHERE operation_id = ? AND change_type = 'restored'",
                Integer.class, restoreOperationId)).isEqualTo(1);
        Map<String, Object> restoredChange = jdbcTemplate.queryForMap("""
                SELECT resource_type, resource_id, before_revision, after_revision, change_type
                FROM ai_operation_changes
                WHERE operation_id = ? AND change_type = 'restored'
                """, restoreOperationId);
        assertThat(restoredChange)
                .containsEntry("resource_type", "document")
                .containsEntry("resource_id", documentId)
                .containsEntry("before_revision", 2L)
                .containsEntry("after_revision", 3L)
                .containsEntry("change_type", "restored");
        String restoredHash = jdbcTemplate.queryForObject(
                "SELECT content_hash FROM document_edit_states WHERE document_id = ?",
                String.class, documentId);
        assertThat(restoredHash).matches("[0-9a-f]{64}");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT markdown FROM document_edit_states WHERE document_id = ?", String.class, documentId))
                .isEqualTo("복원 본문");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revision FROM document_edit_states WHERE document_id = ?", Long.class, documentId))
                .isEqualTo(3L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_version FROM documents WHERE id = ?", Long.class, documentId))
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_content_hash FROM documents WHERE id = ?", String.class, documentId))
                .isEqualTo(restoredHash);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_writes WHERE document_id = ? "
                        + "AND revision_write_id = ?", Integer.class, documentId,
                "op-restore:" + restoreOperationId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_outbox WHERE document_id = ? AND revision = 3",
                Integer.class, documentId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_content_versions WHERE document_id = ?", Integer.class, documentId))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT markdown FROM document_content_versions
                WHERE document_id = ? AND version = 3
                """, String.class, documentId)).isEqualTo("복원 본문");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT content_hash FROM document_content_versions
                WHERE document_id = ? AND version = 3
                """, String.class, documentId)).isEqualTo(restoredHash);
    }

    @Test
    void saveWithAssetsFromOuterTransactionRetriesFreshAndCommitsExactlyOnce() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String userId = "user_tx_" + suffix;
        String workspaceId = "ws_tx_" + suffix;
        String documentId = "doc_tx_" + suffix;
        String operationId = "op_tx_" + suffix;
        String writeId = "write_tx_" + suffix;
        UUID assetId = UUID.randomUUID();
        String markdown = "![이미지](/api/workspaces/" + workspaceId + "/assets/"
                + assetId + "/content)\n변경";
        insertWorkspaceMember(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "문서.md", "old-hash", "EDITABLE");
        jdbcTemplate.update("""
                INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at)
                VALUES (?, '기존', 'old-hash', 1, now(), now())
                """, documentId);
        agentRunCommandRepository.prepareToolApply(
                "agent-tool-" + suffix, workspaceId, userId, documentId, 1,
                operationId, markdown);

        byte[] bytes = {1, 2, 3, 4};
        DocumentAssetValidator.ValidatedAsset validated = new DocumentAssetValidator.ValidatedAsset(
                "diagram.png", "image/png", bytes, 1, 1, "a".repeat(64));
        DocumentAssetStorageCoordinator.StoredAsset stored =
                new DocumentAssetStorageCoordinator.StoredAsset(
                        assetId, "assets/" + assetId, validated);
        AtomicBoolean injectTransientFailure = new AtomicBoolean(true);
        AtomicInteger recordDocumentEditInvocations = new AtomicInteger();
        doAnswer(invocation -> {
            recordDocumentEditInvocations.incrementAndGet();
            if (injectTransientFailure.getAndSet(false)) {
                throw new org.springframework.dao.DuplicateKeyException("injected transient failure");
            }
            return invocation.callRealMethod();
        }).when(operationRecorder).recordDocumentEdit(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong(),
                anyString(), anyString(), any(Instant.class));

        DocumentContentSaveResponse response = new TransactionTemplate(transactionManager)
                .execute(status -> documentService.saveContentWithAssets(
                        workspaceId, userId, documentId, markdown, 1,
                        writeId, Map.of(UUID.randomUUID(), stored), operationId));

        assertThat(response.changed()).isTrue();
        assertThat(recordDocumentEditInvocations).hasValue(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revision FROM document_edit_states WHERE document_id = ?", Long.class, documentId))
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_content_hash FROM documents WHERE id = ?", String.class, documentId))
                .isEqualTo(response.contentHash());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_writes WHERE document_id = ?", Integer.class, documentId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_content_versions WHERE document_id = ?", Integer.class, documentId))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_assets WHERE id = ?", Integer.class, assetId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_asset_references WHERE document_id = ?", Integer.class, documentId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM agent_apply_projections WHERE apply_operation_id = ?", String.class, operationId))
                .isEqualTo("consumed");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_operation_logs WHERE operation_id = ? AND status = 'succeeded'",
                Integer.class, operationId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_operation_changes WHERE operation_id = ?", Integer.class, operationId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_outbox WHERE document_id = ?", Integer.class, documentId))
                .isEqualTo(1);
    }

    @Test
    void conflictAuditAndTokenCommitIndependentlyWhenOuterTransactionRollsBack() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String userId = "user_conflict_" + suffix;
        String workspaceId = "ws_conflict_" + suffix;
        String documentId = "doc_conflict_" + suffix;
        String operationId = "op_conflict_" + suffix;
        String writeId = "write_conflict_" + suffix;
        String markerKey = "outer-marker-" + suffix;
        insertWorkspaceMember(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "문서.md", "old-hash", "EDITABLE");
        jdbcTemplate.update("""
                INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at)
                VALUES (?, '기존', 'old-hash', 1, now(), now())
                """, documentId);
        agentRunCommandRepository.prepareToolApply(
                "agent-tool-conflict-" + suffix, workspaceId, userId, documentId, 2,
                operationId, "충돌 본문");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO idempotency_records(
                        id, user_id, endpoint_scope, idempotency_key, request_hash,
                        status, claim_token, response_status, created_at, expires_at
                    ) VALUES (?, ?, 'outer-test', ?, ?, 'IN_PROGRESS', ?, NULL, now(), now() + interval '1 day')
                    """, UUID.randomUUID(), userId, markerKey, "marker-hash", UUID.randomUUID());
            assertThatThrownBy(() -> documentService.saveContent(
                    workspaceId, userId, documentId, "충돌 본문", 2L,
                    writeId, "agent", operationId))
                    .isInstanceOf(fruition.core.document.exception.DocumentVersionConflictException.class);
            status.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?", Integer.class, markerKey))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM agent_apply_projections WHERE apply_operation_id = ?", String.class, operationId))
                .isEqualTo("consumed");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_operation_logs WHERE operation_id = ?", String.class, operationId))
                .isEqualTo("conflict");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revision FROM document_edit_states WHERE document_id = ?", Long.class, documentId))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_writes WHERE document_id = ?", Integer.class, documentId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_outbox WHERE document_id = ?", Integer.class, documentId))
                .isZero();
    }

    @Test
    void agentReservationAndOutboxRollbackTogether() {
        String runId = "agent_" + UUID.randomUUID().toString().replace("-", "");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            agentRunCommandRepository.create(runId, "ws-1", "user-1", "doc-1", 1L, "op-1");
            aiCommandOutboxWriter.enqueue(runId, "ai.agent.command", "doc-1", Map.of("run_id", runId));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM agent_apply_projections WHERE run_id = ?", Integer.class, runId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_command_outbox WHERE run_id = ?", Integer.class, runId))
                .isZero();
    }

    @Test
    void agentApplyProjectionCanBeConsumedOnlyOnceWhenReady() {
        String runId = "agent_" + UUID.randomUUID().toString().replace("-", "");
        String operationId = "op_" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind) VALUES (?, 'ws-1', 'user-1', 'agent')", runId);
        jdbcTemplate.update("""
                INSERT INTO agent_apply_projections (
                    run_id, workspace_id, user_id, document_id, base_version,
                    apply_operation_id, status
                ) VALUES (?, 'ws-1', 'user-1', 'doc-1', 1, ?, 'ready')
                """, runId, operationId);

        assertThat(agentApplyOperationStore.consume(operationId, "user-1", "doc-1", "write-1")).isTrue();
        assertThat(agentApplyOperationStore.consume(operationId, "user-1", "doc-1", "write-1")).isTrue();
        assertThat(agentApplyOperationStore.consume(operationId, "user-1", "doc-1", "write-2")).isFalse();
        assertThat(agentApplyOperationStore.consume(operationId, "user-2", "doc-1", "write-1")).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT apply_revision_write_id FROM agent_apply_projections WHERE run_id = ?",
                String.class, runId)).isEqualTo("write-1");
    }

    @Test
    void agentRouteOutcomesLabelsOnlyTrustedSignals() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String sessionId = "session_" + suffix;
        String acceptedRunId = "agent_accepted_" + suffix;
        String failedRunId = "agent_failed_" + suffix;
        String unlabeledRunId = "agent_unlabeled_" + suffix;
        jdbcTemplate.update("""
                INSERT INTO chat_sessions(id, created_at, user_id, workspace_id)
                VALUES (?, now(), 'user-1', 'ws-1')
                """, sessionId);
        for (String runId : List.of(acceptedRunId, failedRunId, unlabeledRunId)) {
            String pairId = "pair_" + runId;
            jdbcTemplate.update("""
                    INSERT INTO chat_messages(id, content, created_at, pair_id, role, status, session_id)
                    VALUES (?, '라우팅 질문', now(), ?, 'user', 'completed', ?),
                           (?, '응답', now(), ?, 'assistant', 'completed', ?)
                    """, "user_" + runId, pairId, sessionId,
                    "assistant_" + runId, pairId, sessionId);
            jdbcTemplate.update(
                    "UPDATE chat_messages SET run_id = ? WHERE id = ?",
                    runId, "assistant_" + runId);
        }
        jdbcTemplate.update("""
                INSERT INTO agent_apply_projections(
                    run_id, workspace_id, user_id, status, result, updated_at, apply_consumed_at
                ) VALUES
                    (?, 'ws-1', 'user-1', 'consumed', '{"route":{"action":"markdown_edit"}}', now(), now()),
                    (?, 'ws-1', 'user-1', 'failed', NULL, now(), NULL),
                    (?, 'ws-1', 'user-1', 'ready', '{"route":{"action":"chat_answer"}}', now(), NULL)
                """, acceptedRunId, failedRunId, unlabeledRunId);

        assertThat(jdbcTemplate.queryForList("""
                SELECT run_id, request_text, COALESCE(route ->> 'action', '') AS action, outcome_label
                FROM agent_route_outcomes
                WHERE run_id IN (?, ?, ?)
                ORDER BY run_id
                """, acceptedRunId, failedRunId, unlabeledRunId)).containsExactly(
                Map.of("run_id", acceptedRunId, "request_text", "라우팅 질문",
                        "action", "markdown_edit", "outcome_label", "accepted"),
                Map.of("run_id", failedRunId, "request_text", "라우팅 질문",
                        "action", "", "outcome_label", "technical_failure"),
                Map.of("run_id", unlabeledRunId, "request_text", "라우팅 질문",
                        "action", "chat_answer", "outcome_label", "unlabeled")
        );
    }

    @Test
    void migration_createsDocumentEditingFoundation() {
        List<String> columns = jdbcTemplate.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'documents'
                """,
                String.class
        );

        assertThat(columns).contains(
                "display_name",
                "normalized_filename",
                "source_document_id",
                "current_content_hash",
                "current_version",
                "document_role",
                "folder_id",
                "sort_order",
                "updated_at",
                "deleted_at",
                "deleted_by",
                "delete_operation_id"
        );
        assertThat(columns).doesNotContain("parent_document_id", "source_folder_id");

        for (String table : List.of("document_edit_states", "folders", "idempotency_records")) {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?) IS NOT NULL",
                    Boolean.class,
                    "public." + table
            );
            assertThat(exists).as(table).isTrue();
        }
    }

    @Test
    void v39EditStorageConstraintsAndPendingIndexArePresent() {
        String suffix = UUID.randomUUID().toString();
        String documentId = "doc_v39_" + suffix;
        String workspaceId = "ws_v39_" + suffix;
        String userId = "user_v39_" + suffix;
        insertDocument(documentId, workspaceId, userId, "v39.md", "hash-v39", "EDITABLE");
        String invalidDocumentId = "doc_v39_invalid_" + suffix;
        insertDocument(invalidDocumentId, workspaceId, userId, "invalid.md", "hash-invalid", "EDITABLE");

        jdbcTemplate.update("""
                INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at)
                VALUES (?, '본문', 'hash-v39', 1, now(), now())
                """, documentId);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at)
                VALUES (?, '잘못된 revision', 'hash-v39', 0, now(), now())
                """, invalidDocumentId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO document_edit_writes(
                    document_id, revision_write_id, request_hash, result_revision,
                    result_content_hash, result_updated_at, actor_user_id, changed, created_at
                ) VALUES (?, 'write-v39-invalid-revision', ?, 0, ?, now(), ?, true, now())
                """, documentId, "a".repeat(64), "b".repeat(64), userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO document_edit_outbox(
                    event_id, document_id, workspace_id, revision, content_hash,
                    event_type, schema_version, created_at, published
                ) VALUES ('event-v39-invalid-revision', ?, ?, 0, ?, 'document.edit.saved.v1', 1, now(), false)
                """, documentId, workspaceId, "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO document_edit_writes(
                    document_id, revision_write_id, request_hash, result_revision,
                    result_content_hash, result_updated_at, actor_user_id, changed, created_at
                ) VALUES (?, 'write-v39', ?, 1, ?, now(), ?, true, now())
                """, "doc_v39_missing_" + suffix, "a".repeat(64), "b".repeat(64), userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update("""
                INSERT INTO document_edit_outbox(
                    event_id, document_id, workspace_id, revision, content_hash,
                    event_type, schema_version, created_at, published
                ) VALUES (?, ?, ?, 1, ?, 'document.edit.saved.v1', 1, now(), false)
                """, "event-v39-pending-" + suffix, documentId, workspaceId, "b".repeat(64));
        jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_outbox WHERE event_id = ? AND document_id = ? AND published = false",
                Integer.class, "event-v39-pending-" + suffix, documentId)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT indexname FROM pg_indexes WHERE indexname = 'idx_document_edit_outbox_pending'",
                String.class)).isEqualTo("idx_document_edit_outbox_pending");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT conname FROM pg_constraint WHERE conname = 'document_edit_states_revision_positive'",
                String.class)).isEqualTo("document_edit_states_revision_positive");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT conname FROM pg_constraint WHERE conname = 'fk_document_edit_writes_document'",
                String.class)).isEqualTo("fk_document_edit_writes_document");
    }

    @Test
    void v39BlocksOnlyExistingDocumentEditRestore() throws Exception {
        String databaseName = "v39_restore_guard_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(
                postgresContainer.getJdbcUrl(), postgresContainer.getUsername(), postgresContainer.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }

        String databaseUrl = "jdbc:postgresql://" + postgresContainer.getHost() + ":"
                + postgresContainer.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + databaseName;
        Flyway.configure()
                .dataSource(databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword())
                .target(MigrationVersion.fromVersion("38"))
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(
                databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(v34DocumentInsert("doc_v39_restore_guard", null));
            statement.executeUpdate("""
                    INSERT INTO ai_operation_logs(
                        operation_id, workspace_id, user_id, operation_type, target_document_id,
                        status, changed_resource_count, created_at
                    ) VALUES
                        ('op_v39_old_edit', 'ws_v39', 'user_v39', 'document_edit', 'doc_v39_restore_guard', 'succeeded', 1, now()),
                        ('op_v39_old_ingest', 'ws_v39', 'user_v39', 'ingest', NULL, 'succeeded', 1, now())
                    """);
        }

        Flyway.configure()
                .dataSource(databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword())
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(
                databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO ai_operation_logs(
                        operation_id, workspace_id, user_id, operation_type, target_document_id,
                        status, changed_resource_count, created_at
                    ) VALUES ('op_v39_new_edit', 'ws_v39', 'user_v39', 'document_edit',
                              'doc_v39_restore_guard', 'succeeded', 1, now())
                    """);
            try (ResultSet rows = statement.executeQuery("""
                    SELECT operation_id, document_restore_blocked
                    FROM ai_operation_logs
                    WHERE operation_id IN ('op_v39_old_edit', 'op_v39_old_ingest', 'op_v39_new_edit')
                    ORDER BY operation_id
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("operation_id")).isEqualTo("op_v39_new_edit");
                assertThat(rows.getBoolean("document_restore_blocked")).isFalse();
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("operation_id")).isEqualTo("op_v39_old_edit");
                assertThat(rows.getBoolean("document_restore_blocked")).isTrue();
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("operation_id")).isEqualTo("op_v39_old_ingest");
                assertThat(rows.getBoolean("document_restore_blocked")).isFalse();
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void v39RefusesNonEmptyEditStateTableBeforeCutover() throws Exception {
        String databaseName = "v39_guard_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(
                postgresContainer.getJdbcUrl(), postgresContainer.getUsername(), postgresContainer.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }

        String databaseUrl = "jdbc:postgresql://" + postgresContainer.getHost() + ":"
                + postgresContainer.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + databaseName;
        Flyway.configure()
                .dataSource(databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword())
                .target(MigrationVersion.fromVersion("38"))
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(
                databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(v34DocumentInsert("doc_v39_guard", null));
            statement.executeUpdate("""
                    INSERT INTO document_edit_states(document_id, markdown, content_hash, created_at, updated_at)
                    VALUES ('doc_v39_guard', '기존 본문', 'guard-hash', now(), now())
                    """);
        }

        assertThatThrownBy(() -> Flyway.configure()
                .dataSource(databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword())
                .load()
                .migrate()).isInstanceOf(Exception.class);
    }

    @Test
    void v39RefusesNonEmptyContentVersionsTableBeforeCutover() throws Exception {
        String databaseName = "v39_content_versions_guard_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(
                postgresContainer.getJdbcUrl(), postgresContainer.getUsername(), postgresContainer.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }

        String databaseUrl = "jdbc:postgresql://" + postgresContainer.getHost() + ":"
                + postgresContainer.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + databaseName;
        Flyway.configure()
                .dataSource(databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword())
                .target(MigrationVersion.fromVersion("38"))
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(
                databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(v34DocumentInsert("doc_v39_content_versions_guard", null));
            statement.executeUpdate("""
                    INSERT INTO document_content_versions(document_id, version, markdown, content_hash, created_at)
                    VALUES ('doc_v39_content_versions_guard', 1, '기존 본문', 'guard-hash', now())
                    """);
        }

        assertThatThrownBy(() -> Flyway.configure()
                .dataSource(databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword())
                .load()
                .migrate()).isInstanceOf(Exception.class);
    }

    @Test
    void v34SoftDeletesDuplicateAndPreservesReferencesAndChildren() throws Exception {
        String databaseName = "v34_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(
                postgresContainer.getJdbcUrl(), postgresContainer.getUsername(), postgresContainer.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }

        String databaseUrl = "jdbc:postgresql://" + postgresContainer.getHost() + ":"
                + postgresContainer.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + databaseName;
        Flyway.configure()
                .dataSource(databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword())
                .target(MigrationVersion.fromVersion("33"))
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(v34DocumentInsert("doc_v34_canonical", null));
            statement.executeUpdate(v34DocumentInsert("doc_v34_duplicate", "doc_v34_canonical"));
            statement.executeUpdate("INSERT INTO document_content_versions(document_id, version, markdown, content_hash, created_at) "
                    + "VALUES ('doc_v34_canonical', 1, 'canonical', 'hash-v34', now()), "
                    + "('doc_v34_duplicate', 1, 'duplicate', 'hash-v34', now())");
        }

        Flyway.configure()
                .dataSource(databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword())
                .target(MigrationVersion.fromVersion("38"))
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword());
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT id, deleted_at, source_document_id FROM documents ORDER BY id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("id")).isEqualTo("doc_v34_canonical");
                assertThat(rows.getObject("deleted_at")).isNull();
                assertThat(rows.getString("source_document_id")).isNull();
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("id")).isEqualTo("doc_v34_duplicate");
                assertThat(rows.getObject("deleted_at")).isNotNull();
                assertThat(rows.getString("source_document_id")).isEqualTo("doc_v34_canonical");
            }
            try (ResultSet count = statement.executeQuery("SELECT count(*) FROM document_edit_states")) {
                count.next();
                assertThat(count.getInt(1)).isZero();
            }
            try (ResultSet count = statement.executeQuery("SELECT count(*) FROM document_content_versions")) {
                count.next();
                assertThat(count.getInt(1)).isEqualTo(2);
            }
            assertThatThrownBy(() -> statement.executeUpdate(v34DocumentInsert("doc_v34_new", null)))
                    .isInstanceOf(Exception.class);
        }
    }

    @Test
    void migration_doesNotCreateAccessOwnedTables() {
        // users/workspaces 등은 access_db 소유 — core migration이 만들지 않아야 한다 (MSA DB 분리).
        for (String table : List.of("users", "user_oauth_accounts", "user_refresh_tokens",
                "email_verifications", "workspaces", "workspace_members")) {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?) IS NOT NULL",
                    Boolean.class,
                    "public." + table
            );
            assertThat(exists).as(table).isFalse();
        }
    }

    @Test
    void migration_createsDocumentAssetFoundationAndProtectsReferencedAssets() {
        for (String table : List.of(
                "document_assets", "document_asset_references", "document_asset_orphans")) {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?) IS NOT NULL",
                    Boolean.class,
                    "public." + table
            );
            assertThat(exists).as(table).isTrue();
        }

        String userId = "asset-user-" + UUID.randomUUID();
        String workspaceId = "asset-workspace-" + UUID.randomUUID();
        String documentId = "asset-document-" + UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        insertDocument(documentId, workspaceId, userId, "asset.md", "b".repeat(64), DocumentRole.EDITABLE.name());
        jdbcTemplate.update(
                """
                INSERT INTO document_assets(
                    id, workspace_id, uploaded_by, original_filename, content_type, byte_size,
                    width, height, content_hash, storage_key, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """,
                assetId, workspaceId, userId, "diagram.png", "image/png", 4L,
                1, 1, "a".repeat(64), "assets/" + assetId
        );
        jdbcTemplate.update(
                "INSERT INTO document_asset_references(document_id, asset_id, created_at) VALUES (?, ?, now())",
                documentId,
                assetId
        );

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM document_assets WHERE id = ?", assetId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void documents_allowSameContentAndFolderPlacement() {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;

        insertDocument("doc_parent_" + suffix, workspaceId, userId, "parent.md", "same-hash", "EDITABLE");
        insertDocument("doc_same_" + suffix, workspaceId, userId, "copy.md", "same-hash", "EDITABLE");
        assertThatThrownBy(() -> insertDocument("doc_duplicate_name_" + suffix, workspaceId, userId,
                "parent.md", "different-hash", "EDITABLE")).isInstanceOf(DataIntegrityViolationException.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents WHERE workspace_id = ? AND content_hash = ?",
                Integer.class,
                workspaceId,
                "same-hash"
        );
        assertThat(count).isEqualTo(2);

        UUID folderId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO folders(
                    id, workspace_id, parent_folder_id, name, sort_order, current_version, created_at, updated_at
                ) VALUES (?, ?, NULL, ?, 0, 1, now(), now())
                """,
                folderId,
                workspaceId,
                "폴더"
        );
        // 통일 모델: 역할과 무관하게 folder_id로 폴더에 배치할 수 있다.
        jdbcTemplate.update(
                "UPDATE documents SET folder_id = ? WHERE id = ?",
                folderId,
                "doc_parent_" + suffix
        );
        insertDocument("doc_original_" + suffix, workspaceId, userId, "original.pdf", "pdf-hash", "ORIGINAL");
        jdbcTemplate.update(
                "UPDATE documents SET folder_id = ? WHERE id = ?",
                folderId,
                "doc_original_" + suffix
        );
        Integer placed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents WHERE folder_id = ?",
                Integer.class,
                folderId
        );
        assertThat(placed).isEqualTo(2);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE documents SET source_document_id = id WHERE id = ?",
                "doc_original_" + suffix
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void chatExports_areUniqueByWorkspaceHashAndSelectionModeOnly() {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String hash = "chat-hash-" + suffix;

        insertDocument("regular_a_" + suffix, workspaceId, userId, "a.md", hash, "EDITABLE");
        insertDocument("regular_b_" + suffix, workspaceId, userId, "b.md", hash, "EDITABLE");
        insertDocument("chat_full_" + suffix, workspaceId, userId, "full.md", hash, "EDITABLE");
        jdbcTemplate.update("UPDATE documents SET origin = 'chat_export', selection_mode = 'full' WHERE id = ?",
                "chat_full_" + suffix);
        insertDocument("chat_partial_" + suffix, workspaceId, userId, "partial.md", hash, "EDITABLE");
        jdbcTemplate.update("UPDATE documents SET origin = 'chat_export', selection_mode = 'partial' WHERE id = ?",
                "chat_partial_" + suffix);

        insertDocument("chat_duplicate_" + suffix, workspaceId, userId, "duplicate.md", hash, "EDITABLE");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE documents SET origin = 'chat_export', selection_mode = 'full' WHERE id = ?",
                "chat_duplicate_" + suffix)).isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update("UPDATE documents SET origin = 'chat_export', selection_mode = 'full', deleted_at = now() WHERE id = ?",
                "chat_full_" + suffix);
        insertDocument("chat_reexport_" + suffix, workspaceId, userId, "reexport.md", hash, "EDITABLE");
        jdbcTemplate.update("UPDATE documents SET origin = 'chat_export', selection_mode = 'full' WHERE id = ?",
                "chat_reexport_" + suffix);

        String predicate = jdbcTemplate.queryForObject(
                "SELECT pg_get_expr(indpred, indrelid) FROM pg_index WHERE indexrelid = 'uq_documents_chat_export_workspace_hash_mode'::regclass",
                String.class);
        assertThat(predicate).contains("deleted_at IS NULL");
    }

    @Test
    void duplicate_sameIdempotencyKeyConcurrently_createsOneDocument() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String sourceId = "doc_source_" + suffix;
        // guard가 Redis projection을 읽으므로 멤버십을 projection에 심는다 (access 테이블은 access_db 소유).
        redisTemplate.opsForValue().set("authz:role:" + workspaceId + ":" + userId, "OWNER");
        insertDocument(sourceId, workspaceId, userId, "보고서.md", "source-hash", "EDITABLE");
        jdbcTemplate.update(
                """
                INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at)
                VALUES (?, '# 최신 본문', ?, 1, now(), now())
                """,
                sourceId,
                "a".repeat(64)
        );

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<DocumentDuplicateResponse> first = executor.submit(() -> {
                start.await();
                return documentService.duplicate(
                        workspaceId, userId, sourceId, "concurrent-key");
            });
            Future<DocumentDuplicateResponse> second = executor.submit(() -> {
                start.await();
                return documentService.duplicate(
                        workspaceId, userId, sourceId, "concurrent-key");
            });
            start.countDown();

            assertThat(first.get().id()).isEqualTo(second.get().id());
        }

        Integer duplicateCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents WHERE workspace_id = ? AND origin = 'duplicate'",
                Integer.class,
                workspaceId
        );
        Integer idempotencyCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM idempotency_records
                WHERE user_id = ? AND idempotency_key = 'concurrent-key'
                """,
                Integer.class,
                userId
        );
        assertThat(duplicateCount).isEqualTo(1);
        assertThat(idempotencyCount).isEqualTo(1);
    }

    @Test
    void documentSoftDeleteAndRestore_preservesOriginalAndEditingState() {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String documentId = "doc_" + suffix;
        insertWorkspaceMember(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "문서.md", "original-hash", "EDITABLE");
        jdbcTemplate.update(
                """
                INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at)
                VALUES (?, '# 보존 본문', ?, 1, now(), now())
                """,
                documentId,
                "b".repeat(64)
        );

        documentService.delete(
                workspaceId,
                userId,
                documentId,
                "delete-" + suffix,
                new DocumentLifecycleRequest(1L)
        );

        Map<String, Object> deleted = jdbcTemplate.queryForMap(
                """
                SELECT current_version, content_hash, deleted_at, deleted_by
                FROM documents WHERE id = ?
                """,
                documentId
        );
        assertThat(deleted.get("current_version")).isEqualTo(2L);
        assertThat(deleted.get("content_hash")).isEqualTo("original-hash");
        assertThat(deleted.get("deleted_at")).isNotNull();
        assertThat(deleted.get("deleted_by")).isEqualTo(userId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT markdown FROM document_edit_states WHERE document_id = ?",
                String.class,
                documentId
        )).isEqualTo("# 보존 본문");
        assertThat(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                documentId, workspaceId)).isEmpty();

        documentService.restore(
                workspaceId,
                userId,
                documentId,
                "restore-" + suffix,
                new DocumentLifecycleRequest(2L)
        );

        Map<String, Object> restored = jdbcTemplate.queryForMap(
                """
                SELECT current_version, deleted_at, deleted_by, folder_id
                FROM documents WHERE id = ?
                """,
                documentId
        );
        assertThat(restored.get("current_version")).isEqualTo(3L);
        assertThat(restored.get("deleted_at")).isNull();
        assertThat(restored.get("deleted_by")).isNull();
        assertThat(restored.get("folder_id")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT markdown FROM document_edit_states WHERE document_id = ?",
                String.class,
                documentId
        )).isEqualTo("# 보존 본문");
    }

    @Test
    void markdownExport_readsLatestEditStateWithoutChangingDocument() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String documentId = "doc_" + suffix;
        insertWorkspaceMember(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "회의 결과.md", "original-hash", "EDITABLE");
        jdbcTemplate.update(
                """
                INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at)
                VALUES (?, '# 최신 회의 결과\n한글 본문', ?, 1, now(), now())
                """,
                documentId,
                "b".repeat(64)
        );
        Map<String, Object> before = jdbcTemplate.queryForMap(
                """
                SELECT current_version, updated_at
                FROM documents WHERE id = ?
                """,
                documentId
        );

        DocumentExportResult result =
                documentExportService.exportMarkdown(workspaceId, userId, documentId);

        assertThat(result.filename()).isEqualTo("회의 결과.md");
        assertThat(new String(result.content().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("# 최신 회의 결과\n한글 본문");
        assertThat(jdbcTemplate.queryForMap(
                """
                SELECT current_version, updated_at
                FROM documents WHERE id = ?
                """,
                documentId
        )).isEqualTo(before);
    }

    @Test
    void documentSoftDelete_sameIdempotencyKeyConcurrently_returnsOneResult() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String documentId = "doc_" + suffix;
        insertWorkspaceMember(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "문서.md", "hash", "EDITABLE");

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                start.await();
                return documentService.delete(
                        workspaceId,
                        userId,
                        documentId,
                        "same-delete-key",
                        new DocumentLifecycleRequest(1L)
                );
            });
            Future<?> second = executor.submit(() -> {
                start.await();
                return documentService.delete(
                        workspaceId,
                        userId,
                        documentId,
                        "same-delete-key",
                        new DocumentLifecycleRequest(1L)
                );
            });
            start.countDown();

            assertThat(first.get()).isEqualTo(second.get());
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_version FROM documents WHERE id = ?",
                Long.class,
                documentId
        )).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM idempotency_records
                WHERE user_id = ? AND idempotency_key = 'same-delete-key'
                """,
                Integer.class,
                userId
        )).isEqualTo(1);
    }

    @Test
    void findByIdInActiveWorkspace_filtersOnlyDocumentSoftDelete() {
        // workspaces는 access_db 소유 — workspace 유효성은 WorkspaceAccessGuard(projection/내부 API)가
        // 담당하고, 이 쿼리는 문서 soft delete 여부만 거른다 (MSA DB 분리).
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String documentId = "doc_" + suffix;
        insertWorkspaceMember(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "문서.md", "hash", "EDITABLE");

        assertThat(documentRepository.findByIdInActiveWorkspace(documentId)).isPresent();

        jdbcTemplate.update(
                "UPDATE documents SET deleted_at = now(), deleted_by = ? WHERE id = ?",
                userId,
                documentId
        );

        assertThat(documentRepository.findByIdInActiveWorkspace(documentId)).isEmpty();

        jdbcTemplate.update(
                "UPDATE documents SET deleted_at = NULL, deleted_by = NULL WHERE id = ?",
                documentId
        );

        assertThat(documentRepository.findByIdInActiveWorkspace(documentId)).isPresent();
    }

    @Test
    @Transactional
    void conditionalUpdates_allowOnlyCurrentBaseVersion() {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String documentId = "doc_" + suffix;
        insertDocument(documentId, workspaceId, userId, "기존.md", "hash-before", "EDITABLE");

        Instant renamedAt = Instant.now();
        int renamed = documentRepository.renameIfVersionMatches(
                documentId,
                workspaceId,
                1,
                "새 제목.md",
                "새 제목",
                "새 제목.md",
                renamedAt
        );
        int staleRename = documentRepository.renameIfVersionMatches(
                documentId,
                workspaceId,
                1,
                "오래된 요청.md",
                "오래된 요청",
                "오래된 요청.md",
                Instant.now()
        );
        int contentUpdated = documentRepository.updateContentIfVersionMatches(
                documentId,
                workspaceId,
                2,
                "hash-after",
                42,
                Instant.now()
        );

        assertThat(renamed).isEqualTo(1);
        assertThat(staleRename).isZero();
        assertThat(contentUpdated).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                """
                SELECT filename, display_name, current_version, current_content_hash, byte_size
                FROM documents
                WHERE id = ?
                """,
                documentId
        )).containsAllEntriesOf(Map.of(
                "filename", "새 제목.md",
                "display_name", "새 제목",
                "current_version", 3L,
                "current_content_hash", "hash-after",
                "byte_size", 42L
        ));
    }

    @Test
    void visibleListAndSearchIncludeChatExportAndExcludeDeletedAndOtherWorkspaceDocuments() {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String otherWorkspaceId = "ws_other_" + suffix;

        insertDocument("doc_visible_" + suffix, workspaceId, userId, "보고서.md", "visible-hash", "EDITABLE");
        insertDocument("doc_deleted_" + suffix, workspaceId, userId, "보고서 삭제.md", "deleted-hash", "EDITABLE");
        insertDocument("doc_chat_" + suffix, workspaceId, userId, "보고서 채팅.md", "chat-hash", "EDITABLE");
        insertDocument("doc_other_" + suffix, otherWorkspaceId, userId, "보고서 외부.md", "other-hash", "EDITABLE");
        jdbcTemplate.update(
                "UPDATE documents SET deleted_at = now() WHERE id = ?",
                "doc_deleted_" + suffix
        );
        jdbcTemplate.update(
                "UPDATE documents SET origin = 'chat_export' WHERE id = ?",
                "doc_chat_" + suffix
        );

        assertThat(documentRepository.findVisibleByWorkspaceId(workspaceId))
                .extracting(fruition.core.document.domain.Document::getId)
                .containsExactly("doc_chat_" + suffix, "doc_visible_" + suffix);
        assertThat(documentRepository.searchVisibleByWorkspaceId(workspaceId, "보고서"))
                .extracting(fruition.core.document.domain.Document::getId)
                .containsExactly("doc_chat_" + suffix, "doc_visible_" + suffix);
        assertThat(documentRepository.searchVisibleByWorkspaceId(workspaceId, "본문에만 있는 검색어"))
                .isEmpty();
        assertThat(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                "doc_deleted_" + suffix, workspaceId)).isEmpty();
        assertThat(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                "doc_visible_" + suffix, otherWorkspaceId)).isEmpty();
        assertThat(documentRepository.findMaxRootSortOrder(workspaceId, DocumentRole.EDITABLE))
                .isZero();
    }

    @Test
    void editStateFolderAndIdempotencyConstraintsAreEnforced() {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String documentId = "doc_" + suffix;
        UUID parentFolderId = UUID.randomUUID();
        UUID childFolderId = UUID.randomUUID();
        insertDocument(documentId, workspaceId, userId, "note.md", "note-hash", "EDITABLE");

        jdbcTemplate.update(
                """
                INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at)
                VALUES (?, ?, ?, 1, now(), now())
                """,
                documentId,
                "# 제목",
                "edit-hash"
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO document_edit_states(document_id, markdown, content_hash, revision, created_at, updated_at)
                VALUES (?, ?, ?, 1, now(), now())
                """,
                documentId,
                "# 중복",
                "other-hash"
        )).isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update(
                """
                INSERT INTO folders(
                    id, workspace_id, parent_folder_id, name, sort_order, current_version, created_at, updated_at
                ) VALUES (?, ?, NULL, ?, 0, 1, now(), now())
                """,
                parentFolderId,
                workspaceId,
                "부모"
        );
        jdbcTemplate.update(
                """
                INSERT INTO folders(
                    id, workspace_id, parent_folder_id, name, sort_order, current_version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 0, 1, now(), now())
                """,
                childFolderId,
                workspaceId,
                parentFolderId,
                "자식"
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT parent_folder_id FROM folders WHERE id = ?",
                UUID.class,
                childFolderId
        )).isEqualTo(parentFolderId);

        UUID firstRequestId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO idempotency_records(
                    id, user_id, endpoint_scope, idempotency_key, request_hash,
                    status, claim_token, response_status, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', ?, NULL, ?, ?)
                """,
                firstRequestId,
                userId,
                "POST:/documents/markdown",
                "same-key",
                "request-hash",
                UUID.randomUUID(),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now().plusSeconds(86_400))
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO idempotency_records(
                    id, user_id, endpoint_scope, idempotency_key, request_hash,
                    status, claim_token, response_status, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', ?, NULL, ?, ?)
                """,
                UUID.randomUUID(),
                userId,
                "POST:/documents/markdown",
                "same-key",
                "different-request-hash",
                UUID.randomUUID(),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now().plusSeconds(86_400))
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migration_backfillsExistingV8Documents() throws Exception {
        String databaseName = "backfill_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(
                postgresContainer.getJdbcUrl(),
                postgresContainer.getUsername(),
                postgresContainer.getPassword()
        ); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }

        String databaseUrl = "jdbc:postgresql://"
                + postgresContainer.getHost()
                + ":"
                + postgresContainer.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/"
                + databaseName;

        Flyway.configure()
                .dataSource(databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword())
                .target(MigrationVersion.fromVersion("8"))
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                databaseUrl,
                postgresContainer.getUsername(),
                postgresContainer.getPassword()
        ); Statement statement = connection.createStatement()) {
            // users/workspaces는 access_db 소유 — core_db에는 FK가 없어 ID만 쓰면 된다 (MSA DB 분리).
            statement.executeUpdate(legacyDocumentInsert(
                    "doc_a", "첫 문서.md", "text/markdown", "hash-a", "sources/doc_a"
            ));
            statement.executeUpdate(legacyDocumentInsert(
                    "doc_b", "둘째.MD", "application/octet-stream", "hash-b", "sources/doc_b"
            ));
            statement.executeUpdate(legacyDocumentInsert(
                    "doc_c", "원본.pdf", "application/pdf", "hash-c", "sources/doc_c"
            ));
        }

        Flyway.configure()
                .dataSource(databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword())
                .load()
                .migrate();

        List<Map<String, Object>> documents = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                databaseUrl,
                postgresContainer.getUsername(),
                postgresContainer.getPassword()
        ); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     """
                     SELECT id, display_name, normalized_filename, document_role,
                            folder_id, sort_order,
                            current_version, current_content_hash
                     FROM documents
                     ORDER BY id
                     """
             )) {
            while (resultSet.next()) {
                documents.add(Map.ofEntries(
                        Map.entry("id", resultSet.getString("id")),
                        Map.entry("displayName", resultSet.getString("display_name")),
                        Map.entry("normalizedFilename", resultSet.getString("normalized_filename")),
                        Map.entry("documentRole", resultSet.getString("document_role")),
                        Map.entry("sortOrder", resultSet.getLong("sort_order")),
                        Map.entry("currentVersion", resultSet.getLong("current_version")),
                        Map.entry("currentContentHash", resultSet.getString("current_content_hash")),
                        Map.entry("folderIsNull", resultSet.getObject("folder_id") == null)
                ));
            }

            try (ResultSet editStateCount = statement.executeQuery("SELECT count(*) FROM document_edit_states")) {
                editStateCount.next();
                assertThat(editStateCount.getInt(1)).isZero();
            }
        }

        assertThat(documents).containsExactly(
                Map.of(
                        "id", "doc_a",
                        "displayName", "첫 문서",
                        "normalizedFilename", "첫 문서.md",
                        "documentRole", "EDITABLE",
                        "sortOrder", 0L,
                        "currentVersion", 1L,
                        "currentContentHash", "hash-a",
                        "folderIsNull", true
                ),
                Map.of(
                        "id", "doc_b",
                        "displayName", "둘째",
                        "normalizedFilename", "둘째.md",
                        "documentRole", "EDITABLE",
                        "sortOrder", 1L,
                        "currentVersion", 1L,
                        "currentContentHash", "hash-b",
                        "folderIsNull", true
                ),
                Map.of(
                        "id", "doc_c",
                        "displayName", "원본",
                        "normalizedFilename", "원본.pdf",
                        "documentRole", "ORIGINAL",
                        "sortOrder", 0L,
                        "currentVersion", 1L,
                        "currentContentHash", "hash-c",
                        "folderIsNull", true
                )
        );
    }

    private AgentToolExecuteRequest agentRenameRequest(
            String workspaceId,
            String userId,
            String documentId,
            String idempotencyKey,
            String displayName
    ) throws Exception {
        return new AgentToolExecuteRequest(
                "run-agent-rename", workspaceId, userId, "plan-agent-rename", 1,
                "a".repeat(64), "operation-agent-rename", idempotencyKey,
                objectMapper.readTree("""
                        {"document_id":"%s","display_name":"%s","base_version":1}
                        """.formatted(documentId, displayName)));
    }

    private void insertWorkspaceMember(String userId, String workspaceId) {
        // workspace_members는 access_db 소유 — guard가 읽는 Redis projection만 심는다 (MSA DB 분리).
        redisTemplate.opsForValue().set("authz:role:" + workspaceId + ":" + userId, "OWNER");
    }

    private void insertDocument(
            String documentId,
            String workspaceId,
            String userId,
            String filename,
            String contentHash,
            String documentRole
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO documents(
                    id, byte_size, content_hash, filename, display_name, normalized_filename,
                    mime_type, source_uri, status, uploaded_at, updated_at, user_id, workspace_id,
                    current_content_hash, current_version, document_role, sort_order
                ) VALUES (?, 1, ?, ?, ?, ?, ?, ?, 'completed', now(), now(), ?, ?, ?, 1, ?, 0)
                """,
                documentId,
                contentHash,
                filename,
                filename.substring(0, filename.lastIndexOf('.')),
                filename.toLowerCase(),
                documentRole.equals("EDITABLE") ? "text/markdown" : "application/pdf",
                documentRole.equals("EDITABLE") ? null : "sources/" + documentId,
                userId,
                workspaceId,
                contentHash,
                documentRole
        );
    }

    private String legacyDocumentInsert(
            String documentId,
            String filename,
            String mimeType,
            String contentHash,
            String sourceUri
    ) {
        return """
                INSERT INTO documents(
                    id, byte_size, content_hash, filename, mime_type, source_uri,
                    status, uploaded_at, user_id, workspace_id
                ) VALUES (
                    '%s', 1, '%s', '%s', '%s', '%s',
                    'completed', '2026-07-24 00:00:00+00', 'user_backfill', 'ws_backfill'
                )
                """.formatted(documentId, contentHash, filename, mimeType, sourceUri);
    }

    private String v34DocumentInsert(String documentId, String sourceDocumentId) {
        return """
                INSERT INTO documents(
                    id, byte_size, content_hash, filename, display_name, normalized_filename,
                    mime_type, source_uri, status, uploaded_at, updated_at, user_id, workspace_id,
                    current_content_hash, current_version, document_role, sort_order,
                    origin, selection_mode, source_document_id
                ) VALUES (
                    '%s', 1, 'hash-v34', '%s.md', '%s', '%s.md', 'text/markdown',
                    NULL, 'completed', now(), now(), 'user-v34', 'workspace-v34',
                    'hash-v34', 1, 'EDITABLE', 0, 'chat_export', 'selected', %s
                )
                """.formatted(documentId, documentId, documentId, documentId,
                sourceDocumentId == null ? "NULL" : "'" + sourceDocumentId + "'");
    }
}
