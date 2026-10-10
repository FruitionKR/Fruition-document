package fruition.core.document.controller;

import fruition.core.document.dto.DocumentPermissionRequest;
import fruition.core.document.dto.DocumentPermissionResponse;
import fruition.core.document.service.DocumentPermissionService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Document Permissions", description = "문서·폴더 공동 편집 권한 설정")
@RestController
@RequestMapping("/api/workspaces/{workspace_id}")
public class DocumentPermissionController {

    private final DocumentPermissionService permissionService;

    public DocumentPermissionController(DocumentPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Operation(summary = "문서 권한 설정",
            description = "멤버는 기본으로 모든 문서를 편집할 수 있습니다. view로 바꾸면 소유자와 OWNER 외에는 보기만 합니다."
                    + " null이면 설정을 지워 상위 폴더 설정이나 기본값을 따릅니다. 문서 소유자나 OWNER만 바꿀 수 있습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "설정 완료"),
        @ApiResponse(responseCode = "400", description = "access가 edit, view, null이 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "문서 소유자나 OWNER가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/documents/{document_id}/permission")
    public ResponseEntity<DocumentPermissionResponse> setDocument(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId,
            @Valid @RequestBody DocumentPermissionRequest request) {
        return ResponseEntity.ok(permissionService.setDocument(workspaceId, userId, documentId, request.access()));
    }

    @Operation(summary = "폴더 권한 설정",
            description = "폴더 안의 문서와 하위 폴더가 따르는 권한입니다. 문서·하위 폴더에 직접 건 설정이 우선합니다."
                    + " view 폴더는 OWNER 외에 이름 변경·이동할 수 없습니다. OWNER만 바꿀 수 있습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "설정 완료"),
        @ApiResponse(responseCode = "400", description = "access가 edit, view, null이 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "OWNER가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "폴더 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/folders/{folder_id}/permission")
    public ResponseEntity<DocumentPermissionResponse> setFolder(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("folder_id") UUID folderId,
            @Valid @RequestBody DocumentPermissionRequest request) {
        return ResponseEntity.ok(permissionService.setFolder(workspaceId, userId, folderId, request.access()));
    }
}
