package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.repository.OperationLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestOperationStarterTest {

    @Test
    void start_savesDocumentNameAndServerStartedAtSnapshot() {
        OperationLogRepository repository = mock(OperationLogRepository.class);
        when(repository.save(any(OperationLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        IngestOperationStarter starter = new IngestOperationStarter(repository);
        Instant startedAt = Instant.parse("2026-08-19T01:02:03Z");

        String operationId = starter.start(
                "ws_1", "user_1", "doc_1", "회의록", startedAt);

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(repository).save(captor.capture());
        OperationLog saved = captor.getValue();
        assertThat(saved.getOperationId()).isEqualTo(operationId);
        assertThat(saved.getOperationType()).isEqualTo(OperationType.ingest);
        assertThat(saved.getStatus()).isEqualTo(OperationStatus.processing);
        assertThat(saved.getTargetDocumentId()).isEqualTo("doc_1");
        assertThat(saved.getTargetDisplayName()).isEqualTo("회의록");
        assertThat(saved.getCreatedAt()).isEqualTo(startedAt);
    }
}
