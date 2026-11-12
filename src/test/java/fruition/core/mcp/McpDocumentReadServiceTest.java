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
        var service = service(repository, props);
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
        var service = service(repository, props);
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
                .containsExactly("src_1", "src_2");
    }

    @Test void referenceDefinitionsInOtherBlocksDoNotKeepExternalUrls() {
        String markdown = "[t][x] and [rel][y]\n\n> [x]: https://evil.example/q?s=secret \"title\"\n\n[y]: /docs\n";

        String out = McpDocumentReadService.withoutExternalDefinitions(markdown);

        assertThat(out).doesNotContain("evil.example/q", "secret")
                .contains("x: 외부 링크(evil.example)", "[y]: /docs", "[t][x]");
        assertThat(out.split("\n", -1)[0]).isEqualTo("[t][x] and [rel][y]");
        String multiLine = "[x]:\n  https://evil.example/q\n  \"title\"\n\nafter\n";
        assertThat(McpDocumentReadService.withoutExternalDefinitions(multiLine).split("\n", -1))
                .hasSameSizeAs(multiLine.split("\n", -1)).doesNotContain("  https://evil.example/q")
                .endsWith("after", "");
        assertThat(McpDocumentReadService.withoutExternalDefinitions("plain\n")).isEqualTo("plain\n");
        // 여러 줄 라벨도 줄 수를 그대로 둔다. 그렇지 않으면 뒤 블록 text가 다른 줄로 잘린다.
        String multiLabel = "[foo\nbar]: https://evil.example/x\n\nafter\n";
        assertThat(McpDocumentReadService.withoutExternalDefinitions(multiLabel).split("\n", -1))
                .hasSameSizeAs(multiLabel.split("\n", -1)).doesNotContain("https://evil.example/x")
                .endsWith("after", "");
    }

    @Test void blocksUseRegistryIdsAndRawLineRangesEvenAroundScriptAndMultiLineDefinitions() {
        var repository = mock(McpDocumentDataGateway.class); var props = McpTokenVerifierTest.props();
        var ai = mock(McpWikiDraftClient.class);
        var service = new McpDocumentReadService(repository, new McpCursorCodec(new ObjectMapper(), props), props,
                new ObjectMapper(), McpSourceBlockResolverTest.fakeRegistry(ai, "src_", java.util.function.UnaryOperator.identity()));
        var identity = identity();
        Document document = document("doc_ids");
        String markdown = "# Title\n\n<script>\nalert(1)\n</script>\n\n[x]:\n  https://evil.example/q\n  \"t\"\n\n"
                + "after [t][x]\nmore\n";
        stubBody(repository, document, markdown);

        var response = service.get(identity, "doc_ids", null);

        assertThat(response.blocks()).extracting(McpDocumentReadService.Block::blockId)
                .containsExactly("src_1", "src_2", "src_3", "src_4");
        assertThat(response.blocks()).extracting(McpDocumentReadService.Block::lineStart).containsExactly(1, 3, 7, 11);
        assertThat(response.blocks()).extracting(McpDocumentReadService.Block::lineEnd).containsExactly(1, 5, 9, 12);
        // propose_document_edit 검증기(McpDocumentProposalService)와 같은 기준: 1부터 세는 원문 줄.
        String[] validatorLines = markdown.split("\\r\\n|\\r|\\n", -1);
        assertThat(validatorLines).hasSize(13);
        var last = response.blocks().getLast();
        assertThat(String.join("\n", List.of(validatorLines).subList(last.lineStart() - 1, last.lineEnd())))
                .isEqualTo("after [t][x]\nmore");
        assertThat(last.text()).isEqualTo("after [t][x]\nmore");
        assertThat(response.blocks().get(1).text()).doesNotContain("<script>");
        assertThat(response.blocks().get(2).text()).doesNotContain("evil.example/q").contains("외부 링크(evil.example)");
    }

    @Test void failsInsteadOfFallingBackToOrdinalIdsWhenTheRegistryIsUnavailable() {
        var repository = mock(McpDocumentDataGateway.class); var props = McpTokenVerifierTest.props();
        var ai = mock(McpWikiDraftClient.class);
        when(ai.post(eq(McpSourceBlockResolver.PATH), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new McpToolException("DEPENDENCY_UNAVAILABLE"));
        var service = new McpDocumentReadService(repository, new McpCursorCodec(new ObjectMapper(), props), props,
                new ObjectMapper(), new McpSourceBlockResolver(ai, props));
        Document document = document("doc_down");
        stubBody(repository, document, "first\n\nsecond\n");

        assertThatThrownBy(() -> service.get(identity(), "doc_down", null))
                .isInstanceOf(McpToolException.class).hasMessage("DEPENDENCY_UNAVAILABLE");
    }

    private static void stubBody(McpDocumentDataGateway repository, Document document, String markdown) {
        var props = McpTokenVerifierTest.props();
        var viewer = new DocumentAccessPolicy.Viewer("user", false, false, Map.of(), Map.of(), Map.of());
        when(repository.viewer("workspace", "user", false, false)).thenReturn(viewer);
        when(repository.find("workspace", document.getId())).thenReturn(Optional.of(document));
        when(repository.currentBody(document, props.maxSourceBytes())).thenReturn(Optional.of(
                new McpDocumentDataGateway.CurrentBody(2, "a".repeat(64), markdown,
                        markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)));
    }
    private static McpIdentity identity() {
        return new McpIdentity("user", "workspace", "https://client.example/client.json", "grant", 1,
                Set.of("read"), "MEMBER", Set.of());
    }
    private static McpDocumentReadService service(McpDocumentDataGateway repository, McpProperties props) {
        return new McpDocumentReadService(repository, new McpCursorCodec(new ObjectMapper(), props), props,
                new ObjectMapper(), McpSourceBlockResolverTest.fakeRegistry(mock(McpWikiDraftClient.class), "src_",
                java.util.function.UnaryOperator.identity()));
    }
    private static Document document(String id) {
        return new Document(id, "workspace", "user", id + ".md", "text/markdown", 1, null, "a".repeat(64));
    }
}
