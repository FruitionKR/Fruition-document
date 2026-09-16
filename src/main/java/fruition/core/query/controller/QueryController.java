package fruition.core.query.controller;

import fruition.core.chat.service.ChatSessionService;
import fruition.core.query.domain.QueryRun;
import fruition.core.query.dto.QueryRequest;
import fruition.core.query.dto.QueryResponse;
import fruition.core.query.dto.QueryRunCreateResponse;
import fruition.core.query.service.QueryRunService;
import fruition.shared.ai.AiModelCatalog;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/chat/sessions/{session_id}")
@Tag(name = "Query", description = "Wiki 기반 자연어 질의 API")
public class QueryController {

    private static final Logger log = LoggerFactory.getLogger(QueryController.class);

    private final QueryRunService queryRunService;
    private final ChatSessionService chatSessionService;
    private final AiModelCatalog aiModelCatalog;

    public QueryController(QueryRunService queryRunService,
                           ChatSessionService chatSessionService, AiModelCatalog aiModelCatalog) {
        this.queryRunService = queryRunService;
        this.chatSessionService = chatSessionService;
        this.aiModelCatalog = aiModelCatalog;
    }

    @Operation(
        summary = "Wiki 기반 자연어 질의 (동기)",
        description = "질문을 받아 Wiki 페이지를 검색하고 LLM으로 답변을 생성합니다. " +
                      "응답에는 답변, 관련 Wiki 페이지, 원본 출처, 그래프 하이라이트 경로가 포함됩니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "질의 성공",
            content = @Content(schema = @Schema(implementation = QueryResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 요청 (질문이 비어 있는 경우)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "세션 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "파이프라인 요청 거부",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "파이프라인 타임아웃 또는 사용 불가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "채팅 세션 ID", example = "session_abc12345")
            @PathVariable("session_id") String sessionId,
            @org.springframework.web.bind.annotation.RequestParam(name = "run_id", required = false) String runId,
            @Valid @RequestBody QueryRequest request) {
        log.info("[질의 요청 수신] mode=sync workspaceId={} userId={} sessionId={} questionLength={}",
                workspaceId, userId, sessionId, request.question().length());
        chatSessionService.verifyOwnedSession(workspaceId, userId, sessionId);
        AiModelCatalog.AiModel selected = aiModelCatalog.resolve(request.provider(), request.model());
        QueryRun run = queryRunService.start(workspaceId, userId, sessionId, request.question(),
                selected.provider(), selected.model(), request.allowWebSearch(), runId);
        return ResponseEntity.ok().header("X-AI-Run-Id", run.requestId())
                .body(queryRunService.awaitResult(run, userId));
    }

    @Operation(
        summary = "Wiki 기반 자연어 질의 (비동기)",
        description = "질의를 비동기 run으로 시작합니다. 진행 상황은 GET /api/query/runs/{request_id}/events(SSE)로 구독합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "run 시작됨",
            content = @Content(schema = @Schema(implementation = QueryRunCreateResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 요청 (질문이 비어 있는 경우)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "세션 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/query/runs")
    public ResponseEntity<QueryRunCreateResponse> createRun(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "채팅 세션 ID", example = "session_abc12345")
            @PathVariable("session_id") String sessionId,
            @Valid @RequestBody QueryRequest request) {
        log.info("[질의 요청 수신] mode=async workspaceId={} userId={} sessionId={} questionLength={}",
                workspaceId, userId, sessionId, request.question().length());
        chatSessionService.verifyOwnedSession(workspaceId, userId, sessionId);
        AiModelCatalog.AiModel selected = aiModelCatalog.resolve(request.provider(), request.model());
        QueryRun run = queryRunService.start(workspaceId, userId, sessionId, request.question(),
                selected.provider(), selected.model(), request.allowWebSearch());
        log.info("[질의 run 응답] requestId={} status={}", run.requestId(), run.status());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(QueryRunCreateResponse.from(run));
    }
}
