package fruition.core.aihistory.repository;

import fruition.TestcontainersConfiguration;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OperationLogRepositoryVisibilityIntegrationTest {

    private static final Set<OperationStatus> HIDDEN_BY_DEFAULT =
            Set.of(OperationStatus.processing, OperationStatus.applying,
                    OperationStatus.notify_pending, OperationStatus.rebuilding,
                    OperationStatus.failed, OperationStatus.conflict);

    @Autowired OperationLogRepository operationLogRepository;

    @Test
    void findPage_hidesOperationsWithNothingToShow() {
        String workspaceId = "ws_" + UUID.randomUUID();
        Instant now = Instant.now();
        OperationLog editChanged = OperationLog.completed(
                "op_edit_changed_" + UUID.randomUUID(), workspaceId, "user_1",
                OperationType.document_edit, null, "문서 편집 완료", 1, now.minusSeconds(7));
        OperationLog editUnchanged = OperationLog.completed(
                "op_edit_unchanged_" + UUID.randomUUID(), workspaceId, "user_1",
                OperationType.document_edit, null, "변경 없음", 0, now.minusSeconds(6));
        OperationLog editConflict = OperationLog.applyingDocumentEdit(
                "op_edit_conflict_" + UUID.randomUUID(), workspaceId, "user_1", null, now.minusSeconds(5));
        editConflict.complete(OperationStatus.conflict, "편집 충돌", 0, null, now.minusSeconds(5));
        // 적용 표를 소비했지만 본문 저장이 실패해 남은 고아 행.
        OperationLog editApplying = OperationLog.applyingDocumentEdit(
                "op_edit_applying_" + UUID.randomUUID(), workspaceId, "user_1", null, now.minusSeconds(4));
        OperationLog ingestFailed = OperationLog.processing(
                "op_ingest_failed_" + UUID.randomUUID(), workspaceId, "user_1",
                OperationType.ingest, null, now.minusSeconds(3));
        ingestFailed.complete(OperationStatus.failed, "Wiki ingest에 실패했습니다.", 0, null, now.minusSeconds(3));
        OperationLog ingestUnchanged = OperationLog.completed(
                "op_ingest_unchanged_" + UUID.randomUUID(), workspaceId, "user_1",
                OperationType.ingest, null, "바뀐 페이지 없음", 0, now.minusSeconds(2));
        OperationLog ingestSucceeded = OperationLog.completed(
                "op_ingest_succeeded_" + UUID.randomUUID(), workspaceId, "user_1",
                OperationType.ingest, null, "페이지 2개 반영", 2, now.minusSeconds(1));
        operationLogRepository.saveAll(List.of(editChanged, editUnchanged, editConflict, editApplying,
                ingestFailed, ingestUnchanged, ingestSucceeded));

        List<OperationLog> visible = findPage(workspaceId, null, now.plusSeconds(1), "", 20);

        assertThat(visible)
                .extracting(OperationLog::getOperationId)
                .containsExactly(ingestSucceeded.getOperationId(), editChanged.getOperationId());
    }

    @Test
    void findPage_showsInProgressOnlyWhenStatusIsExplicitlyRequested() {
        String workspaceId = "ws_" + UUID.randomUUID();
        Instant now = Instant.now();
        OperationLog ingest = OperationLog.processing(
                "op_ingest_" + UUID.randomUUID(), workspaceId, "user_1",
                OperationType.ingest, null, now.minusSeconds(1));
        operationLogRepository.save(ingest);

        assertThat(findPage(workspaceId, null, now.plusSeconds(1), "", 20)).isEmpty();

        List<OperationLog> processing =
                findPage(workspaceId, OperationStatus.processing, now.plusSeconds(1), "", 20);

        assertThat(processing)
                .extracting(OperationLog::getOperationId)
                .containsExactly(ingest.getOperationId());
    }

    @Test
    void findPage_hidesFailedByDefaultButOpensItToExplicitStatusQuery() {
        String workspaceId = "ws_" + UUID.randomUUID();
        Instant now = Instant.now();
        OperationLog failed = OperationLog.processing(
                "op_failed_" + UUID.randomUUID(), workspaceId, "user_1",
                OperationType.ingest, null, now.minusSeconds(1));
        failed.complete(OperationStatus.failed, "Wiki ingest에 실패했습니다.", 0, null, now.minusSeconds(1));
        operationLogRepository.save(failed);

        assertThat(findPage(workspaceId, null, now.plusSeconds(1), "", 20)).isEmpty();

        // 프론트 실패 알림이 이 명시 조회로 실패를 감지한다. 여기까지 막으면 알림이 죽는다.
        assertThat(findPage(workspaceId, OperationStatus.failed, now.plusSeconds(1), "", 20))
                .extracting(OperationLog::getOperationId)
                .containsExactly(failed.getOperationId());
    }

    @Test
    void findPage_hidesRestoreConflictByDefaultButOpensItToExplicitStatusQuery() {
        String workspaceId = "ws_" + UUID.randomUUID();
        Instant now = Instant.now();
        // restored_from은 ai_operation_logs를 참조하는 FK라 되돌릴 대상이 실제로 있어야 한다.
        OperationLog target = OperationLog.completed(
                "op_target_" + UUID.randomUUID(), workspaceId, "user_1",
                OperationType.ingest, null, "페이지 1개 반영", 1, now.minusSeconds(3));
        operationLogRepository.save(target);
        // 미리보기가 낡아 반영하지 못한 복구. AiTaskResultApplier가 conflict으로 확정한다.
        OperationLog restore = OperationLog.applying(
                "op_restore_" + UUID.randomUUID(), workspaceId, "user_1", null,
                target.getOperationId(), "{}", now.minusSeconds(1));
        restore.complete(OperationStatus.conflict, "미리보기가 낡았습니다.", 0, null, now.minusSeconds(1));
        operationLogRepository.save(restore);

        assertThat(findPage(workspaceId, null, now.plusSeconds(1), "", 20))
                .extracting(OperationLog::getOperationId)
                .containsExactly(target.getOperationId());

        assertThat(findPage(workspaceId, OperationStatus.conflict, now.plusSeconds(1), "", 20))
                .extracting(OperationLog::getOperationId)
                .containsExactly(restore.getOperationId());
    }

    @Test
    void findPage_doesNotSkipOperationsSharingTheSameCreatedAt() {
        String workspaceId = "ws_" + UUID.randomUUID();
        Instant sameInstant = Instant.parse("2026-08-20T05:33:40.036572Z");
        // 같은 시각 두 건. created_at만으로 커서를 잡으면 두 번째 페이지에서 통째로 빠진다.
        OperationLog older = OperationLog.completed(
                "op_aaa_" + UUID.randomUUID(), workspaceId, "user_1",
                OperationType.ingest, null, "ingest A", 1, sameInstant);
        OperationLog newer = OperationLog.completed(
                "op_zzz_" + UUID.randomUUID(), workspaceId, "user_1",
                OperationType.ingest, null, "ingest B", 1, sameInstant);
        operationLogRepository.saveAll(List.of(older, newer));

        List<OperationLog> first =
                findPage(workspaceId, null, Instant.parse("9999-12-31T23:59:59Z"), "", 1);
        assertThat(first).hasSize(1);

        OperationLog last = first.get(0);
        List<OperationLog> second =
                findPage(workspaceId, null, last.getCreatedAt(), last.getOperationId(), 1);

        assertThat(second).hasSize(1);
        assertThat(List.of(first.get(0).getOperationId(), second.get(0).getOperationId()))
                .containsExactlyInAnyOrder(older.getOperationId(), newer.getOperationId());
    }

    private List<OperationLog> findPage(String workspaceId, OperationStatus status,
                                        Instant cursor, String cursorOperationId, int size) {
        return operationLogRepository.findPage(workspaceId, null, status, cursor, cursorOperationId,
                OperationStatus.succeeded, OperationType.document_edit, HIDDEN_BY_DEFAULT,
                PageRequest.of(0, size));
    }
}
