package fruition.core.document.controller;

import fruition.core.document.dto.BreadcrumbResponse;
import fruition.core.document.dto.FolderChildrenResponse;
import fruition.core.document.dto.HierarchySearchResponse;
import fruition.core.document.service.FolderService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 최상위 탐색: 워크스페이스 폴더 트리의 최상위 폴더·문서를 혼합 순서로 조회한다(TASK-H005). */
@RestController
@RequestMapping("/api/workspaces/{workspace_id}/navigation")
@Tag(name = "Navigation", description = "워크스페이스 문서와 폴더 계층 탐색 API")
public class NavigationController {

    private final FolderService folderService;

    public NavigationController(FolderService folderService) {
        this.folderService = folderService;
    }

    @Operation(summary = "최상위 항목 조회", description = "워크스페이스 최상위의 폴더와 문서를 정렬 순서로 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = FolderChildrenResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<FolderChildrenResponse> root(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(folderService.children(workspaceId, userId, null));
    }

    @Operation(summary = "이동 경로 조회", description = "폴더 또는 문서까지 이어지는 상위 폴더 경로를 최상위부터 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "경로 조회 성공",
            content = @Content(schema = @Schema(implementation = BreadcrumbResponse.class))),
        @ApiResponse(responseCode = "400", description = "folder_id와 document_id가 모두 없거나 함께 전달됨",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "대상 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/breadcrumb")
    public ResponseEntity<BreadcrumbResponse> breadcrumb(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "경로를 조회할 폴더 ID. document_id와 함께 사용할 수 없습니다.")
            @RequestParam(value = "folder_id", required = false) UUID folderId,
            @Parameter(description = "경로를 조회할 문서 ID. folder_id와 함께 사용할 수 없습니다.")
            @RequestParam(value = "document_id", required = false) String documentId) {
        return ResponseEntity.ok(folderService.breadcrumb(workspaceId, userId, folderId, documentId));
    }

    @Operation(summary = "폴더·문서 검색", description = "워크스페이스의 폴더 이름과 문서 파일명을 검색해 계층 경로를 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "검색 성공",
            content = @Content(schema = @Schema(implementation = HierarchySearchResponse.class))),
        @ApiResponse(responseCode = "400", description = "검색어가 비어 있거나 잘못됨",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/search")
    public ResponseEntity<HierarchySearchResponse> search(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "폴더 이름 또는 문서 파일명 검색어", required = true)
            @RequestParam("query") String query) {
        return ResponseEntity.ok(folderService.search(workspaceId, userId, query));
    }
}
