package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.dto.OperationResultRequest;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationApplierStatusTest {

    @Test
    void failureWithoutAnyAppliedPageIsFailed() {
        OperationLogRepository operations = mock(OperationLogRepository.class);
        OperationLog operation = OperationLog.processing(
                "op-1", "ws-1", "user-1", OperationType.ingest, "doc-1", Instant.now());
        when(operations.findById("op-1")).thenReturn(Optional.of(operation));
        OperationApplier applier = new OperationApplier(
                operations,
                mock(OperationChangeRepository.class),
                mock(PipelineWikiStateRequester.class),
                mock(WikiPageVersionRepository.class),
                mock(WikiPageContributionRepository.class),
                mock(LineCounter.class));
        OperationResultRequest request = new OperationResultRequest(
                "op-1", "ingest", "failed", "ws-1", "user-1", "doc-1",
                "failed", List.of(), List.of(new OperationResultRequest.FailedPage("page-1", "error")));

        applier.apply("op-1", request, List.of(), "hash", Instant.now());

        assertThat(operation.getStatus()).isEqualTo(OperationStatus.failed);
        assertThat(operation.getChangedResourceCount()).isZero();
    }

    @Test
    void failureSummaryDoesNotLeakUpstreamErrorText() {
        OperationLogRepository operations = mock(OperationLogRepository.class);
        OperationLog operation = OperationLog.processing(
                "op-1", "ws-1", "user-1", OperationType.ingest, "doc-1", Instant.now());
        when(operations.findById("op-1")).thenReturn(Optional.of(operation));
        OperationApplier applier = new OperationApplier(
                operations,
                mock(OperationChangeRepository.class),
                mock(PipelineWikiStateRequester.class),
                mock(WikiPageVersionRepository.class),
                mock(WikiPageContributionRepository.class),
                mock(LineCounter.class));
        OperationResultRequest request = new OperationResultRequest(
                "op-1", "ingest", "failed", "ws-1", "user-1", "doc-1",
                "500: document-svc pipeline source lookup failed", List.of(), List.of());

        applier.apply("op-1", request, List.of(), "hash", Instant.now());

        // 요약은 목록·상세 API로 사용자에게 그대로 보이므로 상류 오류 원문을 싣지 않는다.
        assertThat(operation.getSummary()).isEqualTo("Wiki ingest에 실패했습니다.");
    }
}
