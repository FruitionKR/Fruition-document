package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.domain.Document;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.McpDocumentDataGateway;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpDocumentReadServiceTest {
    @Test void candidateAndBlockCapsFailWithoutSilentTruncation() {
        var repository = mock(McpDocumentDataGateway.class);
        var props = McpTokenVerifierTest.props();
        var service = new McpDocumentReadService(repository,
                new McpCursorCodec(new ObjectMapper(), props), props, new ObjectMapper());
        var identity = new McpIdentity("user", "workspace", "https://client.example/client.json", "grant", 1,
                Set.of("read"), "MEMBER", Set.of());
        var viewer = new DocumentAccessPolicy.Viewer("user", false, false, Map.of(), Map.of(), Map.of());
        when(repository.viewer("workspace", "user", false, false)).thenReturn(viewer);
        var tooMany = new ArrayList<Document>();
        for (int i = 0; i <= props.maxCandidates(); i++) tooMany.add(document("doc_" + i));
        when(repository.search("workspace", "q", props.maxCandidates() + 1)).thenReturn(tooMany);
        assertThatThrownBy(() -> service.find(identity, "q", null, 1)).isInstanceOf(McpToolException.class)
                .hasMessage("RESPONSE_LIMIT_EXCEEDED");

        Document d = document("doc_a");
        when(repository.find("workspace", "doc_a")).thenReturn(Optional.of(d));
        when(repository.currentBody(d, props.maxSourceBytes())).thenReturn(Optional.of(
                new McpDocumentDataGateway.CurrentBody(1, d.getCurrentContentHash(), null,
                        props.maxSourceBytes() + 1)));
        assertThatThrownBy(() -> service.get(identity, "doc_a", null)).isInstanceOf(McpToolException.class)
                .hasMessage("RESPONSE_LIMIT_EXCEEDED");
    }

    @Test void sanitizesCurrentMarkdownWithoutChangingItsStoredHash() {
        var repository = mock(McpDocumentDataGateway.class); var props = McpTokenVerifierTest.props();
        var service = new McpDocumentReadService(repository,
                new McpCursorCodec(new ObjectMapper(), props), props, new ObjectMapper());
        var identity = new McpIdentity("user", "workspace", "https://client.example/client.json", "grant", 1,
                Set.of("read"), "MEMBER", Set.of());
        var viewer = new DocumentAccessPolicy.Viewer("user", false, false, Map.of(), Map.of(), Map.of());
        Document document = document("doc_safe");
        String markdown = """
                [external](https://evil.example/path) ![pixel](https://evil.example/p.png)

                <b>raw</b> [relative](/docs) [anchor](#part) `https://code.example`
                """;
        when(repository.viewer("workspace", "user", false, false)).thenReturn(viewer);
        when(repository.find("workspace", "doc_safe")).thenReturn(Optional.of(document));
        when(repository.currentBody(document, props.maxSourceBytes())).thenReturn(Optional.of(
                new McpDocumentDataGateway.CurrentBody(2, "a".repeat(64), markdown,
                        markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)));

        var response = service.get(identity, "doc_safe", null);
        String text = response.blocks().stream().map(McpDocumentReadService.Block::text)
                .collect(java.util.stream.Collectors.joining());
        assertThat(response.currentVersion()).isEqualTo(2);
        assertThat(response.contentHash()).isEqualTo("sha256:" + "a".repeat(64));
        assertThat(text).doesNotContain("https://evil.example", "<b>")
                .contains("external (evil.example)", "외부 이미지(evil.example)", "raw",
                        "[relative](/docs)", "[anchor](#part)", "`https://code.example`");
        assertThat(response.blocks()).extracting(McpDocumentReadService.Block::blockId)
                .containsExactly("B0001", "B0002");
    }

    @Test void referenceDefinitionsInOtherBlocksDoNotKeepExternalUrls() {
        String markdown = "[t][x] and [rel][y]\n\n> [x]: https://evil.example/q?s=secret \"title\"\n\n[y]: /docs\n";
        var blocks = McpDocumentReadService.markdownBlocks(markdown);

        var out = McpDocumentReadService.withoutExternalDefinitions(markdown, blocks);

        assertThat(out).hasSize(blocks.size());
        assertThat(String.join("", out)).doesNotContain("evil.example/q", "secret")
                .contains("x: 외부 링크(evil.example)", "[y]: /docs", "[t][x]");
        assertThat(out.get(0)).isEqualTo(blocks.get(0));
        String multiLine = "[x]:\n  https://evil.example/q\n  \"title\"\n\nafter\n";
        var multiBlocks = McpDocumentReadService.markdownBlocks(multiLine);
        assertThat(String.join("", McpDocumentReadService.withoutExternalDefinitions(multiLine, multiBlocks)).lines().count())
                .isEqualTo(multiLine.lines().count());
        assertThat(McpDocumentReadService.withoutExternalDefinitions("plain\n", List.of("plain\n")))
                .containsExactly("plain\n");
    }

    @Test void blockIdsMatchWikiSourceOrdinalsEvenWhenSanitizingEmptiesABlock() {
        var repository = mock(McpDocumentDataGateway.class); var props = McpTokenVerifierTest.props();
        var service = new McpDocumentReadService(repository,
                new McpCursorCodec(new ObjectMapper(), props), props, new ObjectMapper());
        var identity = new McpIdentity("user", "workspace", "https://client.example/client.json", "grant", 1,
                Set.of("read"), "MEMBER", Set.of());
        var viewer = new DocumentAccessPolicy.Viewer("user", false, false, Map.of(), Map.of(), Map.of());
        Document document = document("doc_ids");
        String markdown = "first\n\n<script>alert(1)</script>\n\nthird\n";
        when(repository.viewer("workspace", "user", false, false)).thenReturn(viewer);
        when(repository.find("workspace", "doc_ids")).thenReturn(Optional.of(document));
        when(repository.currentBody(document, props.maxSourceBytes())).thenReturn(Optional.of(
                new McpDocumentDataGateway.CurrentBody(2, "a".repeat(64), markdown,
                        markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)));

        var response = service.get(identity, "doc_ids", null);

        var source = McpDocumentReadService.markdownBlocks(markdown);
        assertThat(response.blocks()).hasSize(source.size());
        assertThat(response.blocks().get(2).blockId()).isEqualTo("B0003");
        assertThat(response.blocks().get(2).text()).isEqualTo(source.get(2));
        assertThat(response.blocks().get(1).text()).doesNotContain("<script>");
    }
    private static Document document(String id) {
        return new Document(id, "workspace", "user", id + ".md", "text/markdown", 1, null, "a".repeat(64));
    }
}
