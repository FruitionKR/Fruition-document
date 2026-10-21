package fruition.core.document.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspacePermissionRequiredException;
import fruition.core.authz.WorkspacePermissions;
import fruition.core.document.domain.Document;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import fruition.core.document.exception.InvalidDocumentConvertRequestException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 문서 공동 편집 권한. 워크스페이스 멤버는 기본으로 모든 문서를 편집할 수 있고, 문서·폴더별 설정이
 * 'view'면 보기만 할 수 있다. 문서는 자기 설정 → 가장 가까운 상위 폴더 설정 → 기본값(편집) 순으로 따른다.
 *
 * <p>문서 소유자와 워크스페이스 OWNER는 설정과 관계없이 편집할 수 있다. 삭제·휴지통 복구는 문서 소유자와 OWNER만 한다.
 * PDF Markdown 변환은 원본 파일이 있는 PDF만 대상이고, 업로더 본인과 {@code document.convert} 권한자(OWNER 포함)만 한다.
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
        // 소유자 여부와 변환 권한을 한 번 읽은 역할·권한으로 함께 판정한다.
        WorkspaceAccessGuard.Access access = workspaceAccessGuard.access(workspaceId, userId);
        return new Viewer(userId, access.isOwner(), access.hasPermission(WorkspacePermissions.DOCUMENT_CONVERT),
                documentAccess, folderAccess, parents);
    }

    /**
     * PDF Markdown 변환 요청을 판정한다. 대상이 아니면 400, 권한이 없으면 403. 최종 판정은 목록의 can_convert와
     * 같은 {@link #canConvert}를 쓴다. 문서 권한 설정을 읽을 필요가 없어 Viewer를 만들지 않는다.
     */
    public void requireConvert(Document document, String userId) {
        if (!isPdf(document)) {
            throw new InvalidDocumentConvertRequestException("PDF 원본 문서만 Markdown으로 변환할 수 있습니다.");
        }
        if (document.getSourceUri() == null) {
            throw new InvalidDocumentConvertRequestException("원본 파일이 없는 문서는 변환할 수 없습니다.");
        }
        if (!canConvert(document, userId, () -> workspaceAccessGuard.hasPermission(
                document.getWorkspaceId(), userId, WorkspacePermissions.DOCUMENT_CONVERT))) {
            throw new WorkspacePermissionRequiredException(WorkspacePermissions.DOCUMENT_CONVERT);
        }
    }

    public static boolean isPdf(Document document) {
        return "application/pdf".equals(document.getMimeType())
                || document.getNormalizedFilename().endsWith(".pdf");
    }

    /** 변환 대상(삭제되지 않았고 원본 파일이 있는 PDF)이고, 업로더 본인이거나 document.convert 권한(OWNER 포함)이 있는지. */
    private static boolean canConvert(Document document, String userId, BooleanSupplier hasConvertPermission) {
        return document.getDeletedAt() == null
                && isPdf(document)
                && document.getSourceUri() != null
                && (userId.equals(document.getUserId()) || hasConvertPermission.getAsBoolean());
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

    /** {@code convertPermission}: document.convert 권한 보유 여부. OWNER는 항상 true다. */
    public record Viewer(String userId, boolean workspaceOwner, boolean convertPermission,
                         Map<String, String> documentAccess, Map<UUID, String> folderAccess, Map<UUID, UUID> parents) {

        /** 폴더가 꼬여 순환이 생겨도 멈추도록 거슬러 올라가는 깊이를 제한한다. */
        private static final int MAX_DEPTH = 64;

        public boolean canEdit(Document document) {
            return workspaceOwner || userId.equals(document.getUserId()) || EDIT.equals(access(document));
        }

        /** 보기·편집 어느 쪽이든 읽을 수 있다. 알 수 없는 설정 값은 읽지 못하는 것으로 본다(fail-closed). */
        public boolean canRead(Document document) {
            return workspaceOwner || userId.equals(document.getUserId()) || readable(access(document));
        }

        /** 폴더 안 문서를 읽을 수 있는지. 판정은 {@link #canRead(Document)}와 같다. */
        public boolean canReadFolder(UUID folderId) {
            return workspaceOwner || readable(folderAccessOf(folderId));
        }

        private static boolean readable(String access) {
            return EDIT.equals(access) || VIEW.equals(access);
        }

        public boolean canDelete(Document document) {
            return workspaceOwner || userId.equals(document.getUserId());
        }

        /** 변환 요청({@link DocumentAccessPolicy#requireConvert})이 받아들여지는지. 판정 코드를 함께 쓴다. */
        public boolean canConvert(Document document) {
            return DocumentAccessPolicy.canConvert(document, userId, () -> convertPermission);
        }

        public boolean canEditFolder(UUID folderId) {
            return workspaceOwner || EDIT.equals(folderAccessOf(folderId));
        }

        /** 이 문서에 직접 건 설정. 없으면 null(상위 폴더 또는 기본값을 따름). */
        public String override(Document document) {
            return documentAccess.get(document.getId());
        }

        /** 이 폴더에 직접 건 설정. 없으면 null(상위 폴더 또는 기본값을 따름). */
        public String folderOverride(UUID folderId) {
            return folderAccess.get(folderId);
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
