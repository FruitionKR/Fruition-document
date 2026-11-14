package fruition.core.wiki.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import fruition.shared.util.ErrorResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.Objects;

@RestController
public class InternalWikiContributionController {

    private final WikiPageContributionRepository repository;
    private final String internalToken;

    public InternalWikiContributionController(
            WikiPageContributionRepository repository,
            @Value("${app.internal.callback-token}") String internalToken) {
        this.repository = repository;
        this.internalToken = internalToken;
    }

    @PostMapping("/internal/wiki/contributions")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResponseEntity<?> find(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody ContributionRequest request) {
        if (token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.of("INVALID_INTERNAL_TOKEN", "내부 토큰이 올바르지 않습니다."));
        }
        if (request.workspaceId() == null || request.workspaceId().isBlank()
                || request.pageIds() == null || request.pageIds().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("INVALID_REQUEST", "workspace_id와 page_ids가 필요합니다."));
        }
        if (!repository.findPageIdsOutsideWorkspace(
                request.pageIds(), request.workspaceId()).isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ErrorResponse.of(
                            "WIKI_PAGE_SCOPE_MISMATCH",
                            "요청한 Wiki page가 workspace 범위와 일치하지 않습니다."));
        }
        var contributions = repository.findByPageIdsAndWorkspaceId(
                request.pageIds(), request.workspaceId());
        var documentIds = contributions.stream().map(row -> row.getSourceDocumentId())
                .filter(Objects::nonNull).distinct().toList();
        Set<String> deletedDocuments = documentIds.isEmpty() ? Set.of()
                : Set.copyOf(repository.findDeletedSourceDocumentIds(documentIds, request.workspaceId()));
        return ResponseEntity.ok(contributions.stream()
                .map(row -> new ContributionResponse(
                        row.getPageId(), row.getIngestOperationId(), row.getSourceDocumentId(),
                        row.getSequenceRevision(), row.isActive() && (row.getSourceDocumentId() == null
                                || !deletedDocuments.contains(row.getSourceDocumentId())), row.getObjectKey(),
                        // AI는 자기 lint 기록과 다른 작업에서 이은 기록을 구분한다.
                        Objects.equals(row.getIngestOperationId(), row.getLintOperationId())
                                ? null : row.getLintOperationId(),
                        row.getSourceDocumentId() != null && deletedDocuments.contains(row.getSourceDocumentId())))
                .toList());
    }

    record ContributionRequest(
            @JsonProperty("page_ids") List<String> pageIds,
            @JsonProperty("workspace_id") String workspaceId
    ) {}
    record ContributionResponse(
            @JsonProperty("page_id") String pageId,
            @JsonProperty("ingest_operation_id") String ingestOperationId,
            @JsonProperty("source_document_id") String sourceDocumentId,
            @JsonProperty("sequence_revision") long sequenceRevision,
            boolean active,
            @JsonProperty("object_key") String objectKey,
            @JsonProperty("lint_operation_id") String lintOperationId,
            @JsonProperty("source_document_deleted") boolean sourceDocumentDeleted
    ) {}
}
