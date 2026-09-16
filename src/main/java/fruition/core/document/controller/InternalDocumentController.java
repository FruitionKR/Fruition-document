package fruition.core.document.controller;

import fruition.core.document.service.DocumentService;
import fruition.core.document.dto.InternalPipelineDocumentResponse;
import fruition.shared.util.ErrorResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * access(인증 서비스)가 호출하는 내부 API. 사용자 인증 대상이 아니며
 * X-Internal-Token으로 검증한다(기존 pipeline 콜백과 같은 방식).
 */
@RestController
public class InternalDocumentController {

    private final DocumentService documentService;
    private final String internalToken;

    public InternalDocumentController(DocumentService documentService,
                                      @Value("${app.internal.callback-token}") String internalToken) {
        this.documentService = documentService;
        this.internalToken = internalToken;
    }

    /** 새 워크스페이스의 초기 노트 생성. 실패는 DocumentService가 best-effort로 처리한다. */
    @ApiResponse(responseCode = "204", description = "초기 노트 생성 완료", content = @Content)
    @PostMapping("/internal/workspaces/{workspace_id}/initial-note")
    public ResponseEntity<?> createInitialNote(
            @PathVariable("workspace_id") String workspaceId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Validated @RequestBody InitialNoteRequest request) {
        if (!tokenMatches(token)) {
            return unauthorized();
        }
        documentService.createInitialNote(workspaceId, request.userId());
        return ResponseEntity.noContent().build();
    }

    /** AI pipeline이 core_db에 직접 접속하지 않고 원본 위치와 소유 범위를 조회한다. */
    @GetMapping("/internal/documents/{document_id}/pipeline-source")
    public ResponseEntity<?> findPipelineSource(
            @PathVariable("document_id") String documentId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!tokenMatches(token)) {
            return unauthorized();
        }
        return ResponseEntity.of(documentService.findPipelineSource(documentId));
    }

    /** 길이가 달라도 시간차가 새지 않도록 상수 시간 비교를 쓴다. */
    private boolean tokenMatches(String token) {
        return token != null && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<ErrorResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("INVALID_INTERNAL_TOKEN", "내부 토큰이 올바르지 않습니다."));
    }

    record InitialNoteRequest(@NotBlank @JsonProperty("user_id") String userId) {}
}
