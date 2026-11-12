package fruition.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 웹 편입이 쓰는 pipeline {@code source_blocks} 레지스트리의 블록 ID·줄 범위를 받아 온다
 * ({@code POST /internal/mcp/source-blocks/resolve}). MCP get_document와 wiki source callback이 같은 ID를 써야
 * 외부 AI가 낸 근거 ID를 화면이 그대로 강조하고, MCP 편입이 문서 블록을 다시 번호 매기지 않는다.
 *
 * <p>줄 범위는 '\n'으로 나눈 1부터 세는 원문 줄이다. 응답의 {@code raw_text}를 그 줄들과 대조해 기준이 어긋나면
 * 실패시킨다. 실패하면 순번 ID로 대신하지 않고 {@link McpToolException}을 던진다.
 */
@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpSourceBlockResolver {
    static final String SCHEMA_VERSION = "mcp.source-blocks.v1";
    static final String PATH = "/internal/mcp/source-blocks/resolve";
    private static final Pattern BLOCK_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,254}");
    /** 블록마다 JSON 키·숫자·block_type이 붙는 몫. */
    private static final long PER_BLOCK_OVERHEAD_BYTES = 512;
    private final McpWikiDraftClient ai;
    private final McpProperties properties;

    public McpSourceBlockResolver(McpWikiDraftClient ai, McpProperties properties) {
        this.ai = ai;
        this.properties = properties;
    }

    public List<SourceBlock> resolve(String workspaceId, String documentId, String markdown) {
        if (markdown.isEmpty()) return List.of();
        JsonNode root = ai.post(PATH, Map.of("schema_version", SCHEMA_VERSION, "workspace_id", workspaceId,
                "document_id", documentId, "markdown", markdown), responseLimit());
        if (root == null || !SCHEMA_VERSION.equals(root.path("schema_version").asText(null))
                || !documentId.equals(root.path("document_id").asText(null)) || !root.path("blocks").isArray()) {
            throw unavailable();
        }
        String[] lines = markdown.split("\n", -1);
        List<SourceBlock> blocks = new ArrayList<>(root.path("blocks").size());
        Set<String> ids = new HashSet<>();
        int previousEnd = 0;
        for (JsonNode node : root.path("blocks")) {
            String blockId = node.path("block_id").isTextual() ? node.path("block_id").asText() : null;
            if (blockId == null || !BLOCK_ID.matcher(blockId).matches() || !ids.add(blockId)) throw unavailable();
            if (!node.path("ordinal").isIntegralNumber() || node.path("ordinal").asLong() != blocks.size() + 1L)
                throw unavailable();
            int start = line(node.path("line_start")), end = line(node.path("line_end"));
            if (start <= previousEnd || end < start || end > lines.length) throw unavailable();
            JsonNode type = node.path("block_type");
            if (!(type.isMissingNode() || type.isNull() || type.isTextual())) throw unavailable();
            String raw = String.join("\n", Arrays.asList(lines).subList(start - 1, end));
            if (!node.path("raw_text").isTextual() || !raw.equals(node.path("raw_text").asText())) throw unavailable();
            blocks.add(new SourceBlock(blockId, blocks.size() + 1, start, end,
                    type.isTextual() ? type.asText() : null, raw));
            previousEnd = end;
        }
        return List.copyOf(blocks);
    }

    /** 원문 두 벌(text·raw_text)과 JSON 이스케이프, 블록마다 붙는 필드를 담을 만큼. */
    private int responseLimit() {
        long limit = properties.maxResponseBytes() + 4L * properties.maxSourceBytes()
                + PER_BLOCK_OVERHEAD_BYTES * properties.maxSourceBlocks();
        return (int) Math.min(Integer.MAX_VALUE - 8L, limit);
    }

    private static int line(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() < 1) throw unavailable();
        return node.asInt();
    }

    private static McpToolException unavailable() {
        return new McpToolException("DEPENDENCY_UNAVAILABLE");
    }

    /** rawText는 원문 lineStart..lineEnd 줄을 '\n'으로 이은 그대로다. 블록 사이 빈 줄은 어느 블록에도 없다. */
    public record SourceBlock(String blockId, int ordinal, int lineStart, int lineEnd, String blockType,
                              String rawText) {}
}
