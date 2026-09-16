package fruition.core.document.controller;

import fruition.core.document.dto.DocumentPositionRequest;
import fruition.core.document.dto.DocumentPositionResponse;
import fruition.core.document.service.DocumentPlacementService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/documents")
@Tag(name = "Documents", description = "문서 위치와 폴더 배치 관리 API")
public class DocumentPositionController {

    private final DocumentPlacementService documentPlacementService;

    public DocumentPositionController(DocumentPlacementService documentPlacementService) {
        this.documentPlacementService = documentPlacementService;
    }

    @Operation(summary = "문서 위치 이동", description = "문서를 대상 폴더와 정렬 위치로 이동합니다. base version과 Idempotency-Key로 동시 변경을 검증합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "이동 성공 또는 멱등 재요청",
            content = @Content(schema = @Schema(implementation = DocumentPositionResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 위치 또는 version, 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서, 대상 폴더 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "version 충돌, IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{document_id}/position")
    public ResponseEntity<DocumentPositionResponse> move(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "이동할 문서 ID", required = true)
            @PathVariable("document_id") String documentId,
            @Parameter(description = "요청 멱등 키", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DocumentPositionRequest request) {
        return ResponseEntity.ok(
                documentPlacementService.move(workspaceId, userId, documentId, idempotencyKey, request));
    }
}
