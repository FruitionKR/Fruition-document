package fruition.core.document.controller;

import fruition.core.document.dto.DocumentTreeResponse;
import fruition.core.document.service.FolderService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/document-tree")
@Tag(name = "Navigation", description = "워크스페이스 문서·폴더 계층 탐색 API")
public class DocumentTreeController {

    private final FolderService folderService;

    public DocumentTreeController(FolderService folderService) {
        this.folderService = folderService;
    }

    @Operation(
            summary = "전체 문서 트리 조회",
            description = "모든 폴더를 펼친 상태의 활성 폴더·문서 계층을 한 번에 반환합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "전체 트리 조회 성공",
            content = @Content(schema = @Schema(implementation = DocumentTreeResponse.class))),
        @ApiResponse(responseCode = "404", description = "활성 워크스페이스 또는 멤버십을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<DocumentTreeResponse> tree(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(folderService.tree(workspaceId, userId));
    }
}
