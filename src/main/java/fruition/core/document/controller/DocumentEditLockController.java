package fruition.core.document.controller;

import fruition.core.document.dto.EditLockResponse;
import fruition.core.document.service.DocumentEditLockService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/documents/{document_id}/edit-lock")
@Tag(name = "Document Edit Lock", description = "문서 편집 잠금(활성 편집 추적) API")
public class DocumentEditLockController {

    private final DocumentEditLockService editLockService;

    public DocumentEditLockController(DocumentEditLockService editLockService) {
        this.editLockService = editLockService;
    }

    @Operation(summary = "편집 잠금 획득",
        description = "편집기 진입 시 호출한다. 비었거나 만료됐거나 본인 보유면 잠금을 부여(200)한다. 다른 사용자가 편집 중이면 423과 보유자 정보를 반환한다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "잠금 획득/갱신",
            content = @Content(schema = @Schema(implementation = EditLockResponse.class))),
        @ApiResponse(responseCode = "423", description = "다른 사용자가 편집 중",
            content = @Content(schema = @Schema(implementation = EditLockResponse.class))),
        @ApiResponse(responseCode = "403", description = "문서 소유자가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<EditLockResponse> acquire(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId) {
        EditLockResponse lock = editLockService.acquire(workspaceId, userId, documentId);
        HttpStatus status = userId.equals(lock.holderUserId()) ? HttpStatus.OK : HttpStatus.LOCKED;
        return ResponseEntity.status(status).body(lock);
    }

    @Operation(summary = "편집 잠금 heartbeat",
        description = "편집 중 주기적으로 호출해 잠금을 연장한다. 보유자가 아니거나 만료됐으면 409.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "잠금 연장",
            content = @Content(schema = @Schema(implementation = EditLockResponse.class))),
        @ApiResponse(responseCode = "409", description = "잠금 상실(만료/타인 보유)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/heartbeat")
    public ResponseEntity<EditLockResponse> heartbeat(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId) {
        return ResponseEntity.ok(editLockService.heartbeat(workspaceId, userId, documentId));
    }

    @Operation(summary = "편집 잠금 해제", description = "편집기 종료 시 호출한다. 보유자 본인의 잠금만 해제하며 멱등이다.")
    @ApiResponse(responseCode = "204", description = "잠금 해제 성공")
    @DeleteMapping
    public ResponseEntity<Void> release(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId) {
        editLockService.release(workspaceId, userId, documentId);
        return ResponseEntity.noContent().build();
    }
}
