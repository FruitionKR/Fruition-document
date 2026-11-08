package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpDocumentProposalServiceTest {
    private final McpDocumentEditContextService contexts = mock(McpDocumentEditContextService.class);
    private final McpAgentProposalClient client = mock(McpAgentProposalClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpDocumentProposalService service = new McpDocumentProposalService(
            contexts, client, mapper, "https://app.example");
    private final McpIdentity owner = new McpIdentity(
            "user-1", "workspace-1", "https://client.example/metadata",
            "00000000-0000-4000-8000-000000000001", 1,
            Set.of("write_propose"), "OWNER", Set.of());

    @Test void mapsVerifiedOwnerAndCurrentContextToOneOperationProposal() {
        when(contexts.read(any(), anyString(), anyLong())).thenReturn(context("# old\nbody", null));
        when(client.create(any())).thenReturn(new McpAgentProposalClient.Response(
                "mcp.agent-proposal.v1", "00000000-0000-4000-8000-000000000003",
                "00000000-0000-4000-8000-000000000004", 1, "a".repeat(64), "awaiting_approval"));

        var result = service.propose(owner, arguments("Fix title", "# new\nbody"));

        assertThat(result).isEqualTo(new McpDocumentProposalService.Result(
                "00000000-0000-4000-8000-000000000003", "awaiting_approval",
                "https://app.example/mcp/workspaces/workspace-1/runs/00000000-0000-4000-8000-000000000003"));
        var actor = org.mockito.ArgumentCaptor.forClass(McpProposalActor.class);
        verify(contexts).read(actor.capture(), org.mockito.ArgumentMatchers.eq("doc_1"),
                org.mockito.ArgumentMatchers.eq(1L));
        assertThat(actor.getValue().userId()).isEqualTo("user-1");
        assertThat(actor.getValue().workspaceId()).isEqualTo("workspace-1");
        assertThat(actor.getValue().clientId()).isEqualTo("https://client.example/metadata");
        assertThat(actor.getValue().grantId()).isEqualTo("00000000-0000-4000-8000-000000000001");
        assertThat(actor.getValue().policyGeneration()).isEqualTo(1);
        assertThat(actor.getValue().toolName()).isEqualTo("propose_document_edit");

        @SuppressWarnings("unchecked")
        var request = org.mockito.ArgumentCaptor.forClass((Class<Map<String,Object>>) (Class<?>) Map.class);
        verify(client).create(request.capture());
        Map<String,Object> proposal = map(request.getValue().get("proposal"));
        assertThat(proposal.get("instruction")).isEqualTo("Fix title");
        assertThat(proposal.get("request_summary")).isEqualTo("Fix title");
        assertThat((java.util.List<?>) proposal.get("operations")).hasSize(1);
        assertThat((java.util.List<?>) proposal.get("artifacts")).hasSize(1);
        assertThat(request.getValue()).doesNotContainKeys("workspace_id", "user_id", "redirect_url");
    }

    @Test void canonicalHashPreservesRawInstructionAndExcludesInvocation() {
        var first = McpDocumentProposalService.request(
                McpAgentProposalClientTest.actor("00000000-0000-4000-8000-000000000002"),
                McpAgentProposalClientTest.input("Fix title"), null, mapper);
        var retry = McpDocumentProposalService.request(
                McpAgentProposalClientTest.actor("00000000-0000-4000-8000-000000000099"),
                McpAgentProposalClientTest.input("Fix title"), null, mapper);
        var trailing = McpDocumentProposalService.request(
                McpAgentProposalClientTest.actor("00000000-0000-4000-8000-000000000002"),
                McpAgentProposalClientTest.input("Fix title "), null, mapper);

        assertThat(first.get("request_hash"))
                .isEqualTo("sha256:9e02041dfc0c922640e56e612b235890e99d3b1074d13a67893fdfb577725c03");
        assertThat(retry.get("request_hash")).isEqualTo(first.get("request_hash"));
        assertThat(trailing.get("request_hash"))
                .isEqualTo("sha256:5c959495edcb3e8504cd4b8767b390baa7e21328819f1ab0bee12dc81a90be2f");
        Map<String,Object> trailingProposal = map(trailing.get("proposal"));
        assertThat(trailingProposal.get("instruction")).isEqualTo("Fix title ");
        assertThat(trailingProposal.get("request_summary")).isEqualTo("Fix title");
    }

    @Test void failsClosedOnCurrentAuthorizationBaseAndGenerationFailuresBeforeAi() {
        org.mockito.Mockito.doThrow(new McpAuthorizationException(403, "PERMISSION_REVOKED"))
                .when(contexts).read(any(), anyString(), anyLong());
        assertThatThrownBy(() -> service.propose(owner, arguments("Fix title", "x")))
                .isInstanceOf(McpToolException.class).hasMessage("PERMISSION_REVOKED");

        org.mockito.Mockito.doThrow(new McpAuthorizationException(409, "SOURCE_CHANGED"))
                .when(contexts).read(any(), anyString(), anyLong());
        assertThatThrownBy(() -> service.propose(owner, arguments("Fix title", "x")))
                .isInstanceOf(McpToolException.class).hasMessage("SOURCE_CHANGED");

        org.mockito.Mockito.doThrow(new McpAuthorizationException(503, "AUTHZ_UNAVAILABLE"))
                .when(contexts).read(any(), anyString(), anyLong());
        assertThatThrownBy(() -> service.propose(owner, arguments("Fix title", "x")))
                .isInstanceOf(McpToolException.class).hasMessage("AUTHZ_UNAVAILABLE");
        verify(client, never()).create(any());
    }

    @Test void missingOrInvalidReviewOriginFailsClosedWithoutCreatingRemoteRun() {
        var unconfigured = new McpDocumentProposalService(contexts, client, mapper, "");

        assertThatThrownBy(() -> unconfigured.propose(owner, arguments("Fix title", "x")))
                .isInstanceOf(McpToolException.class).hasMessage("DEPENDENCY_UNAVAILABLE");
        for (String invalid : java.util.List.of(
                "http://app.example", "https://user@app.example", "https://app.example/path",
                "https://app.example?query", "https://app.example#fragment")) {
            assertThatThrownBy(() -> new McpDocumentProposalService(contexts, client, mapper, invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("MCP review origin must be a canonical HTTPS origin");
        }
        verify(contexts, never()).read(any(), anyString(), anyLong());
        verify(client, never()).create(any());
    }

    @Test void encodesTrustedWorkspaceAsOneReviewRouteSegment() {
        var encodedOwner = new McpIdentity(
                "user-1", "workspace /한글", owner.clientId(), owner.grantId(), owner.policyGeneration(),
                owner.scopes(), owner.role(), owner.permissions());
        when(contexts.read(any(), anyString(), anyLong())).thenReturn(new McpDocumentEditContextService.Response(
                "mcp.document-edit-context.v1", "doc_1", encodedOwner.workspaceId(), 1,
                "sha256:" + "a".repeat(64), "body", "Document", null));
        when(client.create(any())).thenReturn(new McpAgentProposalClient.Response(
                "mcp.agent-proposal.v1", "00000000-0000-4000-8000-000000000003",
                "00000000-0000-4000-8000-000000000004", 1, "a".repeat(64), "awaiting_approval"));

        var result = service.propose(encodedOwner, arguments("Fix title", "new"));

        assertThat(result.reviewUrl()).isEqualTo(
                "https://app.example/mcp/workspaces/workspace%20%2F%ED%95%9C%EA%B8%80/runs/00000000-0000-4000-8000-000000000003");
    }

    @Test void rejectsForeignWorkspaceAndInvalidRangeBeforeAi() {
        when(contexts.read(any(), anyString(), anyLong())).thenReturn(new McpDocumentEditContextService.Response(
                "mcp.document-edit-context.v1", "doc_1", "workspace-2", 1,
                "sha256:" + "a".repeat(64), "one", "Document", null));
        assertThatThrownBy(() -> service.propose(owner, arguments("Fix title", "x")))
                .isInstanceOf(McpToolException.class).hasMessage("SOURCE_CHANGED");

        when(contexts.read(any(), anyString(), anyLong())).thenReturn(context("one", null));
        Map<String,Object> range = new HashMap<>(arguments("Fix title", "x"));
        range.put("target", Map.of("type", "selection", "start_line", 1, "end_line", 2));
        assertThatThrownBy(() -> service.propose(owner, range))
                .isInstanceOf(McpToolException.class).hasMessage("SOURCE_CHANGED");
        verify(client, never()).create(any());
    }

    @Test void rejectsUnknownFractionalBooleanAndOversizedInputs() {
        Map<String,Object> unknown = new HashMap<>(arguments("Fix title", "x"));
        unknown.put("actor", "forged");
        assertInvalid(unknown);

        Map<String,Object> fractional = new HashMap<>(arguments("Fix title", "x"));
        fractional.put("base_version", 1.0);
        assertInvalid(fractional);

        Map<String,Object> bool = new HashMap<>(arguments("Fix title", "x"));
        bool.put("base_version", true);
        assertInvalid(bool);

        Map<String,Object> nonDocumentId = new HashMap<>(arguments("Fix title", "x"));
        nonDocumentId.put("document_id", "folder_1");
        assertInvalid(nonDocumentId);

        Map<String,Object> oversized = new HashMap<>(arguments("Fix title", "x"));
        oversized.put("replacement_markdown", "x".repeat(4 * 1024 * 1024 + 1));
        assertInvalid(oversized);
        verify(contexts, never()).read(any(), anyString(), anyLong());
    }

    private void assertInvalid(Map<String,Object> value) {
        assertThatThrownBy(() -> service.propose(owner, value))
                .isInstanceOf(McpToolException.class).hasMessage("INVALID_INPUT");
    }

    private static Map<String,Object> arguments(String instruction, String replacement) {
        return Map.of(
                "idempotency_key", "request-1", "document_id", "doc_1", "base_version", 1,
                "target", Map.of("type", "selection", "start_line", 1, "end_line", 1),
                "instruction", instruction, "replacement_markdown", replacement);
    }

    private static McpDocumentEditContextService.Response context(String markdown, String parentFolderId) {
        return new McpDocumentEditContextService.Response("mcp.document-edit-context.v1", "doc_1", "workspace-1",
                1, "sha256:" + "a".repeat(64), markdown, "Document", parentFolderId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value) {
        return (Map<String,Object>) value;
    }
}
