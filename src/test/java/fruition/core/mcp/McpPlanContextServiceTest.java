package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.Folder;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.McpDocumentDataGateway;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpPlanContextServiceTest {
    private final McpAccessClient access = mock(McpAccessClient.class);
    private final McpDocumentDataGateway data = mock(McpDocumentDataGateway.class);
    private final DocumentAccessPolicy.Viewer viewer = mock(DocumentAccessPolicy.Viewer.class);
    private final McpProperties properties = McpTokenVerifierTest.props();
    private final McpPlanContextService service = new McpPlanContextService(
            access, data, properties, new ObjectMapper());
    private final McpProposalActor actor = new McpProposalActor(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), "user", "workspace",
            "https://client.example/client.json", 3, "MCP_CLIENT", "2025-11-25", "propose_plan");
    private final McpIdentity identity = new McpIdentity(
            "user", "workspace", actor.clientId(), actor.grantId(), 3,
            Set.of("write_propose"), "MEMBER", Set.of());

    @Test void returnsAuthoritativeRowsAndEditBodyWithoutClientSnapshots() {
        Document document = new Document("doc_1", "workspace", "owner", "Document.md",
                "text/markdown", 4, null, sha256("body"));
        when(access.authorizeProposal(actor)).thenReturn(identity);
        when(data.viewer(identity)).thenReturn(viewer);
        when(viewer.canRead(document)).thenReturn(true);
        when(data.find("workspace", "doc_1"))
                .thenReturn(Optional.of(document));
        when(data.childDocuments("workspace", null, 2001)).thenReturn(List.of(document));
        when(data.childFolders("workspace", null, 2001)).thenReturn(List.of());
        when(data.currentBody(document, properties.maxSourceBytes())).thenReturn(Optional.of(
                new McpDocumentDataGateway.CurrentBody(1, sha256("body"), "body", 4)));

        var response = service.read(actor, null,
                List.of(new McpPlanContextService.Resource("document", "doc_1", 1L)),
                List.of(new McpPlanContextService.EditDocument("doc_1", 1)));

        assertThat(response.schemaVersion()).isEqualTo("mcp.plan-context.v1");
        assertThat(response.workspaceId()).isEqualTo("workspace");
        assertThat(response.hierarchy()).extracting(McpPlanContextService.HierarchyItem::id)
                .containsExactly("doc_1");
        assertThat(response.editDocuments()).singleElement().satisfies(edit -> {
            assertThat(edit.currentVersion()).isOne();
            assertThat(edit.currentMarkdown()).isEqualTo("body");
            assertThat(edit.contentHash()).isEqualTo("sha256:" + sha256("body"));
        });
        verify(data).requireEdit(document, identity);
    }

    @Test void rejectsStaleVersionAndEditOutsideVersionBoundResources() {
        Document document = new Document("doc_1", "workspace", "owner", "Document.md",
                "text/markdown", 4, null, sha256("body"));
        when(access.authorizeProposal(actor)).thenReturn(identity);
        when(data.viewer(identity)).thenReturn(viewer);
        when(data.find("workspace", "doc_1"))
                .thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.read(actor, null,
                List.of(new McpPlanContextService.Resource("document", "doc_1", 2L)), List.of()))
                .isInstanceOf(McpAuthorizationException.class).hasMessage("SOURCE_CHANGED");
        assertThatThrownBy(() -> service.read(actor, null,
                List.of(new McpPlanContextService.Resource("document", "doc_1", null)),
                List.of(new McpPlanContextService.EditDocument("doc_1", 1))))
                .isInstanceOf(McpAuthorizationException.class).hasMessage("INVALID_INPUT");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
