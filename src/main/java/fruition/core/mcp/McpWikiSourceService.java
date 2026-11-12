package fruition.core.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.document.service.McpDocumentDataGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpWikiSourceService {
    private static final Set<String> MIME_TYPES = Set.of("text/markdown", "text/x-markdown", "text/plain");
    private final McpAccessClient access;
    private final McpDocumentDataGateway documents;
    private final McpProperties properties;
    private final McpSourceBlockResolver blocks;

    public McpWikiSourceService(McpAccessClient access, McpDocumentDataGateway documents,
                                McpProperties properties, McpSourceBlockResolver blocks) {
        this.access = access;
        this.documents = documents;
        this.properties = properties;
        this.blocks = blocks;
    }

    @Transactional(readOnly = true)
    public Response read(McpProposalActor actor, String documentId) {
        McpIdentity identity = access.authorizeWikiSource(actor);
        return read(identity, documentId);
    }

    /** Reuses a caller's freshly-authorized web/operation-boundary identity for the last source check. */
    public Response read(McpIdentity identity, String documentId) {
        var document = documents.find(identity.workspaceId(), documentId)
                .orElseThrow(() -> new McpAuthorizationException(404, "RESOURCE_NOT_FOUND"));
        if (!documents.viewer(identity.workspaceId(), identity.userId(), identity.owner(), identity.canConvert())
                .canRead(document)) {
            throw new McpAuthorizationException(404, "RESOURCE_NOT_FOUND");
        }
        if (!MIME_TYPES.contains(document.getMimeType())) {
            throw new McpAuthorizationException(400, "INVALID_SOURCE_TYPE");
        }
        var body = documents.currentBody(document, properties.maxSourceBytes())
                .orElseThrow(() -> new McpAuthorizationException(503, "DEPENDENCY_UNAVAILABLE"));
        if (body.markdown() == null || body.bodyBytes() > properties.maxSourceBytes()) {
            throw new McpAuthorizationException(413, "SOURCE_LIMIT_EXCEEDED");
        }
        String sourceHash = hash(body.markdown());
        if (body.version() < 1 || body.contentHash() == null
                || !(body.contentHash().equals(sourceHash) || body.contentHash().equals(sourceHash.substring(7)))) {
            throw new McpAuthorizationException(503, "DEPENDENCY_UNAVAILABLE");
        }
        // 블록 ID는 웹 편입과 같은 pipeline source_blocks 레지스트리 ID다. 순번으로 대신 매기지 않는다.
        List<McpSourceBlockResolver.SourceBlock> resolved = resolve(identity.workspaceId(), documentId, body.markdown());
        if (resolved.isEmpty() || resolved.size() > properties.maxSourceBlocks()) {
            throw new McpAuthorizationException(413, "SOURCE_LIMIT_EXCEEDED");
        }
        List<Block> out = resolved.stream()
                .map(b -> new Block(b.blockId(), b.ordinal(), b.rawText(), hash(b.rawText()),
                        b.lineStart(), b.lineEnd(), b.blockType()))
                .toList();
        return new Response("mcp.wiki-source.v1", documentId, document.getDisplayName(), identity.workspaceId(), body.version(),
                sourceHash, document.getMimeType(), out);
    }

    private List<McpSourceBlockResolver.SourceBlock> resolve(String workspaceId, String documentId, String markdown) {
        try {
            return blocks.resolve(workspaceId, documentId, markdown);
        } catch (McpToolException e) {
            if ("RESPONSE_LIMIT_EXCEEDED".equals(e.code()) || "SOURCE_LIMIT_EXCEEDED".equals(e.code())) {
                throw new McpAuthorizationException(413, "SOURCE_LIMIT_EXCEEDED");
            }
            throw new McpAuthorizationException(503, "DEPENDENCY_UNAVAILABLE");
        }
    }

    private static String hash(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Response(@JsonProperty("schema_version") String schemaVersion,
                           @JsonProperty("document_id") String documentId,
                           @JsonProperty("display_name") String displayName,
                           @JsonProperty("workspace_id") String workspaceId,
                           @JsonProperty("source_version") long sourceVersion,
                           @JsonProperty("source_content_hash") String sourceContentHash,
                           @JsonProperty("mime_type") String mimeType,
                           List<Block> blocks) {}
    /** text는 원문 line_start..line_end 줄 그대로다. block_type은 레지스트리가 주지 않으면 null이다. */
    public record Block(@JsonProperty("block_id") String blockId, int ordinal, String text,
                        @JsonProperty("content_hash") String contentHash,
                        @JsonProperty("line_start") int lineStart, @JsonProperty("line_end") int lineEnd,
                        @JsonProperty("block_type") String blockType) {}
}
