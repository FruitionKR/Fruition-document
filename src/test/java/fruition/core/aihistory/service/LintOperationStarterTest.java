package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.repository.OperationLogRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LintOperationStarterTest {

    private final OperationLogRepository repository = mock(OperationLogRepository.class);
    private final LintOperationStarter starter = new LintOperationStarter(repository);

    @Test
    void start_savesProcessingLintWithoutTargetDocument() {
        when(repository.save(any(OperationLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String operationId = starter.start("ws_1", "user_1");

        verify(repository).save(any(OperationLog.class));
        assertThat(operationId).startsWith("op_");
        var saved = captureSavedOperation();
        assertThat(saved.getOperationId()).isEqualTo(operationId);
        assertThat(saved.getWorkspaceId()).isEqualTo("ws_1");
        assertThat(saved.getUserId()).isEqualTo("user_1");
        assertThat(saved.getOperationType()).isEqualTo(OperationType.lint);
        assertThat(saved.getStatus()).isEqualTo(OperationStatus.processing);
        assertThat(saved.getTargetDocumentId()).isNull();
        assertThat(saved.getTargetDisplayName()).isEqualTo("위키 다듬기");
    }

    @Test
    void markFailed_completesExistingOperation() {
        OperationLog operation = OperationLog.processing(
                "op_lint_1", "ws_1", "user_1", OperationType.lint, null, java.time.Instant.now());
        when(repository.findById("op_lint_1")).thenReturn(Optional.of(operation));

        starter.markFailed("op_lint_1", "pipeline failure");

        assertThat(operation.getStatus()).isEqualTo(OperationStatus.failed);
        // 요약은 사용자에게 그대로 보이므로 워커 오류 원문 대신 사용자용 문구를 남긴다.
        assertThat(operation.getSummary()).isEqualTo("Wiki 정합성 검사에 실패했습니다.");
        assertThat(operation.getCompletedAt()).isNotNull();
    }

    private OperationLog captureSavedOperation() {
        var captor = org.mockito.ArgumentCaptor.forClass(OperationLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
