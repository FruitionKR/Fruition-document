package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.dto.DocumentRestorePlan;
import fruition.core.aihistory.exception.InvalidRestoreRequestException;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.document.domain.DocumentContentVersion;
import fruition.core.document.domain.DocumentContentVersionId;
import fruition.core.document.dto.DocumentContentSaveResponse;
import fruition.core.document.exception.DocumentContentVersionNotFoundException;
import fruition.core.document.repository.DocumentContentVersionRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.service.DocumentService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문서를 예전 편집 revision의 내용으로 되돌린다.
 *
 * <p>과거 편집 revision의 내용을 지우지 않고 <b>그 내용으로 새 편집 revision을 쌓는다.</b> 되돌린 것도
 * 다시 되돌릴 수 있고, revision 번호가 되돌아가 같은 번호가 다른 내용을 가리키는 일이 없다.
 * Wiki revision과 같은 원칙이다.
 *
 * <p>저장 자체는 {@link DocumentService#saveContentInCurrentTransaction}에 맡긴다. 편집 잠금·낙관적 잠금·편집 상태
 * 갱신이 이미 그 안에 있고, 되돌리기라고 다르게 처리할 이유가 없다. 적용 표를 넘기지 않으므로
 * {@code document_edit} 로그는 생기지 않는다.
 *
 * <p>본문 저장과 복구 변경내역은 같은 PostgreSQL transaction에서 커밋된다. 기록이 실패하면
 * 본문 저장도 함께 롤백된다.
 */
@Component
public class DocumentRestoreApplier {

    private final DocumentService documentService;
    private final DocumentContentVersionRepository contentVersionRepository;
    private final DocumentRepository documentRepository;
    private final OperationChangeRepository operationChangeRepository;

    public DocumentRestoreApplier(DocumentService documentService,
                                  DocumentContentVersionRepository contentVersionRepository,
                                  DocumentRepository documentRepository,
                                  OperationChangeRepository operationChangeRepository) {
        this.documentService = documentService;
        this.contentVersionRepository = contentVersionRepository;
        this.documentRepository = documentRepository;
        this.operationChangeRepository = operationChangeRepository;
    }

    /** @return 되돌리기로 만들어진 새 편집 revision */
    @Transactional
    public long apply(OperationLog restore, DocumentRestorePlan plan) {
        DocumentContentVersion target = contentVersionRepository
                .findById(new DocumentContentVersionId(plan.documentId(), plan.toVersion()))
                .orElseThrow(() -> new DocumentContentVersionNotFoundException(
                        plan.documentId(), plan.toVersion()));
        String resourceDisplayName = documentRepository
                .findByIdAndWorkspaceIdForUpdate(plan.documentId(), restore.getWorkspaceId())
                .filter(document -> document.getDeletedAt() == null)
                .map(document -> document.getDisplayName())
                .orElse(null);

        DocumentContentSaveResponse saved = documentService.saveContentInCurrentTransaction(
                restore.getWorkspaceId(), restore.getUserId(), plan.documentId(),
                target.getMarkdown(), plan.fromVersion(),
                "op-restore:" + restore.getOperationId(), null);

        if (!saved.changed()) {
            throw new InvalidRestoreRequestException(
                    "문서 내용이 되돌릴 편집 revision과 같아 변경할 것이 없습니다: revision=" + plan.toVersion());
        }

        operationChangeRepository.save(new OperationChange(
                restore.getOperationId(), ResourceType.document, plan.documentId(),
                resourceDisplayName, plan.fromVersion(), saved.currentVersion(), ChangeType.restored,
                "편집 revision " + plan.toVersion() + " 내용으로 되돌렸습니다.", null, null));

        return saved.currentVersion();
    }
}
