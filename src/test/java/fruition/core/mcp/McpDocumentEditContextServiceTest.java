package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.domain.Document;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import fruition.core.document.service.McpDocumentDataGateway;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class McpDocumentEditContextServiceTest {
    private final McpAccessClient access = mock(McpAccessClient.class);
    private final McpDocumentDataGateway documents = mock(McpDocumentDataGateway.class);
    private final McpProperties props = McpTokenVerifierTest.props();
    private final McpDocumentEditContextService service =
            new McpDocumentEditContextService(access, documents, props, new ObjectMapper());
    private final McpProposalActor actor = new McpProposalActor(
            "00000000-0000-4000-8000-000000000002",
            "00000000-0000-4000-8000-000000000001",
            "user", "workspace", "https://client.example/client.json", 2,
            "MCP_CLIENT", "2025-11-25", "propose_document_edit");
    private final McpIdentity identity = new McpIdentity(
            "user", "workspace", actor.clientId(), actor.grantId(), 2,
            Set.of("write_propose"), "MEMBER", Set.of());

    @Test void returnsOnlyBoundedCurrentAuthorizedDocumentContext() throws Exception {
        String markdown = "# Current\nbody";
        Document document = document("workspace");
        when(access.authorizeProposal(actor)).thenReturn(identity);
        when(documents.find("workspace", "doc_1")).thenReturn(Optional.of(document));
        when(documents.currentBody(document, props.maxSourceBytes())).thenReturn(Optional.of(
                new McpDocumentDataGateway.CurrentBody(3, sha256(markdown), markdown,
                        markdown.getBytes(StandardCharsets.UTF_8).length)));

        var response = service.read(actor, "doc_1", 3);

        assertThat(response.schemaVersion()).isEqualTo("mcp.document-edit-context.v1");
        assertThat(response.documentId()).isEqualTo("doc_1");
        assertThat(response.workspaceId()).isEqualTo("workspace");
        assertThat(response.currentVersion()).isEqualTo(3);
        assertThat(response.contentHash()).isEqualTo("sha256:" + sha256(markdown));
        assertThat(response.currentMarkdown()).isEqualTo(markdown);
        verify(documents).find("workspace", "doc_1");
        verify(documents).requireEdit(document, identity);
        verify(documents).currentBody(document, props.maxSourceBytes());
        verifyNoMoreInteractions(documents);
    }

    @Test void usesAuthorizedWorkspaceAndRejectsCurrentRoleBaseAndHashFailures() throws Exception {
        Document document = document("workspace");
        when(access.authorizeProposal(actor)).thenReturn(identity);
        when(documents.find("workspace", "doc_1")).thenReturn(Optional.of(document));
        doThrow(new DocumentWriteForbiddenException("denied")).when(documents).requireEdit(document, identity);
        assertThatThrownBy(() -> service.read(actor, "doc_1", 3))
                .isInstanceOf(McpAuthorizationException.class).hasMessage("PERMISSION_REVOKED");
        verify(documents, never()).find("other", "doc_1");

        reset(documents);
        when(documents.find("workspace", "doc_1")).thenReturn(Optional.of(document));
        when(documents.currentBody(document, props.maxSourceBytes())).thenReturn(Optional.of(
                new McpDocumentDataGateway.CurrentBody(2, sha256("body"), "body", 4)));
        assertThatThrownBy(() -> service.read(actor, "doc_1", 3))
                .isInstanceOf(McpAuthorizationException.class).hasMessage("SOURCE_CHANGED");

        when(documents.currentBody(document, props.maxSourceBytes())).thenReturn(Optional.of(
                new McpDocumentDataGateway.CurrentBody(3, "0".repeat(64), "body", 4)));
        assertThatThrownBy(() -> service.read(actor, "doc_1", 3))
                .isInstanceOf(McpAuthorizationException.class).hasMessage("DEPENDENCY_UNAVAILABLE");
    }

    private static Document document(String workspaceId) {
        return new Document("doc_1", workspaceId, "owner", "Document.md", "text/markdown",
                3, null, sha256("# Current\nbody"));
    }

    private static String sha256(String markdown) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(markdown.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
