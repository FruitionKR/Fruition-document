package fruition.core.aihistory.service;

import fruition.TestcontainersConfiguration;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.repository.OperationLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** 복구 선점이 애플리케이션 인스턴스 사이에서도 DB unique index로 직렬화되는지 확인한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RestoreOperationLifecycleIntegrationTest {

    @Autowired RestoreOperationLifecycle lifecycle;
    @Autowired OperationLogRepository operationLogRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void samePreviewToken_concurrentlyClaimsOnlyOneRestore() throws Exception {
        String targetId = "op_target_" + UUID.randomUUID();
        String workspaceId = "ws_" + UUID.randomUUID();
        OperationLog target = OperationLog.completed(
                targetId, workspaceId, "user_1", OperationType.ingest,
                null, "ingest", 1, Instant.now());
        operationLogRepository.save(target);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var task = (java.util.concurrent.Callable<Optional<OperationLog>>) () -> {
                ready.countDown();
                start.await();
                return lifecycle.startQueued(target, "{}", "a".repeat(64), Instant.now());
            };
            Future<Optional<OperationLog>> first = executor.submit(task);
            Future<Optional<OperationLog>> second = executor.submit(task);
            ready.await();
            start.countDown();

            List<Optional<OperationLog>> results = List.of(first.get(), second.get());

            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
            assertThat(results.stream().filter(Optional::isEmpty)).hasSize(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ai_operation_logs WHERE restored_from = ?",
                    Long.class, targetId)).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void documentEdit_samePreviewToken_concurrentlyClaimsOnlyOneRestore() throws Exception {
        String targetId = "op_target_" + UUID.randomUUID();
        String workspaceId = "ws_" + UUID.randomUUID();
        OperationLog target = OperationLog.completed(
                targetId, workspaceId, "user_1", OperationType.document_edit,
                null, "edit", 1, Instant.now());
        operationLogRepository.save(target);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var task = (java.util.concurrent.Callable<Optional<OperationLog>>) () -> {
                ready.countDown();
                start.await();
                return lifecycle.start(target, "{}", "b".repeat(64), Instant.now());
            };
            Future<Optional<OperationLog>> first = executor.submit(task);
            Future<Optional<OperationLog>> second = executor.submit(task);
            ready.await();
            start.countDown();

            List<Optional<OperationLog>> results = List.of(first.get(), second.get());

            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
            assertThat(results.stream().filter(Optional::isEmpty)).hasSize(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ai_operation_logs WHERE restored_from = ?",
                    Long.class, targetId)).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedClaimAllowsSameTokenRetry() {
        String targetId = "op_target_" + UUID.randomUUID();
        String workspaceId = "ws_" + UUID.randomUUID();
        OperationLog target = OperationLog.completed(
                targetId, workspaceId, "user_1", OperationType.ingest,
                null, "ingest", 1, Instant.now());
        operationLogRepository.save(target);

        Optional<OperationLog> first = lifecycle.startQueued(target, "{}", "c".repeat(64), Instant.now());
        assertThat(first).isPresent();
        lifecycle.fail(first.orElseThrow().getOperationId(), "wiki restore failed", Instant.now());

        // 실패한 복구는 아무것도 반영하지 못했으므로 같은 미리보기 토큰으로 다시 시도할 수 있어야 한다.
        assertThat(lifecycle.isClaimed(targetId, "c".repeat(64))).isFalse();
        assertThat(lifecycle.startQueued(target, "{}", "c".repeat(64), Instant.now())).isPresent();

        // 실패 기록 자체는 감사용으로 남되, 요약은 워커 오류 원문이 아니라 사용자용 문구다.
        assertThat(operationLogRepository.findById(first.orElseThrow().getOperationId()))
                .get()
                .extracting(OperationLog::getStatus, OperationLog::getSummary)
                .containsExactly(OperationStatus.failed, "되돌리기에 실패했습니다.");
    }

    @Test
    void lateFailureLeavesSucceededRestoreUntouched() {
        String targetId = "op_target_" + UUID.randomUUID();
        String workspaceId = "ws_" + UUID.randomUUID();
        OperationLog target = OperationLog.completed(
                targetId, workspaceId, "user_1", OperationType.document_edit,
                null, "edit", 1, Instant.now());
        operationLogRepository.save(target);

        Optional<OperationLog> first = lifecycle.start(target, "{}", "e".repeat(64), Instant.now());
        assertThat(first).isPresent();
        lifecycle.finishDocument(first.orElseThrow().getOperationId(), 1L, 2L, Instant.now());

        // 반영이 끝난 뒤 늦게 도착한 실패 신호. 상태를 덮거나 선점을 풀면 같은 미리보기로 두 번 반영된다.
        lifecycle.fail(first.orElseThrow().getOperationId(), "late failure", Instant.now());

        assertThat(operationLogRepository.findById(first.orElseThrow().getOperationId()))
                .get().extracting(OperationLog::getStatus).isEqualTo(OperationStatus.succeeded);
        assertThat(lifecycle.isClaimed(targetId, "e".repeat(64))).isTrue();
    }

    @Test
    void succeededClaimRejectsSameTokenRetry() {
        String targetId = "op_target_" + UUID.randomUUID();
        String workspaceId = "ws_" + UUID.randomUUID();
        OperationLog target = OperationLog.completed(
                targetId, workspaceId, "user_1", OperationType.document_edit,
                null, "edit", 1, Instant.now());
        operationLogRepository.save(target);

        Optional<OperationLog> first = lifecycle.start(target, "{}", "d".repeat(64), Instant.now());
        assertThat(first).isPresent();
        lifecycle.finishDocument(first.orElseThrow().getOperationId(), 1L, 2L, Instant.now());

        // 이미 반영된 복구는 중복 실행을 막아야 한다.
        assertThat(lifecycle.isClaimed(targetId, "d".repeat(64))).isTrue();
        assertThat(lifecycle.start(target, "{}", "d".repeat(64), Instant.now())).isEmpty();
    }

    @Test
    void migration_addsRestoreTokenClaimIndex() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_name = 'ai_operation_logs'
                  AND column_name = 'restore_token_hash'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM pg_indexes
                WHERE tablename = 'ai_operation_logs'
                  AND indexname = 'uk_ai_operation_logs_restore_token'
                """, Integer.class)).isEqualTo(1);
    }
}
