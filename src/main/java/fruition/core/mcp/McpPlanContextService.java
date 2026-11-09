package fruition.core.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.Folder;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.McpDocumentDataGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpPlanContextService {
    private static final Pattern DOCUMENT_ID = Pattern.compile("(?:doc_|chatdoc_)[A-Za-z0-9][A-Za-z0-9_-]{0,246}");
    private static final int MAX_HIERARCHY_ROWS = 2_000;
    private static final int MAX_ANCESTOR_DEPTH = 64;

    private final McpAccessClient access;
    private final McpDocumentDataGateway documentData;
    private final McpProperties properties;
    private final ObjectMapper mapper;

    public McpPlanContextService(McpAccessClient access, McpDocumentDataGateway documentData,
                                 McpProperties properties, ObjectMapper mapper) {
        this.access = access;
        this.documentData = documentData;
        this.properties = properties;
        this.mapper = mapper;
    }

    public Response read(McpProposalActor actor, String targetFolderId,
                         List<Resource> resources, List<EditDocument> requestedEdits) {
        validateInput(resources, requestedEdits);
        McpIdentity identity = access.authorizeProposal(actor);
        var viewer = documentData.viewer(identity);
        UUID targetFolder = folderId(targetFolderId);
        if (targetFolder != null) {
            Folder folder = requireFolder(identity.workspaceId(), targetFolder);
            requireFolderEdit(viewer, folder.getId());
        }

        Map<String, HierarchyItem> hierarchy = new LinkedHashMap<>();
        Set<UUID> siblingParents = new LinkedHashSet<>();
        siblingParents.add(null);
        Set<String> versionBoundDocuments = new HashSet<>();
        for (Resource resource : resources) {
            if ("folder".equals(resource.resourceType())) {
                Folder folder = requireFolder(identity.workspaceId(), UUID.fromString(resource.resourceId()));
                requireFolderEdit(viewer, folder.getId());
                requireVersion(resource.baseVersion(), folder.getCurrentVersion());
                addFolderWithAncestors(identity.workspaceId(), viewer, folder, hierarchy);
                siblingParents.add(folder.getParentFolderId());
                siblingParents.add(folder.getId());
            } else {
                Document document = documentData.find(
                                identity.workspaceId(), resource.resourceId())
                        .orElseThrow(() -> new McpAuthorizationException(404, "RESOURCE_NOT_FOUND"));
                try {
                    documentData.requireEdit(document, identity);
                } catch (DocumentWriteForbiddenException denied) {
                    throw new McpAuthorizationException(403, "PERMISSION_REVOKED");
                }
                requireVersion(resource.baseVersion(), document.getCurrentVersion());
                if (resource.baseVersion() != null) versionBoundDocuments.add(document.getId());
                addDocument(viewer, document, hierarchy);
                addAncestors(identity.workspaceId(), viewer, document.getFolderId(), hierarchy);
                siblingParents.add(document.getFolderId());
            }
        }
        if (targetFolder != null) {
            addFolderWithAncestors(identity.workspaceId(), viewer,
                    requireFolder(identity.workspaceId(), targetFolder), hierarchy);
            siblingParents.add(targetFolder);
        }
        for (UUID parent : siblingParents) addSiblings(identity.workspaceId(), viewer, parent, hierarchy);

        List<EditDocumentContext> edits = new ArrayList<>();
        long bodyBytes = 0;
        for (EditDocument edit : requestedEdits) {
            if (!versionBoundDocuments.contains(edit.documentId())) {
                throw new McpAuthorizationException(400, "INVALID_INPUT");
            }
            Document document = documentData.find(
                            identity.workspaceId(), edit.documentId())
                    .orElseThrow(() -> new McpAuthorizationException(404, "RESOURCE_NOT_FOUND"));
            var body = documentData.currentBody(document, properties.maxSourceBytes())
                    .orElseThrow(() -> new McpAuthorizationException(503, "DEPENDENCY_UNAVAILABLE"));
            if (body.markdown() == null || body.version() != edit.baseVersion()
                    || body.bodyBytes() > properties.maxSourceBytes()) {
                throw new McpAuthorizationException(body.version() == edit.baseVersion() ? 413 : 409,
                        body.version() == edit.baseVersion() ? "RESPONSE_LIMIT_EXCEEDED" : "SOURCE_CHANGED");
            }
            bodyBytes += body.bodyBytes();
            if (bodyBytes > (long) properties.maxSourceBytes() * 20) {
                throw new McpAuthorizationException(413, "RESPONSE_LIMIT_EXCEEDED");
            }
            String contentHash = "sha256:" + sha256(body.markdown());
            if (body.contentHash() == null || !(body.contentHash().equals(contentHash)
                    || body.contentHash().equals(contentHash.substring(7)))) {
                throw new McpAuthorizationException(503, "DEPENDENCY_UNAVAILABLE");
            }
            edits.add(new EditDocumentContext(document.getId(), body.version(), contentHash, body.markdown(),
                    document.getDisplayName(), id(document.getFolderId())));
        }

        Response response = new Response("mcp.plan-context.v1", identity.workspaceId(), targetFolderId,
                List.copyOf(hierarchy.values()), edits);
        try {
            long limit = (long) properties.maxSourceBytes() * 20 + properties.maxResponseBytes();
            if (mapper.writeValueAsBytes(response).length > limit) {
                throw new McpAuthorizationException(413, "RESPONSE_LIMIT_EXCEEDED");
            }
        } catch (McpAuthorizationException e) {
            throw e;
        } catch (Exception e) {
            throw new McpAuthorizationException(503, "DEPENDENCY_UNAVAILABLE");
        }
        return response;
    }

    private void addSiblings(String workspaceId, DocumentAccessPolicy.Viewer viewer, UUID parent,
                             Map<String, HierarchyItem> result) {
        int remaining = MAX_HIERARCHY_ROWS - result.size();
        if (remaining < 1) throw new McpAuthorizationException(413, "RESPONSE_LIMIT_EXCEEDED");
        List<Folder> folderRows = documentData.childFolders(workspaceId, parent, remaining + 1);
        if (folderRows.size() > remaining) throw new McpAuthorizationException(413, "RESPONSE_LIMIT_EXCEEDED");
        for (Folder folder : folderRows) {
            if (viewer.canReadFolder(folder.getId())) addReadableFolder(viewer, folder, result);
        }
        remaining = MAX_HIERARCHY_ROWS - result.size();
        List<Document> documentRows = documentData.childDocuments(workspaceId, parent, remaining + 1);
        if (documentRows.size() > remaining) throw new McpAuthorizationException(413, "RESPONSE_LIMIT_EXCEEDED");
        for (Document document : documentRows) {
            if (viewer.canRead(document)) addDocument(viewer, document, result);
        }
    }

    private void addFolderWithAncestors(String workspaceId, DocumentAccessPolicy.Viewer viewer, Folder folder,
                                        Map<String, HierarchyItem> result) {
        addReadableFolder(viewer, folder, result);
        addAncestors(workspaceId, viewer, folder.getParentFolderId(), result);
    }

    private void addAncestors(String workspaceId, DocumentAccessPolicy.Viewer viewer, UUID folderId,
                              Map<String, HierarchyItem> result) {
        Set<UUID> seen = new HashSet<>();
        UUID current = folderId;
        for (int depth = 0; current != null && depth < MAX_ANCESTOR_DEPTH; depth++) {
            if (!seen.add(current)) throw new McpAuthorizationException(503, "DEPENDENCY_UNAVAILABLE");
            Folder folder = requireFolder(workspaceId, current);
            addReadableFolder(viewer, folder, result);
            current = folder.getParentFolderId();
        }
        if (current != null) throw new McpAuthorizationException(503, "DEPENDENCY_UNAVAILABLE");
    }

    private static void addReadableFolder(DocumentAccessPolicy.Viewer viewer, Folder folder,
                                          Map<String, HierarchyItem> result) {
        if (!viewer.canReadFolder(folder.getId())) throw new McpAuthorizationException(403, "PERMISSION_REVOKED");
        put(result, new HierarchyItem(folder.getId().toString(), "folder", folder.getCurrentVersion(),
                id(folder.getParentFolderId()), folder.getName(), folder.getSortOrder()));
    }

    private static void addDocument(DocumentAccessPolicy.Viewer viewer, Document document,
                                    Map<String, HierarchyItem> result) {
        if (!viewer.canRead(document)) throw new McpAuthorizationException(403, "PERMISSION_REVOKED");
        put(result, new HierarchyItem(document.getId(), "document", document.getCurrentVersion(),
                id(document.getFolderId()), document.getDisplayName(), document.getSortOrder()));
    }

    private static void put(Map<String, HierarchyItem> result, HierarchyItem item) {
        result.putIfAbsent(item.type() + ":" + item.id(), item);
        if (result.size() > MAX_HIERARCHY_ROWS) {
            throw new McpAuthorizationException(413, "RESPONSE_LIMIT_EXCEEDED");
        }
    }

    private Folder requireFolder(String workspaceId, UUID folderId) {
        return documentData.findFolder(workspaceId, folderId)
                .orElseThrow(() -> new McpAuthorizationException(404, "RESOURCE_NOT_FOUND"));
    }

    private static void requireFolderEdit(DocumentAccessPolicy.Viewer viewer, UUID folderId) {
        if (!viewer.canEditFolder(folderId)) throw new McpAuthorizationException(403, "PERMISSION_REVOKED");
    }

    private static void requireVersion(Long requested, long current) {
        if (requested != null && requested != current) {
            throw new McpAuthorizationException(409, "SOURCE_CHANGED");
        }
    }

    private static void validateInput(List<Resource> resources, List<EditDocument> edits) {
        if (resources == null || edits == null || resources.size() > 40 || edits.size() > 20) {
            throw new McpAuthorizationException(400, "INVALID_INPUT");
        }
        Set<String> resourceKeys = new HashSet<>();
        Map<String, Long> documentVersions = new LinkedHashMap<>();
        for (Resource resource : resources) {
            if (resource == null || !("folder".equals(resource.resourceType())
                    || "document".equals(resource.resourceType()))
                    || resource.resourceId() == null || resource.baseVersion() != null && resource.baseVersion() < 1) {
                throw new McpAuthorizationException(400, "INVALID_INPUT");
            }
            if ("folder".equals(resource.resourceType())) folderId(resource.resourceId());
            else if (!DOCUMENT_ID.matcher(resource.resourceId()).matches())
                throw new McpAuthorizationException(400, "INVALID_INPUT");
            if (!resourceKeys.add(resource.resourceType() + ":" + resource.resourceId()))
                throw new McpAuthorizationException(400, "INVALID_INPUT");
            if ("document".equals(resource.resourceType()) && resource.baseVersion() != null)
                documentVersions.put(resource.resourceId(), resource.baseVersion());
        }
        Set<String> editIds = new HashSet<>();
        for (EditDocument edit : edits) {
            if (edit == null || edit.documentId() == null || !DOCUMENT_ID.matcher(edit.documentId()).matches()
                    || edit.baseVersion() < 1 || !editIds.add(edit.documentId())
                    || !Long.valueOf(edit.baseVersion()).equals(documentVersions.get(edit.documentId()))) {
                throw new McpAuthorizationException(400, "INVALID_INPUT");
            }
        }
    }

    private static UUID folderId(String value) {
        if (value == null) return null;
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) throw new IllegalArgumentException();
            return parsed;
        } catch (IllegalArgumentException e) {
            throw new McpAuthorizationException(400, "INVALID_INPUT");
        }
    }

    private static String id(UUID value) { return value == null ? null : value.toString(); }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Resource(@JsonProperty("resource_type") String resourceType,
                           @JsonProperty("resource_id") String resourceId,
                           @JsonProperty("base_version") Long baseVersion) {}
    public record EditDocument(@JsonProperty("document_id") String documentId,
                               @JsonProperty("base_version") long baseVersion) {}
    public record HierarchyItem(String id, String type,
                                @JsonProperty("current_version") long currentVersion,
                                @JsonProperty("parent_id") String parentId,
                                String name, Long position) {}
    public record EditDocumentContext(@JsonProperty("document_id") String documentId,
                                      @JsonProperty("current_version") long currentVersion,
                                      @JsonProperty("content_hash") String contentHash,
                                      @JsonProperty("current_markdown") String currentMarkdown,
                                      @JsonProperty("display_name") String displayName,
                                      @JsonProperty("parent_folder_id") String parentFolderId) {}
    public record Response(@JsonProperty("schema_version") String schemaVersion,
                           @JsonProperty("workspace_id") String workspaceId,
                           @JsonProperty("target_folder_id") String targetFolderId,
                           List<HierarchyItem> hierarchy,
                           @JsonProperty("edit_documents") List<EditDocumentContext> editDocuments) {}
}
