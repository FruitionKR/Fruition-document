package fruition.core.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.exception.InvalidRestoreRequestException;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 저장소 경계만 대체하고 미리보기·서명·복구 판정·실행 명령 생성은 실제 코드를 연결한다. */
@ExtendWith(MockitoExtension.class)
class IngestRestoreFlowTest {
    private static final String WORKSPACE = "ws_restore_flow";
    private static final String USER = "user_test";
    private static final String INGEST = "op_monday";
    private static final Instant MONDAY = Instant.parse("2026-09-07T01:00:00Z");

    @Mock OperationLogRepository logs;
    @Mock WikiPageContributionRepository contributions;
    @Mock PipelineWikiStateRequester wiki;
    @Mock WorkspaceAccessGuard access;
    @Mock RestoreOperationLifecycle lifecycle;
    @Mock AiCommandOutboxWriter outbox;

    private RestorePreviewService preview;
    private RestoreExecuteService execute;
    private OperationLog target;

    @BeforeEach
    void setUp() {
        target = OperationLog.completed(INGEST, WORKSPACE, USER, OperationType.ingest,
                "doc_monday", "월요일 ingest", 2, MONDAY);
        var scope = new RestoreScopeResolver(logs);
        var planner = new RestorePlanner(wiki);
        var signer = new PreviewTokenSigner("");
        var validator = new RestoreTargetValidator(wiki);
        var documentPlanner = mock(DocumentRestorePlanner.class);
        var lintPlanner = mock(LintRestorePlanner.class);
        preview = new RestorePreviewService(logs, contributions, access, scope, planner,
                signer, documentPlanner, lintPlanner, validator);
        execute = new RestoreExecuteService(preview, scope, planner, lintPlanner, signer,
                lifecycle, documentPlanner, mock(DocumentRestoreApplier.class), outbox,
                validator, new ObjectMapper(), "ai.maintenance.command",
                mock(PlatformTransactionManager.class));
        when(logs.findByOperationIdAndWorkspaceId(INGEST, WORKSPACE)).thenReturn(Optional.of(target));
        when(logs.findByTargetDocumentAfter("doc_monday", MONDAY, INGEST, OperationType.ingest))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("삭제 상태여도 메타데이터와 기여가 남으면 ingest 취소 명령을 만들 수 있다")
    void deletedPagesStillAllowCancellation() {
        pages(List.of(contribution("source", INGEST, 1), contribution("concept", INGEST, 1)), "deleted");
        var plan = preview.preview(WORKSPACE, USER, INGEST);
        assertThat(plan.deleteCount()).isEqualTo(2);

        var command = queue(plan.previewToken());

        assertThat(command.kind()).isEqualTo("restore_ingest");
        assertThat(command.cancelOperationIds()).containsExactly(INGEST);
        assertThat(command.sourcePage().pageId()).isEqualTo("source");
        assertThat(command.restoreToOperationId()).isNull();
        assertThat(command.deletedPages()).containsExactly("concept");
        assertThat(command.rebuildPages()).isEmpty();
    }

    @Test
    @DisplayName("Source는 재작성 목록에 넣지 않고 마지막 남은 ingest 본문으로 복원한다")
    void sourceUsesExistingSnapshotContract() {
        pages(List.of(contribution("source", "op_sunday", 1),
                contribution("source", INGEST, 2), contribution("concept", INGEST, 1)), "active");
        var plan = preview.preview(WORKSPACE, USER, INGEST);
        assertThat(plan.restoreCount()).isEqualTo(1);
        assertThat(plan.rebuildCount()).isZero();

        var command = queue(plan.previewToken());

        assertThat(command.sourcePage().pageId()).isEqualTo("source");
        assertThat(command.restoreToOperationId()).isEqualTo("op_sunday");
        assertThat(command.rebuildPages()).isEmpty();
        assertThat(command.deletedPages()).containsExactly("concept");
    }

    @Test
    @DisplayName("다른 문서의 후속 기여는 살려서 Concept를 재작성한다")
    void keepsOtherDocumentsLaterContribution() {
        pages(List.of(contribution("source", INGEST, 1),
                contribution("concept", INGEST, 1), contribution("concept", "op_other", 2)), "active");
        var plan = preview.preview(WORKSPACE, USER, INGEST);
        assertThat(plan.rebuildCount()).isEqualTo(1);

        var command = queue(plan.previewToken());

        assertThat(command.cancelOperationIds()).containsExactly(INGEST);
        assertThat(command.deletedPages()).isEmpty();
        assertThat(command.rebuildPages()).singleElement().satisfies(page -> {
            assertThat(page.pageId()).isEqualTo("concept");
            assertThat(page.keepContributions()).singleElement()
                    .satisfies(kept -> assertThat(kept.operationId()).isEqualTo("op_other"));
        });
    }

    @Test
    @DisplayName("과거 버전이 있는 Concept도 미리보기와 실행 모두 남은 기여로 재작성한다")
    void conceptWithSnapshotIsRebuiltByPipeline() {
        // 일요일 다른 문서 기여 → 월요일 ingest → 화요일 lint 수정 후의 상태.
        // lint는 기여를 추가하지 않으므로 복구 원장에는 두 ingest 기여만 남는다.
        pages(List.of(contribution("source", INGEST, 1),
                contribution("concept", "op_sunday", 1), contribution("concept", INGEST, 2)), "active");
        var plan = preview.preview(WORKSPACE, USER, INGEST);
        assertThat(plan.restoreCount()).isZero();
        assertThat(plan.rebuildCount()).isEqualTo(1);
        assertThat(plan.pages()).anySatisfy(page -> {
            assertThat(page.pageId()).isEqualTo("concept");
            assertThat(page.action()).isEqualTo("rebuild");
            assertThat(page.targetRevision()).isNull();
        });

        var command = queue(plan.previewToken());

        // 현재 pipeline 계약은 Concept를 keep_contributions로 재작성한다.
        // 이 페이지를 보내지 않으면 월요일 내용과 이후 lint 결과가 그대로 남는다.
        assertThat(command.rebuildPages())
                .as("미리보기에서 복원하기로 한 Concept도 pipeline에 실제 변경 명령이 전달되어야 한다")
                .singleElement().satisfies(page -> {
                    assertThat(page.pageId()).isEqualTo("concept");
                    assertThat(page.keepContributions()).singleElement()
                            .satisfies(kept -> assertThat(kept.operationId()).isEqualTo("op_sunday"));
                });
    }

    @Test
    @DisplayName("이미 기여가 모두 취소되었다면 빈 복구를 접수하지 않는다")
    void rejectsAlreadyCancelledIngest() {
        when(contributions.findActivePageIdsByOperationIds(Set.of(INGEST))).thenReturn(List.of());

        assertThatThrownBy(() -> preview.preview(WORKSPACE, USER, INGEST))
                .isInstanceOf(InvalidRestoreRequestException.class)
                .hasMessageContaining("되돌릴 Wiki 페이지가 없습니다");
        verifyNoInteractions(outbox, lifecycle, wiki);
    }

    private void pages(List<WikiPageContribution> rows, String status) {
        when(contributions.findActivePageIdsByOperationIds(Set.of(INGEST)))
                .thenReturn(List.of("source", "concept"));
        when(contributions.findByPageIds(List.of("source", "concept"))).thenReturn(rows);
        when(wiki.lookup(List.of("source", "concept"), WORKSPACE)).thenReturn(List.of(
                new PipelineWikiStateRequester.WikiPageSnapshot(
                        "source", "source", "월요일 원문", "source", WORKSPACE, status),
                new PipelineWikiStateRequester.WikiPageSnapshot(
                        "concept", "concept", "공유 개념", "concept", WORKSPACE, status)));
    }

    private WikiPageContribution contribution(String page, String operation, long revision) {
        return new WikiPageContribution(page, operation,
                operation.equals(INGEST) ? "doc_monday" : "doc_other",
                revision, "wiki/test/" + operation + ".json", MONDAY);
    }

    private RestoreExecuteService.RestoreCommand queue(String token) {
        var restore = OperationLog.applying("op_restore", WORKSPACE, USER,
                "doc_monday", INGEST, "{}", MONDAY.plusSeconds(172800));
        when(lifecycle.startQueued(eq(target), anyString(), anyString(), any()))
                .thenReturn(Optional.of(restore));
        var result = execute.execute(WORKSPACE, USER, INGEST, token);
        assertThat(result.status()).isEqualTo("queued");
        ArgumentCaptor<Object> captured = ArgumentCaptor.forClass(Object.class);
        verify(outbox).enqueue(eq(result.runId()), eq("ai.maintenance.command"),
                eq(WORKSPACE), captured.capture());
        return (RestoreExecuteService.RestoreCommand) captured.getValue();
    }
}
