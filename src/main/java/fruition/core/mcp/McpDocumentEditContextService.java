package fruition.core.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import fruition.core.document.service.McpDocumentDataGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpDocumentEditContextService {
    private static final Pattern DOCUMENT_ID = Pattern.compile("(?:doc_|chatdoc_)[A-Za-z0-9][A-Za-z0-9_-]{0,246}");
    private final McpAccessClient access;
    private final McpDocumentDataGateway documents;
    private final McpProperties properties;
    private final ObjectMapper mapper;

    public McpDocumentEditContextService(McpAccessClient access, McpDocumentDataGateway documents,
                                         McpProperties properties, ObjectMapper mapper) {
        this.access = access;
        this.documents = documents;
        this.properties = properties;
        this.mapper = mapper;
    }

    public Response read(McpProposalActor actor, String documentId, long baseVersion) {
        if (!DOCUMENT_ID.matcher(documentId).matches() || baseVersion < 1) {
            throw new McpAuthorizationException(400, "INVALID_INPUT");
        }
        McpIdentity identity = access.authorizeProposal(actor);
        var document = documents.find(identity.workspaceId(), documentId)
                .orElseThrow(() -> new McpAuthorizationException(404, "RESOURCE_NOT_FOUND"));
        try {
            documents.requireEdit(document, identity);
        } catch (DocumentWriteForbiddenException e) {
            throw new McpAuthorizationException(403, "PERMISSION_REVOKED");
        }
        var body = documents.currentBody(document, properties.maxSourceBytes())
                .orElseThrow(() -> new McpAuthorizationException(503, "DEPENDENCY_UNAVAILABLE"));
        if (body.markdown() == null || body.bodyBytes() > properties.maxSourceBytes()) {
            throw new McpAuthorizationException(413, "RESPONSE_LIMIT_EXCEEDED");
        }
        if (body.version() != baseVersion) {
            throw new McpAuthorizationException(409, "SOURCE_CHANGED");
        }
        String contentHash = "sha256:" + sha256(body.markdown());
        String storedHash = body.contentHash();
        if (storedHash == null || !(storedHash.equals(contentHash) || storedHash.equals(contentHash.substring(7)))) {
            throw new McpAuthorizationException(503, "DEPENDENCY_UNAVAILABLE");
        }
        Response response = new Response("mcp.document-edit-context.v1", documentId, identity.workspaceId(),
                body.version(), contentHash, body.markdown(), document.getDisplayName(),
                document.getFolderId() == null ? null : document.getFolderId().toString());
        try {
            if (mapper.writeValueAsBytes(response).length > properties.maxResponseBytes()) {
                throw new McpAuthorizationException(413, "RESPONSE_LIMIT_EXCEEDED");
            }
        } catch (McpAuthorizationException e) {
            throw e;
        } catch (Exception e) {
            throw new McpAuthorizationException(503, "DEPENDENCY_UNAVAILABLE");
        }
        return response;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Response(
            @JsonProperty("schema_version") String schemaVersion,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("current_version") long currentVersion,
            @JsonProperty("content_hash") String contentHash,
            @JsonProperty("current_markdown") String currentMarkdown,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("parent_folder_id") String parentFolderId) {}
}
