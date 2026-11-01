package fruition.core.wikimaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.service.LintOperationStarter;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.document.domain.AiCommandOutbox;
import fruition.core.document.repository.AiCommandOutboxRepository;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.document.repository.PipelineRunStatusRequester;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wikimaintenance.dto.WikiLintRequest;
import fruition.core.wikimaintenance.repository.WikiLintStateRepository;
import fruition.core.wikimaintenance.exception.PipelineWikiMaintenanceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiMaintenanceServiceTest {

    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock LintOperationStarter operationStarter;
    @Mock AiCommandOutboxRepository outboxRepository;
    @Mock PipelineRunStatusRequester runStatusRequester;
    @Mock WikiLintStateRepository lintStateRepository;
    @Mock PipelineWikiStateRequester wikiStateRequester;
    @Mock WorkspaceAiModelClient workspaceAiModelClient;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private WikiMaintenanceService service;
    private AiCommandOutboxWriter outboxWriter;

    @BeforeEach
    void setUp() {
        outboxWriter = spy(new AiCommandOutboxWriter(outboxRepository, objectMapper,
                org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
                org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class),
                org.mockito.Mockito.mock(fruition.core.usage.service.CreditService.class)));
        service = new WikiMaintenanceService(workspaceAccessGuard, operationStarter, outboxWriter,
                runStatusRequester, lintStateRepository, wikiStateRequester, objectMapper,
                "ai.maintenance.command", workspaceAiModelClient);
        org.mockito.Mockito.lenient().when(workspaceAiModelClient.get("ws_1"))
                .thenReturn(new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"));
    }

    @Test
    void lint_withoutWikiLintPermission_isForbiddenEvenForDryRun() {
        doThrow(new fruition.core.authz.WorkspacePermissionRequiredException("wiki.lint"))
                .when(workspaceAccessGuard).requirePermission("ws_1", "user_1", "wiki.lint");

        assertThatThrownBy(() -> service.lint("ws_1", "user_1", new WikiLintRequest(true, false)))
                .isInstanceOf(fruition.core.authz.WorkspacePermissionRequiredException.class);
        assertThatThrownBy(() -> service.lint("ws_1", "user_1", null))
                .isInstanceOf(fruition.core.authz.WorkspacePermissionRequiredException.class);
        verify(outboxWriter, never()).begin(any(), any(), any(), any());
    }

    @Test
    void lint_checksWikiLintPermission() {
        service.lint("ws_1", "user_1", new WikiLintRequest(false, false));

        verify(workspaceAccessGuard).requirePermission("ws_1", "user_1", "wiki.lint");
    }

    @Test
    void lint_dryRunQueuesWithoutOperation() {
        var result = service.lint("ws_1", "user_1", new WikiLintRequest(true, true));

        assertThat(result.path("status").asText()).isEqualTo("queued");
        assertThatCode(() -> UUID.fromString(result.path("run_id").asText()))
                .doesNotThrowAnyException();
        verify(operationStarter, never()).start(any(), any());
        ArgumentCaptor<WikiMaintenanceService.LintCommand> command =
                ArgumentCaptor.forClass(WikiMaintenanceService.LintCommand.class);
        verify(outboxWriter).enqueue(anyString(), eq("ai.maintenance.command"), eq("ws_1"), command.capture());
        ArgumentCaptor<AiCommandOutbox> outbox = ArgumentCaptor.forClass(AiCommandOutbox.class);
        verify(outboxRepository).save(outbox.capture());
        assertThat(outbox.getValue().getPayload())
                .contains("\"provider\":\"openai\"", "\"model\":\"gpt-6-luna\"");
    }

    @Test
    void lint_writeStartsOperationAndQueuesCommand() {
        when(operationStarter.start("ws_1", "user_1")).thenReturn("op_lint_1");

        var result = service.lint("ws_1", "user_1", new WikiLintRequest(false, false));

        assertThat(result.path("operation_id").asText()).isEqualTo("op_lint_1");
        verify(outboxRepository).save(any(AiCommandOutbox.class));
    }

    @Test
    void lint_writeWithoutMaterializeField_materializesPromotions() {
        service.lint("ws_1", "user_1", new WikiLintRequest(null, false));

        ArgumentCaptor<AiCommandOutbox> outbox = ArgumentCaptor.forClass(AiCommandOutbox.class);
        verify(outboxRepository).save(outbox.capture());
        assertThat(outbox.getValue().getPayload()).contains("\"materialize_promotions\":true");
    }

    @Test
    void lint_explicitFalseOrDryRun_doesNotMaterialize() {
        service.lint("ws_1", "user_1", new WikiLintRequest(false, false));
        service.lint("ws_1", "user_1", new WikiLintRequest(null, true));

        ArgumentCaptor<AiCommandOutbox> outbox = ArgumentCaptor.forClass(AiCommandOutbox.class);
        verify(outboxRepository, org.mockito.Mockito.times(2)).save(outbox.capture());
        assertThat(outbox.getAllValues())
                .allSatisfy(saved -> assertThat(saved.getPayload()).contains("\"materialize_promotions\":false"));
    }

    @Test
    void lint_rejectsNonMemberBeforeQueue() {
        doThrow(new WorkspaceNotFoundException("ws_1"))
                .when(workspaceAccessGuard).requireMember("ws_1", "user_2");

        assertThatThrownBy(() -> service.lint("ws_1", "user_2", new WikiLintRequest(null, null)))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verify(outboxRepository, never()).save(any(AiCommandOutbox.class));
    }

    @Test
    void run_mapsFailureCodeToKoreanMessage() {
        when(runStatusRequester.find("11111111-1111-1111-1111-111111111111")).thenReturn(Optional.of(
                new PipelineRunStatusRequester.PipelineRunStatus(
                        "11111111-1111-1111-1111-111111111111", null, "ws_1", "user_1", "lint", "failed", "provider_unavailable", null)));

        var body = service.run("ws_1", "user_1", "11111111-1111-1111-1111-111111111111");

        assertThat(body.path("error_code").asText()).isEqualTo("provider_unavailable");
        assertThat(body.path("error").asText()).doesNotContain("provider_unavailable").contains("AI 서비스");
    }

    @Test
    void run_preservesPipelineNotFoundAs404() {
        when(runStatusRequester.find("22222222-2222-2222-2222-222222222222")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.run("ws_1", "user_1", "22222222-2222-2222-2222-222222222222"))
                .isInstanceOf(PipelineWikiMaintenanceException.class)
                .satisfies(error -> assertThat(
                        ((PipelineWikiMaintenanceException) error).getHttpStatus())
                        .isEqualTo(404));
    }

    @Test
    void run_nonUuidRunId_isNotFoundWithoutCallingAi() {
        assertThatThrownBy(() -> service.run("ws_1", "user_1", "not-a-uuid"))
                .isInstanceOf(PipelineWikiMaintenanceException.class)
                .satisfies(error -> assertThat(
                        ((PipelineWikiMaintenanceException) error).getHttpStatus()).isEqualTo(404));
        verify(runStatusRequester, never()).find(anyString());
    }

    @Test
    void run_beforeAiCreatesRun_isQueued() {
        when(runStatusRequester.find("33333333-3333-3333-3333-333333333333")).thenReturn(Optional.empty());
        when(outboxWriter.findRun("33333333-3333-3333-3333-333333333333", "ws_1", "user_1"))
                .thenReturn(Optional.of(java.util.Map.of("status", "running")));

        assertThat(service.run("ws_1", "user_1", "33333333-3333-3333-3333-333333333333").path("status").asText()).isEqualTo("queued");
    }

    @Test
    void run_cancellationBeforeAiCreatesRun_waitsThenReportsCancelled() {
        when(runStatusRequester.find("55555555-5555-5555-5555-555555555555")).thenReturn(Optional.empty());
        when(outboxWriter.findRun("55555555-5555-5555-5555-555555555555", "ws_1", "user_1"))
                .thenReturn(Optional.of(java.util.Map.of("status", "rolling_back")))
                .thenReturn(Optional.of(java.util.Map.of("status", "cancelled")));

        assertThat(service.run("ws_1", "user_1", "55555555-5555-5555-5555-555555555555").path("status").asText()).isEqualTo("queued");
        var done = service.run("ws_1", "user_1", "55555555-5555-5555-5555-555555555555");
        assertThat(done.path("status").asText()).isEqualTo("failed");
        assertThat(done.path("error_code").asText()).isEqualTo("cancelled");
        assertThat(done.path("error").asText()).isEqualTo("처리가 취소되었습니다.");
    }

    @Test
    void run_otherUsersRun_isNotFound() {
        when(runStatusRequester.find("66666666-6666-6666-6666-666666666666")).thenReturn(Optional.of(
                new PipelineRunStatusRequester.PipelineRunStatus(
                        "66666666-6666-6666-6666-666666666666", null, "ws_1", "user_2", "lint", "running", null, null)));

        assertThatThrownBy(() -> service.run("ws_1", "user_1", "66666666-6666-6666-6666-666666666666"))
                .isInstanceOf(PipelineWikiMaintenanceException.class);
    }

    @Test
    void run_failedBeforeAiCreatesRun_reportsFailure() {
        when(runStatusRequester.find("44444444-4444-4444-4444-444444444444")).thenReturn(Optional.empty());
        var row = new java.util.HashMap<String, Object>();
        row.put("status", "completed");
        row.put("error_code", "provider_unavailable");
        when(outboxWriter.findRun("44444444-4444-4444-4444-444444444444", "ws_1", "user_1")).thenReturn(Optional.of(row));

        var body = service.run("ws_1", "user_1", "44444444-4444-4444-4444-444444444444");

        assertThat(body.path("status").asText()).isEqualTo("failed");
        assertThat(body.path("error_code").asText()).isEqualTo("provider_unavailable");
        assertThat(body.path("error").asText()).contains("AI 서비스");
    }
}
