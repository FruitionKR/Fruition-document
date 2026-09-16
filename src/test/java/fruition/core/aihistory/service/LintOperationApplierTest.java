package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.dto.OperationResultRequest;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.wiki.domain.WikiPageVersion;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LintOperationApplierTest {

    private static final String CONTENT_HASH =
            "sha256:9d564752bf9b42a1f90fb7548ac6c6bd653f619ceeccd7f950bd5d60eaf2033a";

    @Mock OperationLogRepository operationLogRepository;
    @Mock OperationChangeRepository operationChangeRepository;
    @Mock PipelineWikiStateRequester wikiStateRequester;
    @Mock WikiPageVersionRepository versionRepository;
    @Mock WikiPageContributionRepository contributionRepository;
    @Mock LineCounter lineCounter;

    private LintOperationApplier applier;

    @BeforeEach
    void setUp() {
        applier = new LintOperationApplier(operationLogRepository, operationChangeRepository,
                wikiStateRequester, versionRepository, contributionRepository, lineCounter);
    }

    @Test
    void apply_updatesVersionWithoutCreatingContribution() {
        OperationLog operation = OperationLog.processing(
                "op_lint_1", "ws_1", "user_1", OperationType.lint, null, Instant.now());
        WikiPageVersion previous = org.mockito.Mockito.mock(WikiPageVersion.class);
        when(operationLogRepository.findById("op_lint_1")).thenReturn(Optional.of(operation));
        when(wikiStateRequester.lookup(List.of("page_1"), "ws_1")).thenReturn(List.of(
                new PipelineWikiStateRequester.WikiPageSnapshot(
                        "page_1", "concept", "제목", "title", "ws_1", "active")));
        when(versionRepository.findTopByIdPageIdOrderByIdRevisionDesc("page_1"))
                .thenReturn(Optional.of(previous));
        when(previous.getRevision()).thenReturn(3L);
        when(previous.getMarkdown()).thenReturn("# 이전 본문");
        when(versionRepository.findMaxRevision("page_1")).thenReturn(3L);
        when(contributionRepository.countByIdPageIdAndActiveTrue("page_1")).thenReturn(2L);
        when(lineCounter.count("page_1", 3L, "# 이전 본문", 4L, "# 정리된 본문"))
                .thenReturn(new LineCounter.LineCount(1, 1));
        OperationResultRequest request = new OperationResultRequest(
                "op_lint_1", "lint", "succeeded", "ws_1", "user_1", null,
                "Wiki lint를 실행했습니다.", List.of(), List.of());

        applier.apply("op_lint_1", request,
                List.of(new LintOperationApplier.LoadedPage(
                        "page_1", "wiki/ws_1/pages/page_1/ops/op_lint_1.md",
                        "# 정리된 본문", CONTENT_HASH)),
                "payload-hash", Instant.now());

        ArgumentCaptor<WikiPageVersion> versionCaptor = ArgumentCaptor.forClass(WikiPageVersion.class);
        verify(versionRepository).save(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getRevision()).isEqualTo(4L);
        assertThat(versionCaptor.getValue().getContributionCount()).isEqualTo(2);
        assertThat(versionCaptor.getValue().getContentHash()).isEqualTo(CONTENT_HASH);
        assertThat(versionCaptor.getValue().getOperationId()).isEqualTo("op_lint_1");

        ArgumentCaptor<OperationChange> changeCaptor = ArgumentCaptor.forClass(OperationChange.class);
        verify(operationChangeRepository).save(changeCaptor.capture());
        assertThat(changeCaptor.getValue().getBeforeRevision()).isEqualTo(3L);
        assertThat(changeCaptor.getValue().getAfterRevision()).isEqualTo(4L);
        assertThat(changeCaptor.getValue().getChangeType()).isEqualTo(ChangeType.updated);
        assertThat(changeCaptor.getValue().getResourceDisplayName()).isEqualTo("제목");
        verify(contributionRepository, never()).save(any());
        assertThat(operation.getStatus().name()).isEqualTo("succeeded");
        assertThat(operation.getChangedResourceCount()).isEqualTo(1);
    }

    @Test
    void apply_failureWithoutAnyAppliedPageIsFailed() {
        OperationLog operation = OperationLog.processing(
                "op_lint_1", "ws_1", "user_1", OperationType.lint, null, Instant.now());
        when(operationLogRepository.findById("op_lint_1")).thenReturn(Optional.of(operation));
        OperationResultRequest request = new OperationResultRequest(
                "op_lint_1", "lint", "failed", "ws_1", "user_1", null,
                "500: pipeline lint stage crashed", List.of(),
                List.of(new OperationResultRequest.FailedPage("page_1", "error")));

        applier.apply("op_lint_1", request, List.of(), "payload-hash", Instant.now());

        // 0건이면 "일부만 반영"이 아니라 실패다. OperationApplier와 같은 규칙이다.
        assertThat(operation.getStatus()).isEqualTo(OperationStatus.failed);
        assertThat(operation.getChangedResourceCount()).isZero();
        // 요약은 사용자에게 그대로 보이므로 워커 오류 원문을 싣지 않는다.
        assertThat(operation.getSummary()).isEqualTo("Wiki 정합성 검사에 실패했습니다.");
    }

    @Test
    void apply_failureWithAppliedPageIsPartiallySucceeded() {
        OperationLog operation = OperationLog.processing(
                "op_lint_1", "ws_1", "user_1", OperationType.lint, null, Instant.now());
        WikiPageVersion previous = org.mockito.Mockito.mock(WikiPageVersion.class);
        when(operationLogRepository.findById("op_lint_1")).thenReturn(Optional.of(operation));
        when(wikiStateRequester.lookup(List.of("page_1"), "ws_1")).thenReturn(List.of(
                new PipelineWikiStateRequester.WikiPageSnapshot(
                        "page_1", "concept", "제목", "title", "ws_1", "active")));
        when(versionRepository.findTopByIdPageIdOrderByIdRevisionDesc("page_1"))
                .thenReturn(Optional.of(previous));
        when(previous.getRevision()).thenReturn(3L);
        when(previous.getMarkdown()).thenReturn("# 이전 본문");
        when(versionRepository.findMaxRevision("page_1")).thenReturn(3L);
        when(contributionRepository.countByIdPageIdAndActiveTrue("page_1")).thenReturn(2L);
        when(lineCounter.count("page_1", 3L, "# 이전 본문", 4L, "# 정리된 본문"))
                .thenReturn(new LineCounter.LineCount(1, 1));
        OperationResultRequest request = new OperationResultRequest(
                "op_lint_1", "lint", "failed", "ws_1", "user_1", null,
                "500: pipeline lint stage crashed", List.of(),
                List.of(new OperationResultRequest.FailedPage("page_2", "error")));

        applier.apply("op_lint_1", request,
                List.of(new LintOperationApplier.LoadedPage(
                        "page_1", "wiki/ws_1/pages/page_1/ops/op_lint_1.md",
                        "# 정리된 본문", CONTENT_HASH)),
                "payload-hash", Instant.now());

        assertThat(operation.getStatus()).isEqualTo(OperationStatus.partially_succeeded);
        assertThat(operation.getChangedResourceCount()).isEqualTo(1);
        assertThat(operation.getSummary()).isEqualTo("Wiki 정합성 검사를 일부만 반영했습니다.");
    }

    @Test
    void apply_recordsCreatedForPageWithoutPreviousVersion() {
        OperationLog operation = OperationLog.processing(
                "op_lint_1", "ws_1", "user_1", OperationType.lint, null, Instant.now());
        when(operationLogRepository.findById("op_lint_1")).thenReturn(Optional.of(operation));
        when(wikiStateRequester.lookup(List.of("page_1"), "ws_1")).thenReturn(List.of(
                new PipelineWikiStateRequester.WikiPageSnapshot(
                        "page_1", "concept", "제목", "title", "ws_1", "active")));
        when(versionRepository.findTopByIdPageIdOrderByIdRevisionDesc("page_1"))
                .thenReturn(Optional.empty());
        when(versionRepository.findMaxRevision("page_1")).thenReturn(0L);
        when(contributionRepository.countByIdPageIdAndActiveTrue("page_1")).thenReturn(0L);
        when(lineCounter.count("page_1", null, null, 1L, "# 새 개념"))
                .thenReturn(LineCounter.LineCount.none());
        OperationResultRequest request = new OperationResultRequest(
                "op_lint_1", "lint", "succeeded", "ws_1", "user_1", null,
                "Wiki lint를 실행했습니다.", List.of(), List.of());

        applier.apply("op_lint_1", request,
                List.of(new LintOperationApplier.LoadedPage(
                        "page_1", "wiki/ws_1/pages/page_1/ops/op_lint_1.md",
                        "# 새 개념", "sha256:lint")),
                "payload-hash", Instant.now());

        ArgumentCaptor<OperationChange> changeCaptor = ArgumentCaptor.forClass(OperationChange.class);
        verify(operationChangeRepository).save(changeCaptor.capture());
        assertThat(changeCaptor.getValue().getBeforeRevision()).isNull();
        assertThat(changeCaptor.getValue().getAfterRevision()).isEqualTo(1L);
        assertThat(changeCaptor.getValue().getChangeType()).isEqualTo(ChangeType.created);
        verify(contributionRepository, never()).save(any());
    }
}
