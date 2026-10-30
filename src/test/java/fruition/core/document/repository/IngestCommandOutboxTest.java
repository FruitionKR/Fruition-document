package fruition.core.document.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.document.domain.AiCommandOutbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class IngestCommandOutboxTest {

    @Mock AiCommandOutboxRepository outboxRepository;

    @Test
    void enqueue_serializesProviderAndModelAtOutboxBoundary() {
        ObjectMapper objectMapper = new ObjectMapper();
        AiCommandOutboxWriter writer = spy(new AiCommandOutboxWriter(outboxRepository, objectMapper,
                org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
                org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class),
                org.mockito.Mockito.mock(fruition.core.usage.service.CreditService.class)));
        IngestCommandOutbox outbox = new IngestCommandOutbox(
                writer,
                "ai.ingest.command", objectMapper);

        outbox.enqueue("run_1", "doc_1", "user_1", "ws_1", "full", "# markdown",
                "[{\"block_id\":\"session_1:pair_1\",\"text\":\"Q : 질문\\nA : 답변\"}]", true,
                "op_1", 3, "sha256:source",
                new WorkspaceAiModelClient.AiModelSelection("gemini", "gemini-3.5-flash-lite"));

        ArgumentCaptor<IngestCommandOutbox.IngestCommand> command =
                ArgumentCaptor.forClass(IngestCommandOutbox.IngestCommand.class);
        verify(writer).enqueue(eq("run_1"), eq("ai.ingest.command"), eq("doc_1"), command.capture());
        ArgumentCaptor<AiCommandOutbox> saved = ArgumentCaptor.forClass(AiCommandOutbox.class);
        verify(outboxRepository).save(saved.capture());
        assertThat(saved.getValue().getPayload())
                .contains("\"kind\":\"chat_wiki\"", "\"provider\":\"gemini\"",
                        "\"model\":\"gemini-3.5-flash-lite\"",
                        "\"block_id\":\"session_1:pair_1\"");
    }

    @Test
    void enqueueRename_serializesDocumentRenamedWithoutUser() {
        ObjectMapper objectMapper = new ObjectMapper();
        AiCommandOutboxWriter writer = spy(new AiCommandOutboxWriter(outboxRepository, objectMapper,
                org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
                org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class),
                org.mockito.Mockito.mock(fruition.core.usage.service.CreditService.class)));
        IngestCommandOutbox outbox = new IngestCommandOutbox(
                writer, "ai.ingest.command", objectMapper);

        outbox.enqueueRename("doc_1", "ws_1", "새 이름");

        verify(writer).enqueue(org.mockito.ArgumentMatchers.anyString(), eq("ai.ingest.command"), eq("doc_1"),
                org.mockito.ArgumentMatchers.any(IngestCommandOutbox.RenameCommand.class));
        ArgumentCaptor<AiCommandOutbox> saved = ArgumentCaptor.forClass(AiCommandOutbox.class);
        verify(outboxRepository).save(saved.capture());
        assertThat(saved.getValue().getPayload())
                .contains("\"kind\":\"document_renamed\"", "\"document_id\":\"doc_1\"",
                        "\"workspace_id\":\"ws_1\"", "\"title\":\"새 이름\"")
                .doesNotContain("user_id");
    }
}
