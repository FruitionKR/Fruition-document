package fruition.core.document.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.domain.Document;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 문서 공동 편집 권한. 워크스페이스 멤버는 기본으로 모든 문서를 편집할 수 있고, 문서·폴더별 설정이
 * 'view'면 보기만 할 수 있다. 문서는 자기 설정 → 가장 가까운 상위 폴더 설정 → 기본값(편집) 순으로 따른다.
 *
 * <p>문서 소유자와 워크스페이스 OWNER는 설정과 관계없이 편집할 수 있다. 삭제·휴지통 복구는 문서 소유자와 OWNER만 한다.
 * 멤버인지는 호출하는 쪽이 먼저 확인한다.
 */
@Component
public class DocumentAccessPolicy {

    public static final String EDIT = "edit";
    public static final String VIEW = "view";

    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final JdbcTemplate jdbc;

    public DocumentAccessPolicy(WorkspaceAccessGuard workspaceAccessGuard, JdbcTemplate jdbc) {
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.jdbc = jdbc;
    }

    /** 한 요청 안에서 여러 문서의 권한을 판정할 때 쓴다. 설정과 폴더 계층을 한 번만 읽는다. */
    public Viewer viewer(String workspaceId, String userId) {
        Map<String, String> documentAccess = new HashMap<>();
        Map<UUID, String> folderAccess = new HashMap<>();
        jdbc.query("SELECT document_id, folder_id, access FROM document_permissions WHERE workspace_id = ?", rs -> {
            String documentId = rs.getString("document_id");
            if (documentId != null) {
                documentAccess.put(documentId, rs.getString("access"));
            } else {
                folderAccess.put(rs.getObject("folder_id", UUID.class), rs.getString("access"));
            }
        }, workspaceId);
        Map<UUID, UUID> parents = new HashMap<>();
        if (!folderAccess.isEmpty()) {
            // 폴더 설정이 없으면 상위를 따라갈 일이 없으므로 계층을 읽지 않는다(대부분의 워크스페이스).
            jdbc.query("SELECT id, parent_folder_id FROM folders WHERE workspace_id = ?",
                    rs -> { parents.put(rs.getObject("id", UUID.class), rs.getObject("parent_folder_id", UUID.class)); },
                    workspaceId);
        }
        return new Viewer(userId, workspaceAccessGuard.isOwner(workspaceId, userId),
                documentAccess, folderAccess, parents);
    }

    public void requireEdit(Document document, String userId) {
        if (!viewer(document.getWorkspaceId(), userId).canEdit(document)) {
            throw new DocumentWriteForbiddenException("이 문서를 편집할 권한이 없습니다.");
        }
    }

    public void requireDelete(Document document, String userId) {
        if (!viewer(document.getWorkspaceId(), userId).canDelete(document)) {
            throw new DocumentWriteForbiddenException("문서 소유자나 워크스페이스 OWNER만 삭제할 수 있습니다.");
        }
    }

    public void requireFolderEdit(String workspaceId, UUID folderId, String userId) {
        if (!viewer(workspaceId, userId).canEditFolder(folderId)) {
            throw new DocumentWriteForbiddenException("이 폴더를 편집할 권한이 없습니다.");
        }
    }

    public record Viewer(String userId, boolean workspaceOwner, Map<String, String> documentAccess,
                         Map<UUID, String> folderAccess, Map<UUID, UUID> parents) {

        /** 폴더가 꼬여 순환이 생겨도 멈추도록 거슬러 올라가는 깊이를 제한한다. */
        private static final int MAX_DEPTH = 64;

        public boolean canEdit(Document document) {
            return workspaceOwner || userId.equals(document.getUserId()) || EDIT.equals(access(document));
        }

        public boolean canDelete(Document document) {
            return workspaceOwner || userId.equals(document.getUserId());
        }

        public boolean canEditFolder(UUID folderId) {
            return workspaceOwner || EDIT.equals(folderAccessOf(folderId));
        }

        /** 이 문서에 직접 건 설정. 없으면 null(상위 폴더 또는 기본값을 따름). */
        public String override(Document document) {
            return documentAccess.get(document.getId());
        }

        /** 실제로 적용되는 접근 수준. */
        public String access(Document document) {
            String own = documentAccess.get(document.getId());
            return own != null ? own : folderAccessOf(document.getFolderId());
        }

        private String folderAccessOf(UUID folderId) {
            UUID current = folderId;
            for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
                String access = folderAccess.get(current);
                if (access != null) {
                    return access;
                }
                current = parents.get(current);
            }
            return EDIT;
        }
    }
}
