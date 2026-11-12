package fruition.core.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.domain.Document;
import fruition.core.document.service.AiMarkdownSanitizer;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.McpDocumentDataGateway;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpDocumentReadService {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,254}");
    private static final Pattern HASH = Pattern.compile("(?:sha256:)?([0-9a-f]{64})");
    private static final Parser MARKDOWN = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS).build();
    private final McpDocumentDataGateway documents;
    private final McpCursorCodec cursors;
    private final McpProperties properties;
    private final ObjectMapper mapper;
    private final McpSourceBlockResolver sourceBlocks;

    public McpDocumentReadService(McpDocumentDataGateway documents, McpCursorCodec cursors,
                                  McpProperties properties, ObjectMapper mapper, McpSourceBlockResolver sourceBlocks) {
        this.documents = documents; this.cursors = cursors;
        this.properties = properties; this.mapper = mapper; this.sourceBlocks = sourceBlocks;
    }

    public DocumentSearchResponse find(McpIdentity id, String query, String cursor, int pageSize) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty() || utf8(normalized) > properties.maxResponseBytes()
                || pageSize < 1 || pageSize > properties.maxPageSize()) invalid();
        var rows = documents.search(id.workspaceId(), normalized, properties.maxCandidates() + 1);
        if (rows.size() > properties.maxCandidates()) throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        var viewer = viewer(id);
        List<DocumentResult> all = rows.stream().filter(viewer::canRead).map(this::project).toList();
        String snapshot = snapshot(all);
        int offset = 0;
        if (cursor != null) {
            Map<String,Object> c = cursors.decode(cursor, id, "find_documents");
            if (!McpCursorCodec.sha256(normalized).equals(c.get("q")) || ((Number)c.get("s")).intValue() != pageSize)
                throw new McpToolException("HANDLE_NOT_FOUND");
            if (!snapshot.equals(c.get("h"))) throw new McpToolException("STALE_CURSOR");
            offset = ((Number)c.get("o")).intValue();
        }
        if (offset < 0 || offset > all.size()) throw new McpToolException("HANDLE_NOT_FOUND");
        int end = Math.min(all.size(), offset + pageSize);
        String next = end < all.size() ? cursors.encode(id, "find_documents", Map.of("q",
                McpCursorCodec.sha256(normalized), "s", pageSize, "h", snapshot, "o", end)) : null;
        return bounded(new DocumentSearchResponse("mcp.document-search.v1", all.subList(offset, end), next,
                next != null, "UNTRUSTED_WORKSPACE_CONTENT"));
    }

    public DocumentReadResponse get(McpIdentity id, String documentId, String cursor) {
        if (documentId == null || !ID.matcher(documentId).matches()) invalid();
        var viewer = viewer(id);
        Document document = documents.find(id.workspaceId(), documentId)
                .filter(viewer::canRead).orElseThrow(() -> new McpToolException("RESOURCE_NOT_FOUND"));
        var body = documents.currentBody(document, properties.maxSourceBytes())
                .orElseThrow(() -> new McpToolException("DEPENDENCY_UNAVAILABLE"));
        if (body.markdown() == null || body.bodyBytes() > properties.maxSourceBytes())
            throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        String hash = normalizeHash(body.contentHash());
        // 블록 ID·줄 범위는 웹 편입과 같은 pipeline source_blocks 레지스트리에서 받는다. 그래야 외부 AI가 낸 근거 ID를
        // 화면이 그대로 강조하고 lint·편입 근거로 쓸 수 있다. 실패하면 순번 ID로 대신하지 않는다.
        List<McpSourceBlockResolver.SourceBlock> resolved =
                sourceBlocks.resolve(id.workspaceId(), documentId, body.markdown());
        if (resolved.size() > properties.maxSourceBlocks())
            throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        // 줄 수가 원문과 같은 사본에서 같은 줄 범위를 잘라 블록마다 sanitize한다.
        String[] lines = withoutExternalDefinitions(body.markdown()).split("\n", -1);
        List<Block> all = new ArrayList<>();
        int sanitizedBytes = 0;
        for (var block : resolved) {
            String text = AiMarkdownSanitizer.sanitize(
                    String.join("\n", Arrays.asList(lines).subList(block.lineStart() - 1, block.lineEnd())));
            sanitizedBytes += utf8(text);
            all.add(new Block(block.blockId(), block.ordinal(), block.lineStart(), block.lineEnd(), text));
        }
        if (sanitizedBytes > properties.maxSourceBytes())
            throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        String snapshot = snapshot(all);
        int offset = 0;
        if (cursor != null) {
            Map<String,Object> c = cursors.decode(cursor, id, "get_document");
            if (!documentId.equals(c.get("d"))) throw new McpToolException("HANDLE_NOT_FOUND");
            if (((Number)c.get("v")).longValue() != body.version()
                    || !hash.equals(c.get("x")) || !snapshot.equals(c.get("h")))
                throw new McpToolException("STALE_CURSOR");
            offset = ((Number)c.get("o")).intValue();
        }
        if (offset < 0 || offset > all.size()) throw new McpToolException("HANDLE_NOT_FOUND");
        List<Block> page = new ArrayList<>(); int bytes = 0; int end = offset;
        while (end < all.size() && page.size() < properties.maxDocumentBlocksPerPage()) {
            int blockBytes = utf8(all.get(end).text());
            if (blockBytes > properties.maxDocumentContentBytes()) throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
            if (!page.isEmpty() && bytes + blockBytes > properties.maxDocumentContentBytes()) break;
            page.add(all.get(end++)); bytes += blockBytes;
        }
        String next = end < all.size() ? cursors.encode(id, "get_document", Map.of("d", documentId,
                "v", body.version(), "x", hash, "h", snapshot, "o", end)) : null;
        return bounded(new DocumentReadResponse("mcp.document-read.v1", documentId,
                AiMarkdownSanitizer.sanitize(document.getDisplayName()), body.version(), hash,
                page, next, next != null, "UNTRUSTED_WORKSPACE_CONTENT"));
    }

    private DocumentAccessPolicy.Viewer viewer(McpIdentity id) {
        return documents.viewer(id.workspaceId(), id.userId(), id.owner(), id.canConvert());
    }
    private DocumentResult project(Document d) {
        String displayName = AiMarkdownSanitizer.sanitize(d.getDisplayName());
        var identity = documents.currentIdentity(d, properties.maxSourceBytes())
                .orElseThrow(() -> new McpToolException("DEPENDENCY_UNAVAILABLE"));
        if (identity.bodyBytes() > properties.maxSourceBytes())
            throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        return new DocumentResult(d.getId(), displayName, identity.version(), hash(identity.contentHash()),
                displayName, List.of());
    }
    private String hash(String contentHash) {
        if (contentHash == null) throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        return normalizeHash(contentHash);
    }
    private static String normalizeHash(String hash) {
        var m = HASH.matcher(hash); if (!m.matches()) throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        return "sha256:" + m.group(1);
    }
    private String snapshot(Object value) {
        try { return McpCursorCodec.sha256(mapper.writeValueAsString(value)); }
        catch (Exception e) { throw new McpToolException("DEPENDENCY_UNAVAILABLE"); }
    }
    /**
     * 블록별 sanitize는 다른 블록의 참조 정의를 보지 못해 {@code [t][x]}를 외부 링크로 판단하지 못하고 글자로 남긴다.
     * 정의 줄({@code [x]: https://...})의 외부 주소를 글자로 바꿔, 응답을 이어 붙여 렌더링해도 외부 링크가 살아나지 않게 한다.
     * 바꾼 자리에 원래 있던 줄바꿈 수만큼 줄바꿈을 남겨 줄 수·줄 번호를 원문과 같게 둔다(블록 줄 범위가 원문 기준이다).
     */
    static String withoutExternalDefinitions(String markdown) {
        List<int[]> ranges = new ArrayList<>();
        List<String> replacements = new ArrayList<>();
        MARKDOWN.parse(markdown).accept(new AbstractVisitor() {
            @Override public void visit(LinkReferenceDefinition node) {
                var host = AiMarkdownSanitizer.externalHost(node.getDestination());
                var spans = node.getSourceSpans();
                if (host.isEmpty() || spans.isEmpty()) return;
                var last = spans.getLast();
                ranges.add(new int[] {spans.getFirst().getInputIndex(), last.getInputIndex() + last.getLength()});
                // 라벨은 여러 줄일 수 있다. 교체 글자에 줄바꿈이 남으면 뒤 블록 줄이 밀리므로 공백으로 바꾼다.
                String label = node.getLabel().replaceAll("[\\r\\n]+", " ");
                replacements.add(label + ": " + (host.get().isEmpty() ? "외부 링크" : "외부 링크(" + host.get() + ")"));
            }
        });
        if (ranges.isEmpty()) return markdown;
        StringBuilder out = new StringBuilder(markdown.length());
        int cursor = 0;
        for (int i = 0; i < ranges.size(); i++) {
            int start = Math.max(cursor, ranges.get(i)[0]), end = Math.max(start, ranges.get(i)[1]);
            long newlines = markdown.substring(start, end).chars().filter(ch -> ch == '\n').count();
            out.append(markdown, cursor, start).append(replacements.get(i)).append("\n".repeat((int) newlines));
            cursor = end;
        }
        return out.append(markdown, cursor, markdown.length()).toString();
    }

    private <T> T bounded(T value) {
        try { if (mapper.writeValueAsBytes(value).length > properties.maxResponseBytes())
            throw new McpToolException("RESPONSE_LIMIT_EXCEEDED"); return value; }
        catch (McpToolException e) { throw e; }
        catch (Exception e) { throw new McpToolException("DEPENDENCY_UNAVAILABLE"); }
    }
    private static int utf8(String s) { return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length; }
    private static void invalid() { throw new McpToolException("INVALID_INPUT"); }

    public record DocumentSearchResponse(@JsonProperty("schema_version") String schemaVersion,
            List<DocumentResult> results, @JsonProperty("next_cursor") String nextCursor, boolean truncated,
            @JsonProperty("data_boundary") String dataBoundary) {}
    public record DocumentResult(@JsonProperty("document_id") String documentId,
            @JsonProperty("display_name") String displayName, @JsonProperty("current_version") long currentVersion,
            @JsonProperty("content_hash") String contentHash, String snippet,
            @JsonProperty("evidence_block_ids") List<String> evidenceBlockIds) {}
    public record DocumentReadResponse(@JsonProperty("schema_version") String schemaVersion,
            @JsonProperty("document_id") String documentId, @JsonProperty("display_name") String displayName,
            @JsonProperty("current_version") long currentVersion, @JsonProperty("content_hash") String contentHash,
            List<Block> blocks, @JsonProperty("next_cursor") String nextCursor, boolean truncated,
            @JsonProperty("data_boundary") String dataBoundary) {}
    /** line_start·line_end는 '\n'으로 나눈 현재 원문의 1부터 세는 줄 번호다(propose_document_edit target과 같은 기준). */
    public record Block(@JsonProperty("block_id") String blockId, int ordinal,
                        @JsonProperty("line_start") int lineStart, @JsonProperty("line_end") int lineEnd,
                        String text) {}
}
