package fruition.core.document.service;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.Folder;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import fruition.core.document.repository.PostgresDocumentEditStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import fruition.core.mcp.McpIdentity;

import java.util.List;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Document-domain reads used by the MCP boundary; authorization remains caller-provided and current. */
@Service
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpDocumentDataGateway {
    private final DocumentRepository documents;
    private final FolderRepository folders;
    private final DocumentAccessPolicy policy;
    private final PostgresDocumentEditStore editStore;
    private final DocumentEditStateInitializer editStateInitializer;
    public McpDocumentDataGateway(DocumentRepository documents, FolderRepository folders,
                                  DocumentAccessPolicy policy,
                                  PostgresDocumentEditStore editStore,
                                  DocumentEditStateInitializer editStateInitializer) {
        this.documents = documents; this.folders = folders; this.policy = policy;
        this.editStore = editStore; this.editStateInitializer = editStateInitializer;
    }
    public List<Document> search(String workspaceId, String query, int limit) {
        return documents.searchMcpCandidates(workspaceId, query, PageRequest.of(0, limit));
    }
    public Optional<Document> find(String workspaceId, String documentId) {
        return documents.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId);
    }
    public Optional<Folder> findFolder(String workspaceId, java.util.UUID folderId) {
        return folders.findByIdAndWorkspaceIdAndDeletedAtIsNull(folderId, workspaceId);
    }
    public List<Folder> childFolders(String workspaceId, java.util.UUID parentId, int limit) {
        return folders.findChildren(workspaceId, parentId, PageRequest.of(0, limit));
    }
    public List<Document> childDocuments(String workspaceId, java.util.UUID parentId, int limit) {
        return documents.findChildDocuments(workspaceId, parentId, PageRequest.of(0, limit));
    }
    public Optional<CurrentIdentity> currentIdentity(Document document, int maxBytes) {
        if ("chat_export".equals(document.getOrigin())) {
            return documents.findMcpChatIdentity(document.getWorkspaceId(), document.getId(), maxBytes)
                    .map(identity -> new CurrentIdentity(identity.getVersion(), identity.getContentHash(),
                            identity.getBodyBytes()));
        }
        return editStore.findCurrentIdentity(document.getWorkspaceId(), document.getId())
                .map(identity -> new CurrentIdentity(identity.version(), identity.contentHash(), 0))
                .or(() -> Optional.of(new CurrentIdentity(document.getCurrentVersion(),
                        document.getCurrentContentHash(), 0)));
    }
    /** 본문 편집 revision. documents.current_version(이름·이동·삭제 메타데이터 버전)과 별개다. */
    public Optional<Long> currentEditRevision(Document document) {
        return editStore.findCurrentIdentity(document.getWorkspaceId(), document.getId())
                .map(PostgresDocumentEditStore.CurrentIdentity::version);
    }
    public Optional<CurrentBody> currentBody(Document document, int maxBytes) {
        if ("chat_export".equals(document.getOrigin())) {
            String markdown = document.getPipelineInputMarkdown();
            if (markdown == null) return Optional.empty();
            int bytes = markdown.getBytes(StandardCharsets.UTF_8).length;
            return Optional.of(new CurrentBody(document.getCurrentVersion(), sha256(markdown),
                    bytes <= maxBytes ? markdown : null, bytes));
        }
        Optional<PostgresDocumentEditStore.CurrentBody> current =
                editStore.findCurrentBody(document.getWorkspaceId(), document.getId(), maxBytes);
        if (current.isPresent()) return current.map(body -> new CurrentBody(
                body.version(), body.contentHash(), body.markdown(), body.bodyBytes()));
        if (document.getByteSize() > maxBytes) return Optional.of(new CurrentBody(
                document.getCurrentVersion(), document.getCurrentContentHash(), null,
                (int) Math.min(Integer.MAX_VALUE, document.getByteSize())));
        editStateInitializer.initializeIfNeeded(document);
        return editStore.findCurrentBody(document.getWorkspaceId(), document.getId(), maxBytes)
                .map(body -> new CurrentBody(body.version(), body.contentHash(), body.markdown(), body.bodyBytes()));
    }
    public DocumentAccessPolicy.Viewer viewer(String workspaceId, String userId,
                                              boolean owner, boolean canConvert) {
        return policy.viewer(workspaceId, userId, owner, canConvert);
    }
    public void requireEdit(Document document, McpIdentity identity) {
        policy.requireEdit(document, identity.userId(), identity.owner(), identity.canConvert());
    }
    public DocumentAccessPolicy.Viewer viewer(McpIdentity identity) {
        return policy.viewer(identity.workspaceId(), identity.userId(), identity.owner(), identity.canConvert());
    }
    /**
     * 외부 AI가 변환할 원본 PDF. 웹 변환 요청과 같은 판정(viewer.canConvert)을 쓰고, 넘겨받은 hash가 지금 원본의
     * hash와 같을 때만 돌려준다. 없거나 권한이 없으면 NOT_FOUND, 원본이 바뀌었으면 CHANGED다.
     */
    public ConvertSource convertSource(McpIdentity identity, String sourceDocumentId, String sourceContentHash) {
        Document source = find(identity.workspaceId(), sourceDocumentId)
                .filter(DocumentAccessPolicy::isPdf).orElse(null);
        if (source == null || !viewer(identity).canConvert(source)) return new ConvertSource(null, false);
        return new ConvertSource(source, pdfHash(source).equals(sourceContentHash));
    }

    /** 원본 PDF 바이트의 hash(documents.content_hash)를 MCP 형식(sha256:hex)으로 돌려준다. */
    public static String pdfHash(Document source) {
        String hash = source.getContentHash();
        if (hash == null) return "";
        return hash.startsWith("sha256:") ? hash : "sha256:" + hash;
    }

    /** {@code document}가 null이면 없거나 변환 권한이 없다. */
    public record ConvertSource(Document document, boolean hashMatches) {}

    private static String sha256(String markdown) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(markdown.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    public record CurrentBody(long version, String contentHash, String markdown, int bodyBytes) {}
    public record CurrentIdentity(long version, String contentHash, int bodyBytes) {}
}
