package fruition.core.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.dto.OperationResultRequest;
import fruition.core.aihistory.dto.WikiPublicationEvent;
import fruition.core.aihistory.exception.InvalidCallbackPayloadException;
import fruition.core.aihistory.exception.OperationNotFoundException;
import fruition.core.aihistory.exception.WikiPublicationConflictException;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.WikiPublicationInboxRepository;
import fruition.core.aitask.service.AiTaskResultApplier;
import fruition.core.wiki.service.WikiPublicationStateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.HexFormat;

/** Applies and receipts {@code ai.wiki.publication.v1} against server-owned registration. */
@Service
public class WikiPublicationEventApplier {

    private final ObjectMapper objectMapper;
    private final OperationLogRepository operations;
    private final OperationIngestService operationIngest;
    private final AiTaskResultApplier taskResults;
    private final WikiPublicationInboxRepository inbox;
    private final WikiPublicationPurgeFence purgeFence;
    private final OperationChangeRepository changes;
    private final WikiPublicationStateService wikiState;

    public WikiPublicationEventApplier(ObjectMapper objectMapper,
                                       OperationLogRepository operations,
                                       OperationIngestService operationIngest,
                                       AiTaskResultApplier taskResults,
                                       WikiPublicationInboxRepository inbox,
                                       WikiPublicationPurgeFence purgeFence,
                                       OperationChangeRepository changes,
                                       WikiPublicationStateService wikiState) {
        this.objectMapper = objectMapper;
        this.operations = operations;
        this.operationIngest = operationIngest;
        this.taskResults = taskResults;
        this.inbox = inbox;
        this.purgeFence = purgeFence;
        this.changes = changes;
        this.wikiState = wikiState;
    }

    @Transactional
    public ApplyResult apply(WikiPublicationEvent event) {
        validateShape(event);
        OperationLog operation = operations.findByIdForUpdate(event.operationId())
                .orElseThrow(() -> new OperationNotFoundException(event.operationId()));
        validateRegistration(event, operation);

        // The registration, not the event, supplies both authority scopes.
        purgeFence.lockAndRejectPurged(operation.getWorkspaceId(), operation.getUserId());

        String resultHash = operationIngest.payloadHash(event.result());
        String eventHash = hash(new DomainEvent(
                event.publicationId(), event.operationId(), event.workspaceId(), event.userId(),
                event.kind(), event.outcome(), event.result(), event.publishedReferenceHash(), event.committedAt()));
        if (!event.publishedReferenceHash().equals("sha256:" + resultHash)) {
            throw invalid("published_reference_hash가 result와 일치하지 않습니다.");
        }

        var existing = inbox.find(event);
        if (!existing.isEmpty()) {
            var receipt = existing.getFirst();
            boolean sameDomainIdentity = existing.size() == 1
                    && receipt.publicationId().equals(event.publicationId())
                    && receipt.operationId().equals(event.operationId());
            if (sameDomainIdentity && receipt.eventHash().equals(eventHash) && receipt.resultHash().equals(resultHash)
                    && receipt.publishedReferenceHash().equals(event.publishedReferenceHash())) {
                claimEventId(event);
                return new ApplyResult(false, true, false, receipt.disposition(), receipt.rejectionReason());
            }
            throw new WikiPublicationConflictException(
                    "Wiki publication identity가 다른 결과에 재사용됐습니다: operationId=" + event.operationId());
        }

        boolean accepted = true;
        if (event.kind() == WikiPublicationEvent.Kind.MANUAL_EDIT) {
            validateManualResult(event, operation);
        } else if (event.kind() == WikiPublicationEvent.Kind.RESTORE
                || event.kind() == WikiPublicationEvent.Kind.RECOVERY) {
            accepted = taskResults.applySuccessfulRestore(operation, event.result());
        } else {
            operationIngest.accept(operation.getOperationId(), event.result());
        }

        if (!accepted) {
            insertReceipt(event, eventHash, resultHash,
                    WikiPublicationInboxRepository.Disposition.REJECTED_CONFLICT, "STALE_RESTORE");
            return new ApplyResult(false, false, false,
                    WikiPublicationInboxRepository.Disposition.REJECTED_CONFLICT, "STALE_RESTORE");
        }

        boolean publicEffect = changes.hasPublicEffect(operation.getOperationId());
        if ((event.outcome() == WikiPublicationEvent.Outcome.PUBLISHED) != publicEffect) {
            throw invalid("publication outcome이 실제 반영 결과와 일치하지 않습니다.");
        }
        insertReceipt(event, eventHash, resultHash,
                WikiPublicationInboxRepository.Disposition.ACCEPTED, null);
        if (publicEffect) wikiState.bumpWorkspaceVersion(operation.getWorkspaceId());
        return new ApplyResult(true, false, publicEffect,
                WikiPublicationInboxRepository.Disposition.ACCEPTED, null);
    }

