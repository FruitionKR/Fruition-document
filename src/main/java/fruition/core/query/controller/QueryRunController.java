package fruition.core.query.controller;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.query.domain.QueryRun;
import fruition.core.query.dto.QueryRunStatusResponse;
import fruition.core.query.exception.QueryRunNotFoundException;
import fruition.core.query.service.QueryEventBroker;
import fruition.core.query.service.QueryRunStore;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


@RestController
@RequestMapping("/api/query/runs")
@Tag(name = "Query Runs", description = "비동기 질의 실행 상태와 실시간 진행 이벤트 API")
public class QueryRunController {

    private static final Logger log = LoggerFactory.getLogger(QueryRunController.class);

    private final QueryRunStore queryRunStore;
    private final QueryEventBroker queryEventBroker;
    private final WorkspaceAccessGuard workspaceAccessGuard;

    public QueryRunController(QueryRunStore queryRunStore,
                               QueryEventBroker queryEventBroker,
                               WorkspaceAccessGuard workspaceAccessGuard) {
        this.queryRunStore = queryRunStore;
        this.queryEventBroker = queryEventBroker;
        this.workspaceAccessGuard = workspaceAccessGuard;
    }

    @Operation(summary = "질의 진행 이벤트 구독", description = "비동기 질의의 진행 상황과 최종 결과를 Server-Sent Events로 전달합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "SSE 구독 시작",
            content = @Content(mediaType = "text/event-stream", schema = @Schema(type = "string"))),
        @ApiResponse(responseCode = "404", description = "질의 실행 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{requestId}/events")
    public SseEmitter subscribe(
                                @Parameter(description = "비동기 질의 요청 ID", required = true)
                                @PathVariable String requestId,
                                @AuthenticationPrincipal String userId) {
        requireOwnedRun(requestId, userId);
        log.info("[질의 SSE 구독] requestId={}", requestId);
        return queryEventBroker.subscribe(requestId);
    }

    @Operation(summary = "질의 실행 상태 조회", description = "비동기 질의의 현재 상태와 완료 결과 또는 오류 정보를 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "상태 조회 성공",
            content = @Content(schema = @Schema(implementation = QueryRunStatusResponse.class))),
        @ApiResponse(responseCode = "404", description = "질의 실행 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{requestId}")
    public ResponseEntity<QueryRunStatusResponse> getRun(
                                                         @Parameter(description = "비동기 질의 요청 ID", required = true)
                                                         @PathVariable String requestId,
                                                         @AuthenticationPrincipal String userId) {
        QueryRun run = requireOwnedRun(requestId, userId);
        log.info("[질의 run 상태 조회] requestId={} status={}", requestId, run.status());
        return ResponseEntity.ok(QueryRunStatusResponse.from(run));
    }

    private QueryRun requireRun(String requestId) {
        return queryRunStore.find(requestId)
                .orElseThrow(() -> new QueryRunNotFoundException(requestId));
    }

    /** run이 속한 워크스페이스의 멤버만 조회·구독할 수 있다. */
    private QueryRun requireOwnedRun(String requestId, String userId) {
        QueryRun run = requireRun(requestId);
        workspaceAccessGuard.requireMember(run.workspaceId(), userId);
        return run;
    }

}
