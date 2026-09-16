package fruition.core.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.dto.DocumentRestorePlan;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.exception.InvalidRestoreRequestException;
import fruition.core.aihistory.exception.OperationNotFoundException;
import fruition.core.aihistory.exception.RestorePreviewStaleException;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.wiki.domain.WikiPageContribution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestoreExecuteServiceTest {

    private static final String WORKSPACE = "ws_1";
    private static final String USER = "user_1";
    private static final String TARGET = "op_a2";
    private static final String TOKEN = "signed-token";
    private static final Instant T = Instant.parse("2026-07-28T10:00:00Z");

    @Mock RestorePreviewService previewService;
    @Mock RestoreScopeResolver scopeResolver;
    @Mock RestorePlanner planner;
    @Mock LintRestorePlanner lintRestorePlanner;
    @Mock PreviewTokenSigner tokenSigner;
    @Mock RestoreOperationLifecycle lifecycle;
    @Mock DocumentRestorePlanner documentPlanner;
    @Mock DocumentRestoreApplier documentApplier;
    @Mock AiCommandOutboxWriter outboxWriter;
    @Mock RestoreTargetValidator validator;
    @Mock PlatformTransactionManager transactionManager;

    private RestoreExecuteService service;

    @BeforeEach
    void setUp() {
        service = new RestoreExecuteService(previewService, scopeResolver, planner,
                lintRestorePlanner, tokenSigner, lifecycle, documentPlanner, documentApplier,
                outboxWriter, validator, new ObjectMapper(), "ai.maintenance.command", transactionManager);
    }

    @Test
    void wikiRestore_storesApprovedManifestAndQueuesCommand() {
        PageRestorePlan page = PageRestorePlan.restore("wp_S_A", 2L, "op_a1", 1);
        WikiPageContribution contribution =
                new WikiPageContribution("wp_S_A", "op_a1", "doc_A", 1, "wiki/a.md", T);
        OperationLog target = target(OperationType.ingest);
        OperationLog restore = OperationLog.applying(
                "op_restore", WORKSPACE, USER, "doc_A", TARGET, "{}", T);
        when(previewService.loadOperation(WORKSPACE, USER, TARGET)).thenReturn(target);
        when(scopeResolver.resolve(target)).thenReturn(Set.of("op_a2"));
        when(previewService.loadContributions(Set.of("op_a2")))
                .thenReturn(Map.of("wp_S_A", List.of(contribution)));
        RestorePlan plan = new RestorePlan(List.of(page));
        when(planner.plan(any(), any(), any())).thenReturn(plan);
        when(tokenSigner.matches(TOKEN, TARGET, Map.of("wp_S_A", List.of(contribution))))
                .thenReturn(true);
        when(validator.requireApplicable(target, plan)).thenReturn(page);
        when(lifecycle.startQueued(eq(target), anyString(), anyString(), any())).thenReturn(Optional.of(restore));

        var response = service.execute(WORKSPACE, USER, TARGET, TOKEN);

        assertThat(response.status()).isEqualTo("queued");
        assertThat(response.operationId()).isEqualTo("op_restore");
        assertThat(UUID.fromString(response.runId()).toString()).isEqualTo(response.runId());
        ArgumentCaptor<Object> command = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).enqueue(eq(response.runId()), eq("ai.maintenance.command"),
                eq(WORKSPACE), command.capture());
        RestoreExecuteService.RestoreCommand restoreCommand =
                (RestoreExecuteService.RestoreCommand) command.getValue();
        assertThat(restoreCommand.sourcePage().documentId()).isEqualTo("doc_A");
        assertThat(restoreCommand)
                .extracting(RestoreExecuteService.RestoreCommand::expectedContributions)
                .isEqualTo(Map.of("wp_S_A", List.of("op_a1:1:1")));
    }

    @Test
    void wikiRestore_rejectsStalePreviewBeforeCreatingRun() {
        OperationLog target = target(OperationType.ingest);
        when(previewService.loadOperation(WORKSPACE, USER, TARGET)).thenReturn(target);
        when(scopeResolver.resolve(target)).thenReturn(Set.of("op_a2"));
        when(previewService.loadContributions(Set.of("op_a2"))).thenReturn(Map.of());
        when(planner.plan(any(), any(), any())).thenReturn(new RestorePlan(List.of()));
        when(tokenSigner.matches(TOKEN, TARGET, Map.of())).thenReturn(false);

        assertThatThrownBy(() -> service.execute(WORKSPACE, USER, TARGET, TOKEN))
                .isInstanceOf(RestorePreviewStaleException.class);

        verify(lifecycle, never()).startQueued(any(), anyString(), anyString(), any());
        verify(outboxWriter, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void wikiRestore_rejectsDuplicatePreviewTokenWithoutQueueingAgain() {
        PageRestorePlan page = PageRestorePlan.restore("wp_S_A", 2L, "op_a1", 1);
        OperationLog target = target(OperationType.ingest);
        when(previewService.loadOperation(WORKSPACE, USER, TARGET)).thenReturn(target);
        when(scopeResolver.resolve(target)).thenReturn(Set.of("op_a2"));
        when(previewService.loadContributions(Set.of("op_a2"))).thenReturn(Map.of());
        when(planner.plan(any(), any(), any())).thenReturn(new RestorePlan(List.of(page)));
        when(tokenSigner.matches(TOKEN, TARGET, Map.of())).thenReturn(true);
        when(validator.requireApplicable(target, new RestorePlan(List.of(page)))).thenReturn(page);
        when(lifecycle.startQueued(eq(target), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(WORKSPACE, USER, TARGET, TOKEN))
                .isInstanceOf(InvalidRestoreRequestException.class)
                .hasMessage("같은 미리보기 토큰으로 복구가 이미 접수되었습니다.");

        verify(outboxWriter, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void wikiRestore_replayAfterSuccessRejectsBeforeMutableStateStaleness() {
        PageRestorePlan page = PageRestorePlan.restore("wp_S_A", 2L, "op_a1", 1);
        OperationLog target = target(OperationType.ingest);
        OperationLog restore = OperationLog.applying(
                "op_restore", WORKSPACE, USER, "doc_A", TARGET, "{}", T);
        Map<String, List<WikiPageContribution>> contributions = Map.of();
        RestorePlan plan = new RestorePlan(List.of(page));
        when(previewService.loadOperation(WORKSPACE, USER, TARGET)).thenReturn(target);
        when(scopeResolver.resolve(target)).thenReturn(Set.of("op_a2"));
        when(previewService.loadContributions(Set.of("op_a2"))).thenReturn(contributions);
        when(planner.plan(any(), any(), any())).thenReturn(plan);
        when(tokenSigner.matches(TOKEN, TARGET, contributions)).thenReturn(true, false);
        when(validator.requireApplicable(target, plan)).thenReturn(page);
        when(lifecycle.isClaimed(eq(TARGET), anyString())).thenReturn(false, true);
        when(lifecycle.startQueued(eq(target), anyString(), anyString(), any()))
                .thenReturn(Optional.of(restore));

        service.execute(WORKSPACE, USER, TARGET, TOKEN);

        assertThatThrownBy(() -> service.execute(WORKSPACE, USER, TARGET, TOKEN))
                .isInstanceOf(InvalidRestoreRequestException.class);
        verify(lifecycle).startQueued(eq(target), anyString(), anyString(), any());
        verify(outboxWriter).enqueue(any(), any(), any(), any());
    }

    @Test
    void documentRestore_remainsSynchronous() {
        OperationLog target = target(OperationType.document_edit);
        DocumentRestorePlan plan = new DocumentRestorePlan("doc_A", 6, 5);
        OperationLog restore = OperationLog.applying(
                "op_restore", WORKSPACE, USER, "doc_A", TARGET, "{}", T);
        when(previewService.loadOperation(WORKSPACE, USER, TARGET)).thenReturn(target);
        when(documentPlanner.plan(target)).thenReturn(plan);
        when(tokenSigner.matches(TOKEN, TARGET, plan)).thenReturn(true);
        when(lifecycle.start(eq(target), anyString(), anyString(), any())).thenReturn(Optional.of(restore));
        when(documentApplier.apply(restore, plan)).thenReturn(7L);

        var response = service.execute(WORKSPACE, USER, TARGET, TOKEN);

        assertThat(response.status()).isEqualTo("succeeded");
        ArgumentCaptor<String> tokenHash = ArgumentCaptor.forClass(String.class);
        verify(lifecycle).start(eq(target), anyString(), tokenHash.capture(), any());
        assertThat(tokenHash.getValue())
                .isEqualTo("d131c306d498cf8aa66cbd07b847aa433734eec63cd70b609063b572e9aac140");
        verify(lifecycle).finishDocument(eq("op_restore"), eq(5L), eq(7L), any());
        verify(outboxWriter, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void documentRestore_mapsConcurrentEditToStalePreview() {
        OperationLog target = target(OperationType.document_edit);
        DocumentRestorePlan plan = new DocumentRestorePlan("doc_A", 6, 5);
        OperationLog restore = OperationLog.applying(
                "op_restore", WORKSPACE, USER, "doc_A", TARGET, "{}", T);
        when(previewService.loadOperation(WORKSPACE, USER, TARGET)).thenReturn(target);
        when(documentPlanner.plan(target)).thenReturn(plan);
        when(tokenSigner.matches(TOKEN, TARGET, plan)).thenReturn(true);
        when(lifecycle.start(eq(target), anyString(), anyString(), any())).thenReturn(Optional.of(restore));
        when(documentApplier.apply(restore, plan))
                .thenThrow(new DocumentVersionConflictException("동시 편집"));

        assertThatThrownBy(() -> service.execute(WORKSPACE, USER, TARGET, TOKEN))
                .isInstanceOf(RestorePreviewStaleException.class);

        verify(documentApplier).apply(restore, plan);
        verify(lifecycle, never()).finishDocument(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    void documentRestore_retriesWholeTransactionForTransientFailure() {
        OperationLog target = target(OperationType.document_edit);
        DocumentRestorePlan plan = new DocumentRestorePlan("doc_A", 6, 5);
        OperationLog firstRestore = OperationLog.applying(
                "op_restore_1", WORKSPACE, USER, "doc_A", TARGET, "{}", T);
        OperationLog secondRestore = OperationLog.applying(
                "op_restore_2", WORKSPACE, USER, "doc_A", TARGET, "{}", T);
        when(previewService.loadOperation(WORKSPACE, USER, TARGET)).thenReturn(target);
        when(documentPlanner.plan(target)).thenReturn(plan);
        when(tokenSigner.matches(TOKEN, TARGET, plan)).thenReturn(true);
        when(lifecycle.start(eq(target), anyString(), anyString(), any()))
                .thenReturn(Optional.of(firstRestore), Optional.of(secondRestore));
        when(documentApplier.apply(firstRestore, plan))
                .thenThrow(new IllegalStateException(new org.springframework.dao.DuplicateKeyException("retry")));
        when(documentApplier.apply(secondRestore, plan)).thenReturn(7L);

        var response = service.execute(WORKSPACE, USER, TARGET, TOKEN);

        assertThat(response.operationId()).isEqualTo("op_restore_2");
        verify(lifecycle, org.mockito.Mockito.times(2))
                .start(eq(target), anyString(), anyString(), any());
        verify(documentApplier, org.mockito.Mockito.times(2)).apply(any(), eq(plan));
        verify(lifecycle).finishDocument(eq("op_restore_2"), eq(5L), eq(7L), any());
    }

    @Test
    void documentRestore_doesNotRetryNonTransientFailure() {
        OperationLog target = target(OperationType.document_edit);
        DocumentRestorePlan plan = new DocumentRestorePlan("doc_A", 6, 5);
        OperationLog restore = OperationLog.applying(
                "op_restore", WORKSPACE, USER, "doc_A", TARGET, "{}", T);
        when(previewService.loadOperation(WORKSPACE, USER, TARGET)).thenReturn(target);
        when(documentPlanner.plan(target)).thenReturn(plan);
        when(tokenSigner.matches(TOKEN, TARGET, plan)).thenReturn(true);
        when(lifecycle.start(eq(target), anyString(), anyString(), any())).thenReturn(Optional.of(restore));
        when(documentApplier.apply(restore, plan)).thenThrow(new IllegalStateException("not transient"));

        assertThatThrownBy(() -> service.execute(WORKSPACE, USER, TARGET, TOKEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("not transient");

        verify(lifecycle).start(eq(target), anyString(), anyString(), any());
        verify(documentApplier).apply(restore, plan);
        verify(lifecycle, never()).finishDocument(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    void documentRestore_rejectsDuplicatePreviewTokenBeforeApplyingAgain() {
        OperationLog target = target(OperationType.document_edit);
        DocumentRestorePlan plan = new DocumentRestorePlan("doc_A", 6, 5);
        when(previewService.loadOperation(WORKSPACE, USER, TARGET)).thenReturn(target);
        when(documentPlanner.plan(target)).thenReturn(plan);
        when(tokenSigner.matches(TOKEN, TARGET, plan)).thenReturn(true);
        when(lifecycle.start(eq(target), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(WORKSPACE, USER, TARGET, TOKEN))
                .isInstanceOf(InvalidRestoreRequestException.class)
                .hasMessage("같은 미리보기 토큰으로 복구가 이미 접수되었습니다.");

        verify(documentApplier, never()).apply(any(), any());
        verify(lifecycle, never()).finishDocument(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    void documentRestore_replayAfterSuccessRejectsBeforeMutableStateStaleness() {
        OperationLog target = target(OperationType.document_edit);
        DocumentRestorePlan plan = new DocumentRestorePlan("doc_A", 6, 5);
        OperationLog restore = OperationLog.applying(
                "op_restore", WORKSPACE, USER, "doc_A", TARGET, "{}", T);
        when(previewService.loadOperation(WORKSPACE, USER, TARGET)).thenReturn(target);
        when(documentPlanner.plan(target)).thenReturn(plan);
        when(tokenSigner.matches(TOKEN, TARGET, plan)).thenReturn(true, false);
        when(lifecycle.isClaimed(eq(TARGET), anyString())).thenReturn(false, true);
        when(lifecycle.start(eq(target), anyString(), anyString(), any()))
                .thenReturn(Optional.of(restore));
        when(documentApplier.apply(restore, plan)).thenReturn(7L);

        service.execute(WORKSPACE, USER, TARGET, TOKEN);

        assertThatThrownBy(() -> service.execute(WORKSPACE, USER, TARGET, TOKEN))
                .isInstanceOf(InvalidRestoreRequestException.class);
        verify(documentApplier).apply(restore, plan);
        verify(lifecycle).start(eq(target), anyString(), anyString(), any());
        verify(lifecycle).finishDocument(eq("op_restore"), eq(5L), eq(7L), any());
    }

    @Test
    void crossScopeTargetDoesNotProbeRestoreClaim() {
        when(previewService.loadOperation(WORKSPACE, USER, TARGET))
                .thenThrow(new OperationNotFoundException(TARGET));

        assertThatThrownBy(() -> service.execute(WORKSPACE, USER, TARGET, TOKEN))
                .isInstanceOf(OperationNotFoundException.class);

        verifyNoInteractions(lifecycle);
    }

    private OperationLog target(OperationType type) {
        return OperationLog.completed(TARGET, WORKSPACE, USER, type,
                "doc_A", "요약", 1, T);
    }
}
