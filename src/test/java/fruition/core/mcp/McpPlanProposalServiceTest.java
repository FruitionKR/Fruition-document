package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpPlanProposalServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpPlanContextService contexts = mock(McpPlanContextService.class);
    private final McpDocumentProposalService proposals = mock(McpDocumentProposalService.class);
    private final McpPlanProposalService service = new McpPlanProposalService(contexts, proposals, mapper);
    private final McpIdentity identity = new McpIdentity("user", "workspace", "https://client.example/mcp",
            "00000000-0000-4000-8000-000000000001", 3, Set.of("write_propose"), "MEMBER", Set.of());

    @Test
    void submitsExactPlanThroughSharedProposalFlowAfterContextPreflight() {
        Map<String, Object> input = createFolderThenDocument();
        var expected = new McpDocumentProposalService.Result("run-1", "awaiting_approval", "https://review/run-1");
        when(proposals.submit(any(), any())).thenReturn(expected);

        assertThat(service.propose(identity, input)).isEqualTo(expected);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<McpPlanContextService.Resource>> resources = ArgumentCaptor.forClass(List.class);
        verify(contexts).read(any(), org.mockito.ArgumentMatchers.isNull(), resources.capture(),
                org.mockito.ArgumentMatchers.eq(List.of()));
        assertThat(resources.getValue()).isEmpty();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(proposals).submit(org.mockito.ArgumentMatchers.eq(identity), request.capture());
        assertThat(request.getValue().keySet()).containsExactly(
                "schema_version", "actor", "idempotency_key", "request_hash", "proposal");
        Map<?, ?> proposal = (Map<?, ?>) request.getValue().get("proposal");
        assertThat(proposal.get("instruction")).isNull();
        assertThat(proposal.get("operations")).isEqualTo(input.get("operations"));
        assertThat(((Map<?, ?>) request.getValue().get("actor")).get("tool_name")).isEqualTo("propose_plan");
    }

    @Test
    void rejectsUnknownFieldsAndArtifactHashMismatchBeforeDependencies() {
        Map<String, Object> unknown = new LinkedHashMap<>(createFolderThenDocument());
        unknown.put("user_id", "forged");
        assertThatThrownBy(() -> service.propose(identity, unknown))
                .isInstanceOf(McpToolException.class).hasMessage("INVALID_INPUT");

        Map<String, Object> mismatch = createFolderThenDocument();
        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) ((List<?>) mismatch.get("artifacts")).getFirst();
        artifact.put("content_hash", "sha256:" + "0".repeat(64));
        assertThatThrownBy(() -> service.propose(identity, mismatch))
                .isInstanceOf(McpToolException.class).hasMessage("INVALID_INPUT");
    }

    @Test
    void resultReferencesRequireOrderedDependencyAndCorrectProducer() {
        Map<String, Object> invalid = createFolderThenDocument();
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) invalid.get("operations")).getFirst();
        String existingFolder = "00000000-0000-4000-8000-000000000099";
        first.put("tool_name", "rename_folder");
        first.put("target_id", existingFolder);
        first.put("base_version", 1);
        first.put("arguments", linked("folder_id", existingFolder, "name", "Renamed", "base_version", 1));
        assertThatThrownBy(() -> service.propose(identity, invalid))
                .isInstanceOf(McpToolException.class).hasMessage("INVALID_INPUT");
    }

    private static Map<String, Object> createFolderThenDocument() {
        String contentHash = hash("body");
        Map<String, Object> destination = linked("$operation_result", "op-1", "field", "id");
        Map<String, Object> folder = linked(
                "id", "op-1", "sequence", 1, "tool_name", "create_folder", "target_type", "folder",
                "target_id", null, "base_version", null, "source_parent_id", null,
                "destination_parent_id", null, "arguments", linked("name", "Folder", "parent_folder_id", null),
                "reason", "Create folder", "depends_on", List.of());
        Map<String, Object> document = linked(
                "id", "op-2", "sequence", 2, "tool_name", "create_document", "target_type", "document",
                "target_id", null, "base_version", null, "source_parent_id", null,
                "destination_parent_id", null, "arguments", linked("display_name", "Document", "folder_id", destination,
                        "content_artifact_id", "artifact-1", "content_hash", contentHash),
                "reason", "Create document", "depends_on", List.of("op-1"));
        Map<String, Object> artifact = linked(
                "id", "artifact-1", "content_hash", contentHash, "purpose", "create_document",
                "document_id", null, "base_version", null, "target", null, "markdown", "body");
        return linked("idempotency_key", "key-1", "request_summary", "Build structure",
                "target_folder_id", null, "operations", new ArrayList<>(List.of(folder, document)),
                "artifacts", new ArrayList<>(List.of(artifact)));
    }

    private static String hash(String value) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2)
            result.put((String) values[index], values[index + 1]);
        return result;
    }
}
