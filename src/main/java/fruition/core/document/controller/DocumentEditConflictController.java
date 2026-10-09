package fruition.core.document.controller;

import fruition.core.document.dto.DocumentEditConflictListResponse;
import fruition.core.document.dto.DocumentEditConflictRequest;
import fruition.core.document.dto.DocumentEditConflictResolveRequest;
import fruition.core.document.dto.DocumentEditConflictResponse;
import fruition.core.document.service.DocumentEditConflictService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Document Edit Conflicts", description = "같은 revision에서 갈라진 편집을 OWNER가 골라 해결")
@RestController
@RequestMapping("/api/workspaces/{workspace_id}")
public class DocumentEditConflictController {

    private final DocumentEditConflictService conflictService;

    public DocumentEditConflictController(DocumentEditConflictService conflictService) {
        this.conflictService = conflictService;
    }

    @Operation(summary = "편집 충돌 등록",
            description = "본문 저장이 409 DOCUMENT_VERSION_CONFLICT로 막혔을 때 내 본문을 충돌로 올려 보존합니다."
                    + " 그 문서를 편집할 수 있어야 합니다. 같은 client_conflict_id로 다시 보내면 기존 충돌을 돌려줍니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "등록 또는 멱등 재요청",
            content = @Content(schema = @Schema(implementation = DocumentEditConflictResponse.class))),
        @ApiResponse(responseCode = "400", description = "markdown·base_revision·client_conflict_id가 올바르지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "편집 권한 없음(문서·폴더 권한이 view이고 문서 소유자·OWNER가 아님)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "413", description = "Markdown 5MB 초과",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/documents/{document_id}/conflicts")
    public ResponseEntity<DocumentEditConflictResponse> registerConflict(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId,
            @Valid @RequestBody DocumentEditConflictRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(conflictService.register(workspaceId, userId, documentId, request));
    }

    @Operation(summary = "미해결 편집 충돌 목록",
            description = "충돌 본과 서버 현재 본문·revision·마지막 수정자를 함께 반환합니다. OWNER만 호출할 수 있습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "403", description = "OWNER가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/conflicts")
    public ResponseEntity<DocumentEditConflictListResponse> listConflicts(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(conflictService.listOpen(workspaceId, userId));
    }

    @Operation(summary = "편집 충돌 해결",
            description = "server는 본문을 그대로 두고, conflict·merged는 그 본문으로 새 revision을 저장합니다."
                    + " 고르지 않은 본문은 버전 이력과 충돌 기록에 남습니다. OWNER만 호출할 수 있고, 먼저 해결한 요청만 반영됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "해결 완료",
            content = @Content(schema = @Schema(implementation = DocumentEditConflictResponse.class))),
        @ApiResponse(responseCode = "400", description = "choice가 올바르지 않거나 필요한 markdown·base_revision이 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "OWNER가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "충돌·문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "이미 해결됨(CONFLICT_ALREADY_RESOLVED) 또는 서버 revision이 바뀜(DOCUMENT_VERSION_CONFLICT)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "423", description = "다른 사용자가 편집 잠금을 보유 중",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/conflicts/{conflict_id}/resolve")
    public ResponseEntity<DocumentEditConflictResponse> resolveConflict(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("conflict_id") UUID conflictId,
            @Valid @RequestBody DocumentEditConflictResolveRequest request) {
        return ResponseEntity.ok(conflictService.resolve(workspaceId, userId, conflictId, request));
    }
}