    private void validateShape(WikiPublicationEvent event) {
        if (event == null || !WikiPublicationEvent.TYPE.equals(event.eventType())) {
            throw invalid("event_type이 ai.wiki.publication.v1이 아닙니다.");
        }
        require(event.eventId(), "event_id");
        require(event.publicationId(), "publication_id");
        require(event.operationId(), "operation_id");
        require(event.workspaceId(), "workspace_id");
        require(event.userId(), "user_id");
        require(event.publishedReferenceHash(), "published_reference_hash");
        if (event.kind() == null || event.outcome() == null || event.result() == null
                || event.committedAt() == null) {
            throw invalid("kind, outcome, result, committed_at은 필수입니다.");
        }
        if (!event.operationId().equals(event.result().operationId())) {
            throw invalid("event와 result의 operation_id가 다릅니다.");
        }
    }

    private void validateRegistration(WikiPublicationEvent event, OperationLog operation) {
        if (!operation.getWorkspaceId().equals(event.workspaceId())
                || !operation.getUserId().equals(event.userId())
                || !operation.getWorkspaceId().equals(event.result().workspaceId())
                || !operation.getUserId().equals(event.result().userId())) {
            throw invalid("서버 등록 scope와 publication scope가 일치하지 않습니다.");
        }
        OperationType expected = switch (event.kind()) {
            case INGEST -> OperationType.ingest;
            case MANUAL_EDIT -> OperationType.wiki_edit;
            case LINT -> OperationType.lint;
            case RESTORE, RECOVERY -> OperationType.restore;
        };
        if (operation.getOperationType() != expected
                || !expected.name().equals(event.result().operationType())) {
            throw invalid("서버 등록 작업 유형과 publication kind가 일치하지 않습니다.");
        }
    }

    private void validateManualResult(WikiPublicationEvent event, OperationLog operation) {
        OperationResultRequest result = event.result();
        if (!operation.getStatus().isTerminal() || event.outcome() != WikiPublicationEvent.Outcome.PUBLISHED
                || !"succeeded".equals(result.status()) || result.changedPages().size() != 1
                || !result.deletedPagesOrEmpty().isEmpty() || !result.failedPagesOrEmpty().isEmpty()
                || !result.failedActionsOrEmpty().isEmpty() || result.ledgerContributionsOrEmpty().size() != 1
                || !result.linkChangesOrEmpty().removedLinks().isEmpty()
                || !result.linkChangesOrEmpty().restoredLinks().isEmpty()) {
            throw invalid("manual publication result가 저장된 수동 편집 계약과 다릅니다.");
        }
        if (!wikiState.matchesManualResult(operation, result)) {
            throw invalid("manual publication key/hash/ledger가 저장된 수동 편집과 다릅니다.");
        }
    }

    private void insertReceipt(WikiPublicationEvent event, String eventHash, String resultHash,
                               WikiPublicationInboxRepository.Disposition disposition,
                               String rejectionReason) {
        if (!inbox.insert(event, eventHash, resultHash, disposition, rejectionReason)) {
            throw conflict(event);
        }
        claimEventId(event);
    }

    private void claimEventId(WikiPublicationEvent event) {
        if (!inbox.claimEventId(event.eventId(), event.operationId())) {
            throw conflict(event);
        }
    }

    private WikiPublicationConflictException conflict(WikiPublicationEvent event) {
        return new WikiPublicationConflictException(
                "Wiki publication identity가 다른 결과에 재사용됐습니다: operationId=" + event.operationId());
    }

    private String hash(Object value) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception e) {
            throw new IllegalStateException("Wiki publication hash를 계산하지 못했습니다.", e);
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw invalid(field + "은 필수입니다.");
    }

    private static InvalidCallbackPayloadException invalid(String message) {
        return new InvalidCallbackPayloadException(message);
    }

    private record DomainEvent(String publicationId, String operationId, String workspaceId,
                               String userId, WikiPublicationEvent.Kind kind,
                               WikiPublicationEvent.Outcome outcome, OperationResultRequest result,
                               String publishedReferenceHash, java.time.Instant committedAt) {}

    public record ApplyResult(boolean applied, boolean duplicate, boolean wikiChanged,
                              WikiPublicationInboxRepository.Disposition disposition,
                              String rejectionReason) {}
}
