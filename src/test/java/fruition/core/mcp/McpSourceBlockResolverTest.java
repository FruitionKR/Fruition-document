package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpSourceBlockResolverTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void returnsRegistryIdsAndRawLineRangesInDocumentOrder() {
        McpWikiDraftClient ai = mock(McpWikiDraftClient.class);
        var resolver = fakeRegistry(ai, "src_", UnaryOperator.identity());
        String markdown = "# Title\n\nfirst line\nsecond line\n\n\nlast\n";

        var blocks = resolver.resolve("workspace", "doc_1", markdown);

        assertThat(blocks).extracting(McpSourceBlockResolver.SourceBlock::blockId)
                .containsExactly("src_1", "src_2", "src_3");
        assertThat(blocks.get(1)).isEqualTo(new McpSourceBlockResolver.SourceBlock(
                "src_2", 2, 3, 4, "paragraph", "first line\nsecond line"));
        assertThat(blocks.get(2).lineStart()).isEqualTo(7);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(ai).post(eq(McpSourceBlockResolver.PATH), body.capture(), anyInt());
        assertThat(body.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "schema_version", "mcp.source-blocks.v1", "workspace_id", "workspace",
                "document_id", "doc_1", "markdown", markdown));
    }

    @Test void allowsResponsesLargerThanTheGeneralResponseLimit() {
        McpWikiDraftClient ai = mock(McpWikiDraftClient.class);
        var props = McpTokenVerifierTest.props();
        fakeRegistry(ai, "b", UnaryOperator.identity()).resolve("workspace", "doc_1", "a");
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(ai).post(eq(McpSourceBlockResolver.PATH), any(), limit.capture());
        assertThat(limit.getValue()).isGreaterThanOrEqualTo(props.maxResponseBytes() + 2 * props.maxSourceBytes());
    }

    @Test void emptyMarkdownHasNoBlocksWithoutCallingThePipeline() {
        McpWikiDraftClient ai = mock(McpWikiDraftClient.class);
        assertThat(new McpSourceBlockResolver(ai, McpTokenVerifierTest.props()).resolve("w", "doc_1", "")).isEmpty();
        verify(ai, never()).post(any(), any(), anyInt());
    }

    @Test void rejectsResponsesThatDoNotMatchTheSentMarkdown() {
        String markdown = "one\n\ntwo\n";
        List<UnaryOperator<ObjectNode>> broken = List.of(
                root -> root.put("document_id", "doc_other"),
                root -> root.put("schema_version", "mcp.source-blocks.v0"),
                root -> { block(root, 1).put("raw_text", "changed"); return root; },
                root -> { block(root, 1).put("line_start", 1).put("line_end", 1); return root; },
                root -> { block(root, 1).put("line_end", 99); return root; },
                root -> { block(root, 1).put("block_id", "B0001"); block(root, 0).put("block_id", "B0001"); return root; },
                root -> { block(root, 1).put("ordinal", 5); return root; },
                root -> { block(root, 0).put("block_id", "../x"); return root; },
                root -> { block(root, 0).put("block_type", 3); return root; });
        for (var mutation : broken) {
            McpWikiDraftClient ai = mock(McpWikiDraftClient.class);
            var resolver = fakeRegistry(ai, "b", mutation);
            assertThatThrownBy(() -> resolver.resolve("workspace", "doc_1", markdown))
                    .isInstanceOf(McpToolException.class).hasMessage("DEPENDENCY_UNAVAILABLE");
        }
    }

    @Test void passesMappedPipelineErrorsThrough() {
        McpWikiDraftClient ai = mock(McpWikiDraftClient.class);
        when(ai.post(eq(McpSourceBlockResolver.PATH), any(), anyInt()))
                .thenThrow(new McpToolException("RESOURCE_NOT_FOUND"));
        assertThatThrownBy(() -> new McpSourceBlockResolver(ai, McpTokenVerifierTest.props())
                .resolve("workspace", "doc_1", "x")).hasMessage("RESOURCE_NOT_FOUND");
    }

    private static ObjectNode block(ObjectNode root, int index) {
        return (ObjectNode) root.withArray("blocks").get(index);
    }

    /**
     * pipeline 레지스트리 대역. 빈 줄로 나뉜 줄 묶음마다 블록 하나를 주고 ID는 prefix + 순번이다.
     * mutation으로 응답을 망가뜨릴 수 있다.
     */
    static McpSourceBlockResolver fakeRegistry(McpWikiDraftClient ai, String prefix,
                                               UnaryOperator<ObjectNode> mutation) {
        when(ai.post(eq(McpSourceBlockResolver.PATH), any(), anyInt())).thenAnswer(call -> {
            Map<?, ?> body = call.getArgument(1);
            return mutation.apply(response((String) body.get("document_id"), (String) body.get("markdown"), prefix));
        });
        return new McpSourceBlockResolver(ai, McpTokenVerifierTest.props());
    }

    static ObjectNode response(String documentId, String markdown, String prefix) {
        String[] lines = markdown.split("\n", -1);
        ObjectNode root = MAPPER.createObjectNode().put("schema_version", "mcp.source-blocks.v1")
                .put("document_id", documentId);
        ArrayNode blocks = root.putArray("blocks");
        List<int[]> ranges = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= lines.length; i++) {
            boolean blank = i == lines.length || lines[i].isBlank();
            if (!blank && start < 0) start = i;
            if (blank && start >= 0) { ranges.add(new int[] {start + 1, i}); start = -1; }
        }
        for (int i = 0; i < ranges.size(); i++) {
            String raw = String.join("\n", Arrays.asList(lines).subList(ranges.get(i)[0] - 1, ranges.get(i)[1]));
            blocks.addObject().put("block_id", prefix + (i + 1)).put("ordinal", i + 1)
                    .put("line_start", ranges.get(i)[0]).put("line_end", ranges.get(i)[1])
                    .put("block_type", raw.startsWith("#") ? "heading" : "paragraph")
                    .put("text", raw.strip()).put("raw_text", raw);
        }
        return root;
    }
}
