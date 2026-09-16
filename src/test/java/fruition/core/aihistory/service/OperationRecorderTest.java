package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.OperationLogRepository;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationRecorderTest {

    @Mock OperationLogRepository operationLogRepository;
    @Mock OperationChangeRepository operationChangeRepository;
    @Mock LineCounter lineCounter;

    @Test
    void exactConflictRetryKeepsOneAuditRow() {
        OperationRecorder recorder = new OperationRecorder(
                operationLogRepository, operationChangeRepository, lineCounter);
        Instant firstAttempt = Instant.parse("2026-08-12T00:00:00Z");
        OperationLog existing = OperationLog.completed(
                "op-1", "ws-1", "user-1", OperationType.document_edit, "doc-1",
                "문서가 이미 변경되어 AI 편집을 반영하지 못했습니다.", 0, firstAttempt);
        existing.complete(OperationStatus.conflict, existing.getSummary(), 0, null, firstAttempt);
        when(operationLogRepository.insertConflictIfAbsent(
                eq("op-1"), eq("ws-1"), eq("user-1"), eq("doc-1"), any(), any()))
                .thenReturn(1)
                .thenReturn(0);
        when(operationLogRepository.findById("op-1")).thenReturn(Optional.of(existing));

        recorder.recordConflict("op-1", "ws-1", "user-1", "doc-1", firstAttempt);
        recorder.recordConflict("op-1", "ws-1", "user-1", "doc-1", firstAttempt.plusSeconds(1));

        verify(operationLogRepository, times(2)).insertConflictIfAbsent(
                eq("op-1"), eq("ws-1"), eq("user-1"), eq("doc-1"), any(), any());
        verify(operationLogRepository).findById("op-1");
    }

    @Test
    void recordsDocumentDisplayNameAtChangeTime() {
        OperationRecorder recorder = new OperationRecorder(
                operationLogRepository, operationChangeRepository, lineCounter);
        when(lineCounter.count("doc-1", 1L, "이전", 2L, "이후"))
                .thenReturn(new LineCounter.LineCount(1, 1));

        OperationLog operation = OperationLog.applyingDocumentEdit(
                "op-1", "ws-1", "user-1", "doc-1", Instant.parse("2026-08-12T00:00:00Z"));
        when(operationLogRepository.findById("op-1")).thenReturn(Optional.of(operation));
        when(operationChangeRepository.existsByOperationIdAndResourceIdAndChangeType(
                "op-1", "doc-1", fruition.core.aihistory.domain.ChangeType.updated)).thenReturn(false);

        recorder.recordDocumentEdit("op-1", "ws-1", "user-1", "doc-1",
                "변경 당시 이름", 1L, 2L, "이전", "이후",
                Instant.parse("2026-08-12T00:00:00Z"));

        ArgumentCaptor<fruition.core.aihistory.domain.OperationChange> captor =
                ArgumentCaptor.forClass(fruition.core.aihistory.domain.OperationChange.class);
        verify(operationChangeRepository).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getResourceDisplayName())
                .isEqualTo("변경 당시 이름");
    }
}
