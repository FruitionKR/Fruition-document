package fruition.core.wiki.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.util.ErrorResponse;
import fruition.core.wiki.service.WikiService;
import fruition.core.wiki.dto.WikiGraphResponse;
import fruition.core.wiki.dto.WikiPageContentSaveRequest;
import fruition.core.wiki.dto.WikiPageContentSaveResponse;
import fruition.core.wiki.service.WikiPageEditService;
import fruition.core.wiki.dto.WikiPageDetailResponse;
import fruition.core.wiki.dto.WikiPageDiffResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/wiki")
@Tag(name = "Wiki", description = "Wiki 그래프·페이지 조회와 페이지 본문 수정 API")
public class WikiController {

    private final WikiService wikiService;
    private final WikiPageEditService wikiPageEditService;
    private final ObjectMapper objectMapper;

    public WikiController(WikiService wikiService, WikiPageEditService wikiPageEditService,
                          ObjectMapper objectMapper) {
        this.wikiService = wikiService;
        this.wikiPageEditService = wikiPageEditService;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "Wiki 그래프 조회", description = "모든 Wiki 노드(pages)와 엣지(links)를 반환합니다. 중앙 그래프 렌더링과 답변 후 하이라이트에 사용됩니다. "
            + "응답의 ETag를 If-None-Match로 보내면, 그래프가 같을 때 본문 없이 304를 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "그래프 조회 성공",
            content = @Content(schema = @Schema(implementation = WikiGraphResponse.class))),
        @ApiResponse(responseCode = "304", description = "If-None-Match의 ETag와 그래프가 같음"),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/graph")
    public ResponseEntity<WikiGraphResponse> getGraph(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) throws JsonProcessingException {
        WikiGraphResponse graph = wikiService.findGraph(workspaceId, userId);
        // 그래프 원본이 pipeline에 있어 호출은 줄이지 못하고 전송만 줄인다. ETag가 맞으면
        // HttpEntityMethodProcessor가 본문 대신 304를 보낸다. pipeline이 Wiki 버전을 내려주면
        // 그 값으로 호출 전에 판단할 수 있다.
        String etag = "\"" + DigestUtils.md5DigestAsHex(objectMapper.writeValueAsBytes(graph)) + "\"";
        return ResponseEntity.ok().eTag(etag).body(graph);
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

    @Operation(operationId = "saveWikiPageContent", summary = "Wiki 페이지 본문 수정",
        description = "사람이 고친 본문을 저장합니다. base_revision이 현재 revision과 같을 때만 반영하고 revision을 하나 올립니다. "
            + "같은 revision_write_id 재시도는 처음 결과를 돌려줍니다. 본문이 같으면 revision을 올리지 않습니다. "
            + "OWNER 또는 wiki.ingest 권한이 있는 멤버만 고칠 수 있습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "저장 성공 또는 같은 본문 no-op·같은 write ID 재시도",
            content = @Content(schema = @Schema(implementation = WikiPageContentSaveResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 Markdown, base_revision 또는 revision_write_id",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "wiki.ingest 권한 없음(WORKSPACE_PERMISSION_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스 멤버가 아니거나 페이지를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "revision 충돌(WIKI_PAGE_VERSION_CONFLICT, error.current_revision에 현재 revision) 또는 revision_write_id 재사용 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "413", description = "Markdown 5MB 초과",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "AI 서비스 저장 실패(WIKI_PAGE_PIPELINE_UNAVAILABLE). 같은 revision_write_id로 다시 시도",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/pages/{wiki_page_id}/content")
    public WikiPageContentSaveResponse saveContent(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "Wiki 페이지 ID", example = "wp_abc123")
            @PathVariable("wiki_page_id") String wikiPageId,
            @RequestBody WikiPageContentSaveRequest request) {
        return wikiPageEditService.save(workspaceId, userId, wikiPageId, request);
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
