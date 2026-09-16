package fruition.core.document.service;

import fruition.shared.idempotency.IdempotencyService;
import fruition.core.document.domain.Document;
import fruition.core.document.dto.DocumentPositionRequest;
import fruition.core.document.dto.DocumentPositionResponse;
import fruition.core.document.exception.HierarchyItemNotFoundException;
import fruition.core.document.exception.HierarchyVersionConflictException;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** 문서를 폴더 사이·최상위로 이동하고 형제 마지막에 배치한다(TASK-H003). */
@Service
public class DocumentPlacementService {

    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final DocumentRepository documentRepository;
    private final FolderRepository folderRepository;
    private final IdempotencyService idempotencyService;
    private final SiblingReorderer siblingReorderer;

    public DocumentPlacementService(WorkspaceAccessGuard workspaceAccessGuard,
                                    DocumentRepository documentRepository,
                                    FolderRepository folderRepository,
                                    IdempotencyService idempotencyService,
                                    SiblingReorderer siblingReorderer) {
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.documentRepository = documentRepository;
        this.folderRepository = folderRepository;
        this.idempotencyService = idempotencyService;
        this.siblingReorderer = siblingReorderer;
    }

    @Transactional
    public DocumentPositionResponse move(String workspaceId, String userId, String documentId,
                                         String idempotencyKey, DocumentPositionRequest request) {
        UUID targetFolderId = request.folderId();
        String scope = "PATCH:/api/workspaces/" + workspaceId + "/documents/" + documentId + "/position";
        String hash = idempotencyService.requestHash(
                String.valueOf(targetFolderId), String.valueOf(request.position()),
                String.valueOf(request.baseVersion()));
        return idempotencyService.execute(
                userId, scope, idempotencyKey, hash, DocumentPositionResponse.class, 200,
                response -> documentId, () -> {
                    verifyMembership(workspaceId, userId);
                    documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                            .orElseThrow(() -> new HierarchyItemNotFoundException("문서를 찾을 수 없습니다."));
                    if (targetFolderId != null && folderRepository
                            .findActiveForUpdate(targetFolderId, workspaceId).isEmpty()) {
                        throw new HierarchyItemNotFoundException("대상 폴더를 찾을 수 없습니다.");
                    }
                    long sortOrder = siblingReorderer.placeDocument(
                            workspaceId, targetFolderId, documentId, request.position());
                    int updated = documentRepository.moveIfVersionMatches(
                            documentId, workspaceId, request.baseVersion(), targetFolderId,
                            sortOrder, Instant.now());
                    if (updated == 0) {
                        throw new HierarchyVersionConflictException("문서가 이미 변경되어 이동할 수 없습니다.");
                    }
                    Document moved = documentRepository
                            .findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                            .orElseThrow(() -> new HierarchyItemNotFoundException("문서를 찾을 수 없습니다."));
                    return new DocumentPositionResponse(
                            moved.getId(), moved.getFolderId(), moved.getSortOrder(), moved.getCurrentVersion());
                });
    }

    private void verifyMembership(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
    }
}
