package fruition.core.document.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.domain.Document;
import fruition.core.document.dto.DocumentPermissionResponse;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import fruition.core.document.exception.HierarchyItemNotFoundException;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 문서·폴더 권한 설정을 바꾼다. 문서는 그 문서의 소유자나 워크스페이스 OWNER가, 폴더는 OWNER만 바꾼다.
 * 폴더에는 소유자가 없어서다. access가 null이면 설정을 지워 상위 폴더 설정이나 기본값(편집)을 따르게 한다.
 */
@Service
public class DocumentPermissionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentPermissionService.class);

    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final DocumentRepository documentRepository;
    private final FolderRepository folderRepository;
    private final JdbcTemplate jdbc;

    public DocumentPermissionService(WorkspaceAccessGuard workspaceAccessGuard, DocumentRepository documentRepository,
                                     FolderRepository folderRepository, JdbcTemplate jdbc) {
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.documentRepository = documentRepository;
        this.folderRepository = folderRepository;
        this.jdbc = jdbc;
    }

    @Transactional
    public DocumentPermissionResponse setDocument(String workspaceId, String userId, String documentId, String access) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!userId.equals(document.getUserId()) && !workspaceAccessGuard.isOwner(workspaceId, userId)) {
            throw new DocumentWriteForbiddenException("문서 소유자나 워크스페이스 OWNER만 권한을 바꿀 수 있습니다.");
        }
        write("document_id", documentId, workspaceId, userId, access);
        log.info("[문서 권한 변경] workspaceId={} documentId={} userId={} access={}", workspaceId, documentId, userId, access);
        return new DocumentPermissionResponse(access);
    }

    @Transactional
    public DocumentPermissionResponse setFolder(String workspaceId, String userId, UUID folderId, String access) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(folderId, workspaceId)
                .orElseThrow(() -> new HierarchyItemNotFoundException("폴더를 찾을 수 없습니다."));
        if (!workspaceAccessGuard.isOwner(workspaceId, userId)) {
            throw new DocumentWriteForbiddenException("워크스페이스 OWNER만 폴더 권한을 바꿀 수 있습니다.");
        }
        write("folder_id", folderId, workspaceId, userId, access);
        log.info("[폴더 권한 변경] workspaceId={} folderId={} userId={} access={}", workspaceId, folderId, userId, access);
        return new DocumentPermissionResponse(access);
    }

    /** column은 상수 두 가지(document_id, folder_id)만 들어온다. */
    private void write(String column, Object targetId, String workspaceId, String userId, String access) {
        if (access == null) {
            jdbc.update("DELETE FROM document_permissions WHERE " + column + " = ?", targetId);
            return;
        }
        jdbc.update("INSERT INTO document_permissions(workspace_id, " + column + ", access, updated_by) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (" + column + ") DO UPDATE SET access = EXCLUDED.access, "
                        + "updated_by = EXCLUDED.updated_by, updated_at = now()",
                workspaceId, targetId, access, userId);
    }
}
