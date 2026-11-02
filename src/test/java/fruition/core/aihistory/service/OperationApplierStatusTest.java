package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.dto.OperationResultRequest;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
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
                mock(LineCounter.class),
                mock(DocumentRepository.class));
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
                mock(LineCounter.class),
                mock(DocumentRepository.class));
        OperationResultRequest request = new OperationResultRequest(
                "op-1", "ingest", "failed", "ws-1", "user-1", "doc-1",
                "500: document-svc pipeline source lookup failed", List.of(), List.of());

        applier.apply("op-1", request, List.of(), "hash", Instant.now());

        // 요약은 목록·상세 API로 사용자에게 그대로 보이므로 상류 오류 원문을 싣지 않는다.
        assertThat(operation.getSummary()).isEqualTo("Wiki ingest에 실패했습니다.");
    }

    @Test
    void contributionOfTrashedDocumentIsStoredInactive() {
        OperationLogRepository operations = mock(OperationLogRepository.class);
        OperationLog operation = OperationLog.processing(
                "op-1", "ws-1", "user-1", OperationType.ingest, "doc-1", Instant.now());
        when(operations.findById("op-1")).thenReturn(Optional.of(operation));
        PipelineWikiStateRequester wikiState = mock(PipelineWikiStateRequester.class);
        when(wikiState.lookup(List.of("page-1"), "ws-1")).thenReturn(List.of(
                new PipelineWikiStateRequester.WikiPageSnapshot(
                        "page-1", "source", "문서", "doc", "ws-1", "active")));
        WikiPageVersionRepository versions = mock(WikiPageVersionRepository.class);
        when(versions.findTopByIdPageIdOrderByIdRevisionDesc("page-1")).thenReturn(Optional.empty());
        LineCounter lineCounter = mock(LineCounter.class);
        when(lineCounter.count(any(), any(), any(), anyLong(), any()))
                .thenReturn(new LineCounter.LineCount(1, 0));
        WikiPageContributionRepository contributions = mock(WikiPageContributionRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        // 편입 도중 문서가 휴지통으로 갔다.
        when(documents.findDeletedForShare("doc-1")).thenReturn(Optional.of(true));
        OperationApplier applier = new OperationApplier(
                operations, mock(OperationChangeRepository.class), wikiState, versions,
                contributions, lineCounter, documents);
        OperationResultRequest request = new OperationResultRequest(
                "op-1", "ingest", "succeeded", "ws-1", "user-1", "doc-1",
                "완료", List.of(), List.of());

        applier.apply("op-1", request, List.of(new OperationApplier.LoadedPage(
                "page-1", "wiki/page-1.md", "wiki/contribution", "# 문서", "hash")), "hash", Instant.now());

        ArgumentCaptor<WikiPageContribution> saved = ArgumentCaptor.forClass(WikiPageContribution.class);
        verify(contributions).save(saved.capture());
        assertThat(saved.getValue().isActive()).isFalse();
        assertThat(saved.getValue().getDeactivatedBy()).isNull();
    }
}
