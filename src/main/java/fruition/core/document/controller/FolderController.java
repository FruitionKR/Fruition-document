package fruition.core.document.controller;

import fruition.core.document.dto.DocumentLifecycleRequest;
import fruition.core.document.dto.FolderChildrenResponse;
import fruition.core.document.dto.FolderCreateRequest;
import fruition.core.document.dto.FolderLifecycleResponse;
import fruition.core.document.dto.FolderPositionRequest;
import fruition.core.document.dto.FolderRenameRequest;
import fruition.core.document.dto.FolderResponse;
import fruition.core.document.service.FolderService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/folders")
@Tag(name = "Folders", description = "워크스페이스 문서 폴더의 생성, 이동, 삭제 및 복구 API")
public class FolderController {

    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    @Operation(summary = "폴더 생성", description = "워크스페이스의 최상위 또는 지정한 상위 폴더 아래에 새 폴더를 생성합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "생성 성공 또는 멱등 재요청",
            content = @Content(schema = @Schema(implementation = FolderResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 이름 또는 위치, 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스 또는 상위 폴더를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<FolderResponse> create(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "요청 멱등 키", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody FolderCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(folderService.create(workspaceId, userId, idempotencyKey, request));
    }

    @Operation(summary = "폴더 이름 변경", description = "폴더 이름을 변경하고 base version으로 동시 변경을 검증합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "변경 성공 또는 멱등 재요청",
            content = @Content(schema = @Schema(implementation = FolderResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 이름 또는 version, 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "폴더 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "version 충돌, IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{folder_id}")
    public ResponseEntity<FolderResponse> rename(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "이름을 변경할 폴더 ID", required = true)
            @PathVariable("folder_id") UUID folderId,
            @Parameter(description = "요청 멱등 키", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody FolderRenameRequest request) {
        return ResponseEntity.ok(folderService.rename(workspaceId, userId, folderId, idempotencyKey, request));
    }

    @Operation(summary = "폴더 위치 이동", description = "폴더를 대상 상위 폴더와 정렬 위치로 이동합니다. 자기 자신이나 하위 폴더로는 이동할 수 없습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "이동 성공 또는 멱등 재요청",
            content = @Content(schema = @Schema(implementation = FolderResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 요청 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "폴더, 대상 폴더 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "순환 이동, version 충돌, IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{folder_id}/position")
    public ResponseEntity<FolderResponse> move(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "이동할 폴더 ID", required = true)
            @PathVariable("folder_id") UUID folderId,
            @Parameter(description = "요청 멱등 키", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody FolderPositionRequest request) {
        return ResponseEntity.ok(folderService.move(workspaceId, userId, folderId, idempotencyKey, request));
    }

    @Operation(summary = "폴더 하위 항목 조회", description = "폴더 바로 아래의 하위 폴더와 문서를 정렬 순서로 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = FolderChildrenResponse.class))),
        @ApiResponse(responseCode = "404", description = "폴더 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{folder_id}/children")
    public ResponseEntity<FolderChildrenResponse> children(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "조회할 폴더 ID", required = true)
            @PathVariable("folder_id") UUID folderId) {
        return ResponseEntity.ok(folderService.children(workspaceId, userId, folderId));
    }

    @Operation(summary = "폴더 삭제", description = "폴더와 하위 항목을 휴지통 상태로 전환하며 base version으로 동시 변경을 검증합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "삭제 성공 또는 멱등 재요청",
            content = @Content(schema = @Schema(implementation = FolderLifecycleResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 version 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "내용이 있는 폴더를 삭제할 권한이 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "폴더 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "version 충돌, IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{folder_id}")
    public ResponseEntity<FolderLifecycleResponse> delete(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "삭제할 폴더 ID", required = true)
            @PathVariable("folder_id") UUID folderId,
            @Parameter(description = "요청 멱등 키", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DocumentLifecycleRequest request) {
        return ResponseEntity.ok(
                folderService.delete(workspaceId, userId, folderId, idempotencyKey, request.baseVersion()));
    }

    @Operation(summary = "폴더 복구", description = "삭제된 폴더와 하위 항목을 복구하고 유효한 탐색 위치에 배치합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "복구 성공 또는 멱등 재요청",
            content = @Content(schema = @Schema(implementation = FolderLifecycleResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 version 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "삭제된 폴더 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "version 충돌, IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{folder_id}/restore")
    public ResponseEntity<FolderLifecycleResponse> restore(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "복구할 폴더 ID", required = true)
            @PathVariable("folder_id") UUID folderId,
            @Parameter(description = "요청 멱등 키", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DocumentLifecycleRequest request) {
        return ResponseEntity.ok(
                folderService.restore(workspaceId, userId, folderId, idempotencyKey, request.baseVersion()));
    }
}
