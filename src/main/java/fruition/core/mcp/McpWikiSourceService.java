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

    public McpWikiSourceService(McpAccessClient access, McpDocumentDataGateway documents,
                                McpProperties properties) {
        this.access = access;
        this.documents = documents;
        this.properties = properties;
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
        List<String> texts = McpDocumentReadService.markdownBlocks(body.markdown());
        if (texts.isEmpty() || texts.size() > properties.maxSourceBlocks()) {
            throw new McpAuthorizationException(413, "SOURCE_LIMIT_EXCEEDED");
        }
        List<Block> blocks = java.util.stream.IntStream.range(0, texts.size())
                .mapToObj(i -> new Block("B%04d".formatted(i + 1), i + 1, texts.get(i), hash(texts.get(i))))
                .toList();
        return new Response("mcp.wiki-source.v1", documentId, document.getDisplayName(), identity.workspaceId(), body.version(),
                sourceHash, document.getMimeType(), blocks);
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
    public record Block(@JsonProperty("block_id") String blockId, int ordinal, String text,
                        @JsonProperty("content_hash") String contentHash) {}
}
