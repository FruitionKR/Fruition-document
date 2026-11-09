package fruition.core.aihistory.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.aihistory.service.WikiPublicationReceiptService;
import fruition.shared.util.ErrorResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

@RestController
public class InternalWikiPublicationReceiptController {

    private final WikiPublicationReceiptService receipts;
    private final String internalToken;

    public InternalWikiPublicationReceiptController(
            WikiPublicationReceiptService receipts,
            @Value("${app.internal.callback-token}") String internalToken) {
        this.receipts = receipts;
        this.internalToken = internalToken;
    }

    @PostMapping("/internal/wiki/publications/receipt")
    public ResponseEntity<?> find(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody ReceiptRequest request) {
        if (!tokenMatches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.of("INVALID_INTERNAL_TOKEN", "내부 토큰이 올바르지 않습니다."));
        }
        if (request == null || !request.valid()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("INVALID_REQUEST", "Publication receipt identity가 필요합니다."));
        }
        return receipts.find(request.workspaceId(), request.userId(), request.publicationId(),
                        request.operationId(), request.eventId())
                .<ResponseEntity<?>>map(receipt -> ResponseEntity.ok(ReceiptResponse.from(receipt)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ErrorResponse.of("PUBLICATION_RECEIPT_NOT_FOUND", "Publication receipt를 찾을 수 없습니다.")));
    }

    private boolean tokenMatches(String token) {
        return token != null && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8));
    }

    public record ReceiptRequest(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("publication_id") String publicationId,
            @JsonProperty("operation_id") String operationId,
            @JsonProperty("event_id") String eventId
    ) {
        boolean valid() {
            return bounded(workspaceId) && bounded(userId) && bounded(publicationId)
                    && bounded(operationId) && bounded(eventId);
        }

        private static boolean bounded(String value) {
            return value != null && !value.isBlank() && value.length() <= 255;
        }
    }

    public record ReceiptResponse(
            @JsonProperty("publication_id") String publicationId,
            @JsonProperty("operation_id") String operationId,
            @JsonProperty("event_id") String eventId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            String kind,
            String outcome,
            @JsonProperty("published_reference_hash") String publishedReferenceHash,
            @JsonProperty("committed_at") Instant committedAt,
            String disposition,
            @JsonProperty("rejection_reason") String rejectionReason
    ) {
        static ReceiptResponse from(WikiPublicationReceiptService.PublicationReceipt receipt) {
            return new ReceiptResponse(
                    receipt.publicationId(), receipt.operationId(), receipt.eventId(),
                    receipt.workspaceId(), receipt.userId(), receipt.kind(), receipt.outcome(),
                    receipt.publishedReferenceHash(), receipt.committedAt(),
                    receipt.disposition(), receipt.rejectionReason());
        }
    }
}
