package fruition.core.document.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.authz.WorkspaceAiModelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** ingest command를 현재 DB 트랜잭션의 outbox에 저장한다. */
@Component
public class IngestCommandOutbox {

    private final AiCommandOutboxWriter writer;
    private final String commandTopic;
    private final WorkspaceAiModelClient workspaceAiModelClient;
    private final ObjectMapper objectMapper;

    public IngestCommandOutbox(AiCommandOutboxWriter writer,
                               @Value("${app.processing.command-topic}") String commandTopic,
                               WorkspaceAiModelClient workspaceAiModelClient,
                               ObjectMapper objectMapper) {
        this.writer = writer;
        this.commandTopic = commandTopic;
        this.workspaceAiModelClient = workspaceAiModelClient;
        this.objectMapper = objectMapper;
    }

    public void begin(String runId, String workspaceId, String userId) {
        writer.begin(runId, workspaceId, userId, "document");
    }

    public void enqueue(String runId, String documentId, String userId, String workspaceId,
                        String selectionMode, String inputMarkdown, String inputBlocksJson, boolean chatWiki,
                        String operationId, long sourceRevision, String sourceContentHash) {
        WorkspaceAiModelClient.AiModelSelection aiModel = workspaceAiModelClient.get(workspaceId);
        IngestCommand command = new IngestCommand(
                runId,
                chatWiki ? "chat_wiki" : "document",
                documentId,
                userId,
                workspaceId,
                aiModel.provider(),
                aiModel.model(),
                selectionMode,
                inputMarkdown,
                readTree(inputBlocksJson),
                operationId,
                sourceRevision,
                sourceContentHash
        );
        writer.enqueue(runId, commandTopic, documentId, command);
    }

    /** documents.pipeline_input_blocks는 command payload와 같은 JSON이라 그대로 실어 보낸다. */
    private JsonNode readTree(String inputBlocksJson) {
        if (inputBlocksJson == null || inputBlocksJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(inputBlocksJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("채팅 source block을 읽을 수 없습니다.", e);
        }
    }

    public void enqueueDelete(String documentId, String workspaceId) {
        String runId = UUID.randomUUID().toString();
        writer.enqueue(runId, commandTopic, documentId,
                new DeleteCommand(runId, "document_deleted", documentId, workspaceId));
    }

    record IngestCommand(
            @JsonProperty("run_id") String runId,
            String kind,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("workspace_id") String workspaceId,
            String provider,
            String model,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("selection_mode") String selectionMode,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("input_markdown") String inputMarkdown,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("input_blocks") JsonNode inputBlocks,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("operation_id") String operationId,
            @JsonProperty("source_revision") long sourceRevision,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("source_content_hash") String sourceContentHash
    ) {}

    record DeleteCommand(
            @JsonProperty("run_id") String runId,
            String kind,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("workspace_id") String workspaceId
    ) {}
}
