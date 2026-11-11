package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.Folder;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.McpDocumentDataGateway;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpHierarchyToolsTest {
    private final McpDocumentDataGateway data = mock(McpDocumentDataGateway.class);
    private final DocumentAccessPolicy.Viewer viewer = mock(DocumentAccessPolicy.Viewer.class);
    private final McpProperties props = McpTokenVerifierTest.props();
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpHierarchyTools tools = new McpHierarchyTools(data, new McpCursorCodec(mapper, props), props, mapper);
    private final McpIdentity identity = new McpIdentity("user", "workspace", "client", "grant", 1,
            Set.of("read"), "MEMBER", Set.of());
    private final UUID folderId = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    McpHierarchyToolsTest() {
        when(data.viewer(identity)).thenReturn(viewer);
        when(viewer.canRead(any())).thenReturn(true);
        when(viewer.canReadFolder(any())).thenReturn(true);
    }

    @Test void listsPlacementWithPaginationAndDetectsChanges() {
        Folder folder = new Folder(folderId, "workspace", null, "ai", 0);
        Document doc = document("doc_a");
        when(data.childFolders("workspace", null, props.maxCandidates() + 1)).thenReturn(List.of(folder));
        when(data.childDocuments("workspace", null, props.maxCandidates() + 1)).thenReturn(List.of(doc));
        var first = mapper.valueToTree(tools.invoke(identity, McpHierarchyTools.ROOT, paging(null, 1)));
        assertThat(first.path("workspace_id").asText()).isEqualTo("workspace");
        assertThat(first.path("truncated").asBoolean()).isTrue();
        String cursor = first.path("next_cursor").asText();
        var second = mapper.valueToTree(tools.invoke(identity, McpHierarchyTools.ROOT, paging(cursor, 1)));
        assertThat(second.path("items").size()).isEqualTo(1);
        assertThat(second.path("items").get(0).path("id").asText())
                .isNotEqualTo(first.path("items").get(0).path("id").asText());
        assertThat(second.path("next_cursor").isNull()).isTrue();
        when(data.childDocuments("workspace", null, props.maxCandidates() + 1)).thenReturn(List.of());
        assertThatThrownBy(() -> tools.invoke(identity, McpHierarchyTools.ROOT, paging(cursor, 1)))
                .hasMessage("STALE_CURSOR");
    }

    @Test void metadataUsesPlacementVersionWithoutReadingPdfOrMarkdownBody() {
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn("doc_pdf"); when(doc.getDisplayName()).thenReturn("<b>Paper</b>");
        when(doc.getFolderId()).thenReturn(folderId); when(doc.getCurrentVersion()).thenReturn(9L);
        when(doc.getSortOrder()).thenReturn(3L);
        when(data.find("workspace", "doc_pdf")).thenReturn(Optional.of(doc));
        var out = mapper.valueToTree(tools.invoke(identity, McpHierarchyTools.METADATA, Map.of("document_id", "doc_pdf")));
        assertThat(out.path("item").path("parent_id").asText()).isEqualTo(folderId.toString());
        assertThat(out.path("item").path("current_version").asLong()).isEqualTo(9);
        assertThat(out.path("item").path("position").asLong()).isEqualTo(3);
        assertThat(out.path("item").path("name").asText()).doesNotContain("<b>");
        verify(data, never()).currentBody(any(), anyInt());
        verify(data, never()).currentIdentity(any(), anyInt());
    }

    @Test void inaccessibleAndMissingTargetsAreIndistinguishable() {
        Document doc = document("doc_hidden");
        when(data.find("workspace", "doc_hidden")).thenReturn(Optional.of(doc));
        when(viewer.canRead(doc)).thenReturn(false);
        for (String id : List.of("doc_hidden", "doc_missing"))
            assertThatThrownBy(() -> tools.invoke(identity, McpHierarchyTools.METADATA, Map.of("document_id", id)))
                    .hasMessage("RESOURCE_NOT_FOUND");
        var args = children(null, 1);
        assertThatThrownBy(() -> tools.invoke(identity, McpHierarchyTools.CHILDREN, args)).hasMessage("RESOURCE_NOT_FOUND");
        when(data.findFolder("workspace", folderId)).thenReturn(Optional.of(new Folder(folderId, "workspace", null, "ai", 0)));
        when(viewer.canReadFolder(folderId)).thenReturn(false);
        assertThatThrownBy(() -> tools.invoke(identity, McpHierarchyTools.CHILDREN, args)).hasMessage("RESOURCE_NOT_FOUND");
        verify(data, never()).childFolders(anyString(), any(), anyInt());
    }

    @Test void childrenFilterUnreadableRowsAndBindCursorToParentAndPageSize() {
        when(data.findFolder("workspace", folderId)).thenReturn(Optional.of(new Folder(folderId, "workspace", null, "ai", 0)));
        var a = document("doc_a"); var b = document("doc_b"); var hidden = document("doc_hidden");
        when(viewer.canRead(hidden)).thenReturn(false);
        when(data.childDocuments("workspace", folderId, props.maxCandidates() + 1)).thenReturn(List.of(a, hidden, b));
        var out = mapper.valueToTree(tools.invoke(identity, McpHierarchyTools.CHILDREN, children(null, 1)));
        String cursor = out.path("next_cursor").asText();
        assertThat(out.toString()).doesNotContain("doc_hidden");
        assertThatThrownBy(() -> tools.invoke(identity, McpHierarchyTools.CHILDREN, children(cursor, 2)))
                .hasMessage("HANDLE_NOT_FOUND");
        var second = mapper.valueToTree(tools.invoke(identity, McpHierarchyTools.CHILDREN, children(cursor, 1)));
        assertThat(second.path("next_cursor").isNull()).isTrue();
        assertThatThrownBy(() -> tools.invoke(identity, McpHierarchyTools.ROOT, paging(cursor, 1)))
                .hasMessage("HANDLE_NOT_FOUND");
        var other = new McpIdentity("user", "workspace", "client", "other-grant", 1, Set.of("read"), "MEMBER", Set.of());
        when(data.viewer(other)).thenReturn(viewer);
        assertThatThrownBy(() -> tools.invoke(other, McpHierarchyTools.CHILDREN, children(cursor, 1)))
                .hasMessage("HANDLE_NOT_FOUND");
    }

    @Test void rejectsInjectedIdentityAndInvalidInputsBeforeReading() {
        var extra = paging(null, 1); extra.put("workspace_id", "other");
        for (var args : List.of(extra, paging(null, 0), paging(null, props.maxPageSize() + 1), paging(null, 1.5), paging(7, 1)))
            assertThatThrownBy(() -> tools.invoke(identity, McpHierarchyTools.ROOT, args)).hasMessage("INVALID_INPUT");
        assertThatThrownBy(() -> tools.invoke(identity, McpHierarchyTools.METADATA, Map.of("document_id", "../other")))
                .hasMessage("INVALID_INPUT");
        verify(data, never()).childDocuments(anyString(), any(), anyInt());
        verify(data, never()).find(anyString(), anyString());
    }

    @Test void candidateCapFailsInsteadOfSilentlyOmittingDocuments() {
        var rows = new ArrayList<Document>();
        for (int i = 0; i <= props.maxCandidates(); i++) rows.add(document("doc_" + i));
        when(data.childDocuments("workspace", null, props.maxCandidates() + 1)).thenReturn(rows);
        assertThatThrownBy(() -> tools.invoke(identity, McpHierarchyTools.ROOT, paging(null, 1)))
                .hasMessage("RESPONSE_LIMIT_EXCEEDED");
    }

    @Test void advertisesStrictReadOnlyContracts() {
        assertThat(tools.tools()).extracting(t -> t.name()).containsExactly(McpHierarchyTools.ROOT, McpHierarchyTools.CHILDREN, McpHierarchyTools.METADATA, McpHierarchyTools.SEARCH, McpHierarchyTools.BREADCRUMB);
        assertThat(tools.tools()).allSatisfy(t -> {
            assertThat(t.annotations().readOnlyHint()).isTrue();
            assertThat(t.inputSchema().get("additionalProperties")).isEqualTo(false);
            assertThat(t.outputSchema().get("additionalProperties")).isEqualTo(false);
            assertThat(t.description()).contains("metadata version", "propose_plan", "untrusted");
        });
    }

    @Test void searchIncludesFoldersAndDocumentsAndBindsQuery() {
        var folder = new Folder(folderId, "workspace", null, "ai", 1);
        when(data.searchFolders("workspace", "ai", props.maxCandidates() + 1)).thenReturn(List.of(folder));
        when(data.search("workspace", "ai", props.maxCandidates() + 1)).thenReturn(List.of(document("doc_ai")));
        var args = paging(null, 1); args.put("query", " ai ");
        var first = mapper.valueToTree(tools.invoke(identity, McpHierarchyTools.SEARCH, args));
        assertThat(first.path("query").asText()).isEqualTo("ai");
        assertThat(first.path("schema_version").asText()).isEqualTo("mcp.hierarchy-search.v1");
        args.put("cursor", first.path("next_cursor").asText());
        var second = mapper.valueToTree(tools.invoke(identity, McpHierarchyTools.SEARCH, args));
        assertThat(second.path("items").get(0).path("type").asText()).isEqualTo("folder");
        args.put("query", "other");
        assertThatThrownBy(() -> tools.invoke(identity, McpHierarchyTools.SEARCH, args)).hasMessage("HANDLE_NOT_FOUND");
        args.put("query", "  ");
        assertThatThrownBy(() -> tools.invoke(identity, McpHierarchyTools.SEARCH, args)).hasMessage("INVALID_INPUT");
    }

    @Test void breadcrumbReturnsRootFirstAndRejectsCyclesAndHiddenAncestors() {
        UUID child = UUID.fromString("22345678-1234-1234-1234-123456789abc");
        when(data.findFolder("workspace", folderId)).thenReturn(Optional.of(new Folder(folderId, "workspace", null, "ai", 0)));
        when(data.findFolder("workspace", child)).thenReturn(Optional.of(new Folder(child, "workspace", folderId, "attention", 0)));
        var args = new LinkedHashMap<String, Object>(); args.put("folder_id", child.toString()); args.put("document_id", null);
        var out = mapper.valueToTree(tools.invoke(identity, McpHierarchyTools.BREADCRUMB, args));
        assertThat(out.path("items")).extracting(node -> node.path("name").asText()).containsExactly("ai", "attention");
        when(viewer.canReadFolder(folderId)).thenReturn(false);
        assertThatThrownBy(() -> tools.invoke(identity, McpHierarchyTools.BREADCRUMB, args)).hasMessage("RESOURCE_NOT_FOUND");
        when(viewer.canReadFolder(folderId)).thenReturn(true);
        when(data.findFolder("workspace", folderId)).thenReturn(Optional.of(new Folder(folderId, "workspace", child, "ai", 0)));
        assertThatThrownBy(() -> tools.invoke(identity, McpHierarchyTools.BREADCRUMB, args)).hasMessage("DEPENDENCY_UNAVAILABLE");
        args.put("document_id", "doc_a");
        assertThatThrownBy(() -> tools.invoke(identity, McpHierarchyTools.BREADCRUMB, args)).hasMessage("INVALID_INPUT");
        args.put("folder_id", null);
        when(data.find("workspace", "doc_a")).thenReturn(Optional.of(document("doc_a")));
        var documentPath = mapper.valueToTree(tools.invoke(identity, McpHierarchyTools.BREADCRUMB, args));
        assertThat(documentPath.path("items").size()).isEqualTo(1);
        assertThat(documentPath.path("items").get(0).path("id").asText()).isEqualTo("doc_a");
    }

    private Map<String, Object> children(Object cursor, Number size) {
        var args = paging(cursor, size); args.put("folder_id", folderId.toString()); return args;
    }
    private static Map<String, Object> paging(Object cursor, Number size) {
        var args = new LinkedHashMap<String, Object>(); args.put("cursor", cursor); args.put("page_size", size); return args;
    }
    private static Document document(String id) {
        return new Document(id, "workspace", "user", id + ".md", "text/markdown", 1, null, "a".repeat(64));
    }
}
