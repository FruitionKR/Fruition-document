package fruition.core.aihistory.service;

import fruition.core.aihistory.repository.WikiPublicationInboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class WikiPublicationReceiptService {

    private final WikiPublicationInboxRepository inbox;

    public WikiPublicationReceiptService(WikiPublicationInboxRepository inbox) {
        this.inbox = inbox;
    }

    @Transactional(readOnly = true)
    public Optional<PublicationReceipt> find(
            String workspaceId, String userId, String publicationId,
            String operationId, String eventId) {
        return inbox.findReceipt(workspaceId, userId, publicationId, operationId, eventId)
                .map(receipt -> new PublicationReceipt(
                        receipt.publicationId(), receipt.operationId(), receipt.eventId(),
                        receipt.workspaceId(), receipt.userId(), receipt.kind(), receipt.outcome(),
                        receipt.publishedReferenceHash(), receipt.committedAt(),
                        receipt.disposition().name(), receipt.rejectionReason()));
    }

    public record PublicationReceipt(
            String publicationId, String operationId, String eventId,
            String workspaceId, String userId, String kind, String outcome,
            String publishedReferenceHash, Instant committedAt,
            String disposition, String rejectionReason) {}
}
