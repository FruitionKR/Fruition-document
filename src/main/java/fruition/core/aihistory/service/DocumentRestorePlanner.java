package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.dto.DocumentRestorePlan;
import fruition.core.aihistory.exception.InvalidRestoreRequestException;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.service.DocumentEditStateInitializer;
import org.springframework.stereotype.Component;

/**
 * 문서 편집 되돌리기 계획. Wiki와 달리 계산할 것이 없다.
 *
 * <p>편집 revision은 선형이라 "그 작업이 손대기 직전 revision"이 곧 목적지다. 되돌릴 지점은
 * 이미 {@code ai_operation_changes.before_revision}에 적혀 있고, 현재 revision은
 * {@code document_edit_states.revision}이 기준이다.
 */
@Component
public class DocumentRestorePlanner {

    private final OperationChangeRepository operationChangeRepository;
    private final DocumentRepository documentRepository;
    private final DocumentEditStateInitializer editStateInitializer;
    private final DocumentEditStateRepository editStateRepository;

    public DocumentRestorePlanner(OperationChangeRepository operationChangeRepository,
                                  DocumentRepository documentRepository,
                                  DocumentEditStateInitializer editStateInitializer,
                                  DocumentEditStateRepository editStateRepository) {
        this.operationChangeRepository = operationChangeRepository;
        this.documentRepository = documentRepository;
        this.editStateInitializer = editStateInitializer;
        this.editStateRepository = editStateRepository;
    }

    public DocumentRestorePlan plan(OperationLog target) {
        OperationChange change = operationChangeRepository
                .findByOperationIdOrderByIdAsc(target.getOperationId()).stream()
                .filter(c -> c.getResourceType() == ResourceType.document)
                .findFirst()
                .orElseThrow(() -> new InvalidRestoreRequestException(
                        "되돌릴 문서 변경내역이 없습니다: operationId=" + target.getOperationId()));

        // 새로 만든 문서라면 돌아갈 지점이 없다.
        if (change.getBeforeRevision() == null) {
            throw new InvalidRestoreRequestException(
                    "되돌릴 이전 편집 revision이 없는 작업입니다: operationId=" + target.getOperationId());
        }

        String documentId = change.getResourceId();
        Document document = documentRepository
                .findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, target.getWorkspaceId())
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        editStateInitializer.initializeIfNeeded(document);
        long currentRevision = editStateRepository.findById(documentId)
                .map(DocumentEditState::getRevision)
                .orElseThrow(() -> new InvalidRestoreRequestException(
                        "현재 Markdown 편집 상태를 찾을 수 없습니다: documentId=" + documentId));
        long toVersion = change.getBeforeRevision();
        if (currentRevision == toVersion) {
            throw new InvalidRestoreRequestException(
                    "문서가 이미 해당 편집 revision 내용입니다: revision=" + toVersion);
        }
        return new DocumentRestorePlan(documentId, currentRevision, toVersion);
    }
}
