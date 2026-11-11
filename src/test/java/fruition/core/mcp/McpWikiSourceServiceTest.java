package fruition.core.mcp;

import fruition.core.document.domain.Document;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.McpDocumentDataGateway;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class McpWikiSourceServiceTest {
    private final McpAccessClient access = mock(McpAccessClient.class);
    private final McpDocumentDataGateway documents = mock(McpDocumentDataGateway.class);
    private final McpProperties props = McpTokenVerifierTest.props();
    private final McpWikiSourceService service = new McpWikiSourceService(access, documents, props);
    private final McpProposalActor actor = new McpProposalActor(
            "00000000-0000-4000-8000-000000000002", "00000000-0000-4000-8000-000000000001",
            "user", "workspace", "https://client.example/client.json", 2,
            "MCP_CLIENT", "2025-11-25", "start_ingest");
    private final McpIdentity identity = new McpIdentity("user", "workspace", actor.clientId(), actor.grantId(),
            2, Set.of("wiki_write"), "MEMBER", Set.of("wiki.ingest"));

    @Test void returnsCurrentAuthoritativeSourceWithStableServerBlocksAndHashes() {
        String markdown = "# Heading\n\nParagraph";
        Document document = document("workspace", "text/markdown");
        authorized(document, markdown, 7, markdown.getBytes(StandardCharsets.UTF_8).length);

        var response = service.read(actor, "doc_1");

        assertThat(response.schemaVersion()).isEqualTo("mcp.wiki-source.v1");
        assertThat(response.documentId()).isEqualTo("doc_1");
        assertThat(response.displayName()).isEqualTo("Source");
        // AI reader는 정확한 필드 집합만 받는다.
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().convertValue(response, Map.class).keySet())
                .containsExactlyInAnyOrder("schema_version", "document_id", "display_name",
                "workspace_id", "source_version", "source_content_hash", "mime_type", "blocks");
        assertThat(response.workspaceId()).isEqualTo("workspace");
        assertThat(response.sourceVersion()).isEqualTo(7);
        assertThat(response.sourceContentHash()).isEqualTo("sha256:" + sha256(markdown));
        assertThat(response.blocks()).extracting(McpWikiSourceService.Block::blockId)
                .containsExactly("B0001", "B0002");
        assertThat(response.blocks()).allSatisfy(block ->
                assertThat(block.contentHash()).isEqualTo("sha256:" + sha256(block.text())));
        verify(access).authorizeWikiSource(actor);
        verify(documents).find("workspace", "doc_1");
    }

    @Test void usesCurrentAuthorizedWorkspaceAndHidesUnreadableDocument() {
        Document document = document("workspace", "text/markdown");
        when(access.authorizeWikiSource(actor)).thenReturn(identity);
        when(documents.find("workspace", "doc_1")).thenReturn(Optional.of(document));
        when(documents.viewer("workspace", "user", false, false)).thenReturn(
                new DocumentAccessPolicy.Viewer("user", false, false,
                        Map.of("doc_1", DocumentAccessPolicy.VIEW), Map.of(), Map.of()));
        when(documents.currentBody(document, props.maxSourceBytes())).thenReturn(Optional.of(
                new McpDocumentDataGateway.CurrentBody(1, sha256("body"), "body", 4)));
        service.read(actor, "doc_1");

        reset(documents);
        when(documents.find("workspace", "doc_1")).thenReturn(Optional.of(document));
        when(documents.viewer("workspace", "user", false, false)).thenReturn(
                new DocumentAccessPolicy.Viewer("user", false, false,
                        Map.of("doc_1", "invalid"), Map.of(), Map.of()));
        assertThatThrownBy(() -> service.read(actor, "doc_1"))
                .isInstanceOf(McpAuthorizationException.class).hasMessage("RESOURCE_NOT_FOUND");
        verify(documents, never()).find("other", "doc_1");
    }

    @Test void failsClosedForUnsupportedTypeRevisionHashAndConfiguredBounds() {
        Document pdf = document("workspace", "application/pdf");
        when(access.authorizeWikiSource(actor)).thenReturn(identity);
        when(documents.find("workspace", "doc_1")).thenReturn(Optional.of(pdf));
        when(documents.viewer("workspace", "user", false, false)).thenReturn(viewer());
        assertThatThrownBy(() -> service.read(actor, "doc_1"))
                .isInstanceOf(McpAuthorizationException.class).hasMessage("INVALID_SOURCE_TYPE");

        Document markdown = document("workspace", "text/markdown");
        when(documents.find("workspace", "doc_1")).thenReturn(Optional.of(markdown));
        when(documents.currentBody(markdown, props.maxSourceBytes())).thenReturn(Optional.of(
                new McpDocumentDataGateway.CurrentBody(0, sha256("body"), "body", 4)));
        assertThatThrownBy(() -> service.read(actor, "doc_1"))
                .isInstanceOf(McpAuthorizationException.class).hasMessage("DEPENDENCY_UNAVAILABLE");

        when(documents.currentBody(markdown, props.maxSourceBytes())).thenReturn(Optional.of(
                new McpDocumentDataGateway.CurrentBody(1, "0".repeat(64), "body", 4)));
        assertThatThrownBy(() -> service.read(actor, "doc_1"))
                .isInstanceOf(McpAuthorizationException.class).hasMessage("DEPENDENCY_UNAVAILABLE");

        when(documents.currentBody(markdown, props.maxSourceBytes())).thenReturn(Optional.of(
                new McpDocumentDataGateway.CurrentBody(1, sha256("body"), null, props.maxSourceBytes() + 1)));
        assertThatThrownBy(() -> service.read(actor, "doc_1"))
                .isInstanceOf(McpAuthorizationException.class).hasMessage("SOURCE_LIMIT_EXCEEDED");
    }

    private void authorized(Document document, String body, long version, int bytes) {
        when(access.authorizeWikiSource(actor)).thenReturn(identity);
        when(documents.find("workspace", "doc_1")).thenReturn(Optional.of(document));
        when(documents.viewer("workspace", "user", false, false)).thenReturn(viewer());
        when(documents.currentBody(document, props.maxSourceBytes())).thenReturn(Optional.of(
                new McpDocumentDataGateway.CurrentBody(version, sha256(body), body, bytes)));
    }
    private static DocumentAccessPolicy.Viewer viewer() {
        return new DocumentAccessPolicy.Viewer("user", false, false, Map.of(), Map.of(), Map.of());
    }
    private static Document document(String workspace, String mime) {
        return new Document("doc_1", workspace, "other", "Source.md", mime, 1, null, sha256("body"));
    }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
