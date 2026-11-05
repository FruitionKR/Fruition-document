package fruition.core.document.service;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.Folder;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 다른 모듈이 문서·폴더 저장소를 직접 쓰지 않고, 살아 있는 대상을 이 사용자가 편집할 수 있는지 묻는다. */
@Service
public class DocumentEditTargets {

    private final DocumentRepository documentRepository;
    private final FolderRepository folderRepository;

    public DocumentEditTargets(DocumentRepository documentRepository, FolderRepository folderRepository) {
        this.documentRepository = documentRepository;
        this.folderRepository = folderRepository;
    }

    /** 문서가 이 워크스페이스에 있고 삭제되지 않았으며 편집할 수 있을 때만 true. */
    public boolean canEditDocument(String workspaceId, DocumentAccessPolicy.Viewer viewer, String documentId) {
        return documentId != null && documentRepository
                .findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .map(viewer::canEdit)
                .orElse(false);
    }

    /** 폴더가 이 워크스페이스에 있고 삭제되지 않았으며 편집할 수 있을 때만 true. null(최상위)은 멤버면 쓸 수 있다. */
    public boolean canEditFolder(String workspaceId, DocumentAccessPolicy.Viewer viewer, UUID folderId) {
        if (folderId == null) return true;
        return folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(folderId, workspaceId).isPresent()
                && viewer.canEditFolder(folderId);
    }

    /** 살아 있는 문서가 지금 있는 폴더. 최상위거나 문서가 없으면 null. */
    public UUID folderOfDocument(String workspaceId, String documentId) {
        return documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .map(Document::getFolderId)
                .orElse(null);
    }

    /** 살아 있는 폴더의 지금 상위. 최상위거나 폴더가 없으면 null. */
    public UUID parentOfFolder(String workspaceId, UUID folderId) {
        return folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(folderId, workspaceId)
                .map(Folder::getParentFolderId)
                .orElse(null);
    }
}
