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
                org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class)));
        service = new WikiMaintenanceService(workspaceAccessGuard, operationStarter, outboxWriter,
                runStatusRequester, lintStateRepository, wikiStateRequester, objectMapper,
                "ai.maintenance.command", workspaceAiModelClient);
        org.mockito.Mockito.lenient().when(workspaceAiModelClient.get("ws_1"))
                .thenReturn(new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-5-nano"));
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
                .contains("\"provider\":\"openai\"", "\"model\":\"gpt-5-nano\"");
    }

    @Test
    void lint_writeStartsOperationAndQueuesCommand() {
        when(operationStarter.start("ws_1", "user_1")).thenReturn("op_lint_1");

        var result = service.lint("ws_1", "user_1", new WikiLintRequest(false, false));

        assertThat(result.path("operation_id").asText()).isEqualTo("op_lint_1");
        verify(outboxRepository).save(any(AiCommandOutbox.class));
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
    void run_preservesPipelineNotFoundAs404() {
        when(runStatusRequester.find("missing-run")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.run("ws_1", "user_1", "missing-run"))
                .isInstanceOf(PipelineWikiMaintenanceException.class)
                .satisfies(error -> assertThat(
                        ((PipelineWikiMaintenanceException) error).getHttpStatus())
                        .isEqualTo(404));
    }
}
