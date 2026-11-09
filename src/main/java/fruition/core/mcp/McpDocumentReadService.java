package fruition.core.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.domain.Document;
import fruition.core.document.service.AiMarkdownSanitizer;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.McpDocumentDataGateway;
import org.commonmark.node.Node;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    public McpDocumentReadService(McpDocumentDataGateway documents, McpCursorCodec cursors,
                                  McpProperties properties, ObjectMapper mapper) {
        this.documents = documents; this.cursors = cursors;
        this.properties = properties; this.mapper = mapper;
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
        String sanitized = AiMarkdownSanitizer.sanitize(body.markdown());
        if (utf8(sanitized) > properties.maxSourceBytes())
            throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        List<String> texts = markdownBlocks(sanitized);
        if (texts.size() > properties.maxSourceBlocks())
            throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        List<Block> all = new ArrayList<>();
        String hashPrefix = hash.substring("sha256:".length(), "sha256:".length() + 12);
        for (int i = 0; i < texts.size(); i++)
            all.add(new Block("mcp_" + hashPrefix + "_" + (i + 1), i + 1, texts.get(i)));
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
    static List<String> markdownBlocks(String markdown) {
        List<Integer> starts = new ArrayList<>();
        for (Node node = MARKDOWN.parse(markdown).getFirstChild(); node != null; node = node.getNext()) {
            if (!node.getSourceSpans().isEmpty()) starts.add(node.getSourceSpans().getFirst().getInputIndex());
        }
        if (starts.isEmpty()) return markdown.isEmpty() ? List.of() : List.of(markdown);
        List<String> blocks = new ArrayList<>(starts.size());
        for (int i = 0; i < starts.size(); i++) {
            int start = i == 0 ? 0 : starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) : markdown.length();
            blocks.add(markdown.substring(start, end));
        }
        return blocks;
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
    public record Block(@JsonProperty("block_id") String blockId, int ordinal, String text) {}
}
