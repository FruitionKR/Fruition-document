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
import fruition.core.aihistory.exception.InvalidCallbackPayloadException;
import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.domain.WikiPageContributionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
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
        when(contributionRepository.findByPageIds(List.of("page_1"))).thenReturn(List.of(new WikiPageContribution(
                "page_1", "op_ingest", "doc_1", 1L, "wiki/ws_1/pages/page_1/ops/op_ingest.json", Instant.now())));
        when(lineCounter.count("page_1", 3L, "# 이전 본문", 4L, "# 정리된 본문"))
                .thenReturn(new LineCounter.LineCount(1, 1));
        OperationResultRequest request = new OperationResultRequest(
                "op_lint_1", "lint", "succeeded", "ws_1", "user_1", null,
                "Wiki lint를 실행했습니다.", List.of(), List.of());

        applier.apply("op_lint_1", request,
                List.of(new LintOperationApplier.LoadedPage(
                        "page_1", "wiki/ws_1/pages/page_1/ops/op_lint_1.md",
                        "# 정리된 본문", CONTENT_HASH)),
                List.of(), "payload-hash", Instant.now());

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

        applier.apply("op_lint_1", request, List.of(), List.of(), "payload-hash", Instant.now());

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
        when(contributionRepository.findByPageIds(List.of("page_1"))).thenReturn(List.of(new WikiPageContribution(
                "page_1", "op_ingest", "doc_1", 1L, "wiki/ws_1/pages/page_1/ops/op_ingest.json", Instant.now())));
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
                List.of(), "payload-hash", Instant.now());

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
                List.of(), "payload-hash", Instant.now());

        ArgumentCaptor<OperationChange> changeCaptor = ArgumentCaptor.forClass(OperationChange.class);
        verify(operationChangeRepository).save(changeCaptor.capture());
        assertThat(changeCaptor.getValue().getBeforeRevision()).isNull();
        assertThat(changeCaptor.getValue().getAfterRevision()).isEqualTo(1L);
        assertThat(changeCaptor.getValue().getChangeType()).isEqualTo(ChangeType.created);
        verify(contributionRepository, never()).save(any());
    }

    @Test
    @DisplayName("원장 행을 받은 순서대로 기존 행 뒤에 더하고 새 버전은 마지막 순서 값에 둔다")
    void apply_writesLedgerRowsInOrder() {
        OperationLog operation = givenLint();
        givenPage("P", 2L);
        givenPage("C", 0L);
        when(operationLogRepository.findById("op_x")).thenReturn(Optional.of(OperationLog.completed(
                "op_x", "ws_1", "user_1", OperationType.ingest, "doc_x", "편입", 1, Instant.now())));
        List<OperationResultRequest.LedgerContribution> ledger = List.of(
                ledger("P", "op_lint_1", null, null),
                ledger("C", "op_lint_1", null, null),
                ledger("C", "op_x", "doc_x", "op_lint_1"));

        applier.apply("op_lint_1", lintRequest(List.of()), List.of(loaded("P"), loaded("C")), ledger,
                "payload-hash", Instant.now());

        ArgumentCaptor<WikiPageContribution> rows = ArgumentCaptor.forClass(WikiPageContribution.class);
        verify(contributionRepository, times(3)).save(rows.capture());
        assertThat(rows.getAllValues())
                .extracting(WikiPageContribution::getPageId, WikiPageContribution::getIngestOperationId,
                        WikiPageContribution::getSequenceRevision, WikiPageContribution::getSourceDocumentId,
                        WikiPageContribution::getLintOperationId, WikiPageContribution::isLintRecord)
                .containsExactly(
                        tuple("C", "op_lint_1", 1L, null, "op_lint_1", true),
                        tuple("C", "op_x", 2L, "doc_x", "op_lint_1", false),
                        tuple("P", "op_lint_1", 3L, null, "op_lint_1", true));
        ArgumentCaptor<WikiPageVersion> versions = ArgumentCaptor.forClass(WikiPageVersion.class);
        verify(versionRepository, times(2)).save(versions.capture());
        assertThat(versions.getAllValues()).extracting(WikiPageVersion::getPageId, WikiPageVersion::getRevision)
                .containsExactly(tuple("C", 2L), tuple("P", 3L));
        assertThat(operation.getStatus()).isEqualTo(OperationStatus.succeeded);
    }

    @Test
    @DisplayName("같은 lint 결과가 다시 오면 이미 만든 revision을 보고 원장 행을 더하지 않는다")
    void apply_redeliveryDoesNotDuplicateLedgerRows() {
        givenLint();
        when(wikiStateRequester.lookup(List.of("C"), "ws_1")).thenReturn(List.of(snapshot("C")));
        when(versionRepository.findFirstByIdPageIdAndOperationId("C", "op_lint_1"))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(WikiPageVersion.class)));

        applier.apply("op_lint_1", lintRequest(List.of()), List.of(loaded("C")),
                List.of(ledger("C", "op_lint_1", null, null)), "payload-hash", Instant.now());

        verify(contributionRepository, never()).save(any());
        verify(versionRepository, never()).save(any());
        verify(operationChangeRepository, never()).save(any());
    }

    @Test
    @DisplayName("합쳐 지운 페이지의 기여를 이 lint 이름으로 끄고 삭제 변경을 남긴다")
    void apply_deactivatesDeletedPageContributions() {
        givenLint();
        givenPage("C", 3L);
        WikiPageVersion previous = org.mockito.Mockito.mock(WikiPageVersion.class);
        when(previous.getRevision()).thenReturn(3L);
        when(versionRepository.findTopByIdPageIdOrderByIdRevisionDesc("C")).thenReturn(Optional.of(previous));
        WikiPageContribution ingest = new WikiPageContribution(
                "C", "op_z", "doc_z", 3L, "wiki/ws_1/pages/C/ops/op_z.json", Instant.now());
        WikiPageContribution merge = WikiPageContribution.lint(
                "C", "op_lint_1", null, 4L, "wiki/ws_1/pages/C/ops/op_lint_1.json", "op_lint_1", Instant.now());
        when(contributionRepository.findByPageIds(List.of("C"))).thenReturn(List.of(ingest, merge));

        applier.apply("op_lint_1", lintRequest(List.of("C")), List.of(loaded("C")),
                List.of(ledger("C", "op_lint_1", null, null)), "payload-hash", Instant.now());

        assertThat(List.of(ingest, merge)).allSatisfy(row -> {
            assertThat(row.isActive()).isFalse();
            assertThat(row.getDeactivatedBy()).isEqualTo("op_lint_1");
        });
        ArgumentCaptor<OperationChange> changes = ArgumentCaptor.forClass(OperationChange.class);
        verify(operationChangeRepository, times(2)).save(changes.capture());
        assertThat(changes.getAllValues()).extracting(OperationChange::getChangeType)
                .containsExactly(ChangeType.updated, ChangeType.deleted);
    }

    @Test
    @DisplayName("꺼진 같은 (페이지, 작업) 행은 이 lint가 다시 잇는다")
    void apply_relinksInactiveRow() {
        givenLint();
        givenPage("P", 5L);
        when(operationLogRepository.findById("op_z")).thenReturn(Optional.of(OperationLog.completed(
                "op_z", "ws_1", "user_1", OperationType.ingest, "doc_z", "편입", 1, Instant.now())));
        WikiPageContribution previous = WikiPageContribution.lint(
                "P", "op_z", "doc_z", 2L, "wiki/ws_1/pages/P/ops/op_z.json", "op_lint_0", Instant.now());
        previous.deactivate("op_restore");
        when(contributionRepository.findById(new WikiPageContributionId("P", "op_z")))
                .thenReturn(Optional.of(previous));

        applier.apply("op_lint_1", lintRequest(List.of()), List.of(loaded("P")),
                List.of(ledger("P", "op_z", "doc_z", "op_lint_1")), "payload-hash", Instant.now());

        assertThat(previous.isActive()).isTrue();
        assertThat(previous.getSequenceRevision()).isEqualTo(6L);
        assertThat(previous.getLintOperationId()).isEqualTo("op_lint_1");
        verify(contributionRepository, never()).save(any());
    }

    @Test
    @DisplayName("다른 워크스페이스 작업을 잇는 원장 행은 거절한다")
    void apply_rejectsLinkToOtherWorkspace() {
        givenLint();
        when(operationLogRepository.findById("op_x")).thenReturn(Optional.of(OperationLog.completed(
                "op_x", "ws_other", "user_1", OperationType.ingest, "doc_x", "편입", 1, Instant.now())));

        assertThatThrownBy(() -> applier.apply("op_lint_1", lintRequest(List.of()), List.of(loaded("C")),
                List.of(ledger("C", "op_x", "doc_x", "op_lint_1")), "payload-hash", Instant.now()))
                .isInstanceOf(InvalidCallbackPayloadException.class);
        verify(contributionRepository, never()).save(any());
    }

    private OperationLog givenLint() {
        OperationLog operation = OperationLog.processing(
                "op_lint_1", "ws_1", "user_1", OperationType.lint, null, Instant.now());
        when(operationLogRepository.findById("op_lint_1")).thenReturn(Optional.of(operation));
        return operation;
    }

    private void givenPage(String pageId, long maxRevision) {
        when(wikiStateRequester.lookup(List.of(pageId), "ws_1")).thenReturn(List.of(snapshot(pageId)));
        when(versionRepository.findMaxRevision(pageId)).thenReturn(maxRevision);
        org.mockito.Mockito.lenient().when(lineCounter.count(eq(pageId), any(), any(), anyLong(), any()))
                .thenReturn(LineCounter.LineCount.none());
    }

    private static PipelineWikiStateRequester.WikiPageSnapshot snapshot(String pageId) {
        return new PipelineWikiStateRequester.WikiPageSnapshot(
                pageId, "concept", "제목", pageId, "ws_1", "active");
    }

    private static OperationResultRequest lintRequest(List<String> deletedPages) {
        return new OperationResultRequest("op_lint_1", "lint", "succeeded", "ws_1", "user_1", null,
                "Wiki lint를 실행했습니다.", List.of(), List.of(), deletedPages, null, null, null);
    }

    private static LintOperationApplier.LoadedPage loaded(String pageId) {
        return new LintOperationApplier.LoadedPage(pageId,
                "wiki/ws_1/pages/" + pageId + "/ops/op_lint_1.md", "# " + pageId, "sha256:" + pageId);
    }

    private static OperationResultRequest.LedgerContribution ledger(
            String pageId, String operationId, String documentId, String linkedBy) {
        return new OperationResultRequest.LedgerContribution(pageId, operationId, documentId,
                "wiki/ws_1/pages/" + pageId + "/ops/" + operationId + ".json", linkedBy);
    }
}
