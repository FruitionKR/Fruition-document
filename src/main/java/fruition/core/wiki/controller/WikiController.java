package fruition.core.wiki.controller;

import fruition.shared.util.ErrorResponse;
import fruition.core.wiki.service.WikiService;
import fruition.core.wiki.dto.WikiGraphResponse;
import fruition.core.wiki.dto.WikiPageDetailResponse;
import fruition.core.wiki.dto.WikiPageDiffResponse;
import fruition.core.wiki.dto.WikiPageRenameRequest;
import fruition.core.wiki.dto.WikiPageRenameResponse;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/wiki")
@Tag(name = "Wiki", description = "Wiki 그래프 및 페이지 조회 API")
public class WikiController {

    private final WikiService wikiService;

    public WikiController(WikiService wikiService) {
        this.wikiService = wikiService;
    }

    @Operation(summary = "Wiki 그래프 조회", description = "모든 Wiki 노드(pages)와 엣지(links)를 반환합니다. 중앙 그래프 렌더링과 답변 후 하이라이트에 사용됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "그래프 조회 성공",
            content = @Content(schema = @Schema(implementation = WikiGraphResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/graph")
    public ResponseEntity<WikiGraphResponse> getGraph(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(wikiService.findGraph(workspaceId, userId));
    }

    @Operation(summary = "Wiki 페이지 상세 조회", description = "특정 Wiki 페이지의 상세 정보를 반환합니다. source_documents와 related_pages를 포함합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "페이지 조회 성공",
            content = @Content(schema = @Schema(implementation = WikiPageDetailResponse.class))),
        @ApiResponse(responseCode = "404", description = "페이지를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/pages/{wiki_page_id}")
    public ResponseEntity<WikiPageDetailResponse> getPage(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "Wiki 페이지 ID", example = "wp_abc123")
            @PathVariable("wiki_page_id") String wikiPageId) {
        return ResponseEntity.ok(wikiService.findById(workspaceId, userId, wikiPageId));
    }

    @Operation(summary = "Wiki 페이지 이름 변경", description = "Wiki 페이지 제목을 변경합니다. update_slug=true이면 slug도 재생성하며 중복 여부를 검증합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "이름 변경 성공",
            content = @Content(schema = @Schema(implementation = WikiPageRenameResponse.class))),
        @ApiResponse(responseCode = "400", description = "유효하지 않은 제목",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "페이지를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "slug 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/pages/{wiki_page_id}/rename")
    public ResponseEntity<WikiPageRenameResponse> rename(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "Wiki 페이지 ID", example = "wp_abc123")
            @PathVariable("wiki_page_id") String wikiPageId,
            @RequestBody WikiPageRenameRequest request) {
        return ResponseEntity.ok(wikiService.rename(workspaceId, userId, wikiPageId, request));
    }

    @Operation(summary = "Wiki 페이지 변경분 조회",
        description = "두 revision 사이의 diff를 반환합니다. 저장된 본문을 읽어 요청 시점에 계산하며, 사용자가 펼칠 때만 호출됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = WikiPageDiffResponse.class))),
        @ApiResponse(responseCode = "404", description = "페이지 또는 버전을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "두 본문의 차이가 너무 커서 비교할 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/pages/{wiki_page_id}/diff")
    public ResponseEntity<WikiPageDiffResponse> diff(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "Wiki 페이지 ID", example = "wp_abc123")
            @PathVariable("wiki_page_id") String wikiPageId,
            @Parameter(description = "비교 기준 revision", example = "1")
            @RequestParam("from") long fromRevision,
            @Parameter(description = "비교 대상 revision", example = "2")
            @RequestParam("to") long toRevision) {
        return ResponseEntity.ok(
                wikiService.diff(workspaceId, userId, wikiPageId, fromRevision, toRevision));
    }
}
