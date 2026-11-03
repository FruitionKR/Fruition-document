package fruition.core.agent.controller;

import fruition.core.agent.dto.AgentAutoApprovalSettings;
import fruition.core.agent.service.AgentAutoApprovalService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/agent/auto-approval-settings")
@Tag(name = "Agent", description = "워크스페이스 문맥에서 Agent 실행을 관리하는 API")
public class AgentAutoApprovalController {

    private final AgentAutoApprovalService autoApprovalService;

    public AgentAutoApprovalController(AgentAutoApprovalService autoApprovalService) {
        this.autoApprovalService = autoApprovalService;
    }

    @Operation(summary = "Agent 자동 승인 설정 조회",
            description = "요청한 사용자의 AI 에이전트 계획 자동 승인 설정을 조회합니다. 사용자 단위 설정이며 모든 "
                    + "워크스페이스에 적용됩니다(경로의 워크스페이스는 멤버 확인용). 저장한 적 없으면 모두 false입니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = AgentAutoApprovalSettings.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<AgentAutoApprovalSettings> getAutoApprovalSettings(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(autoApprovalService.get(workspaceId, userId));
    }

    @Operation(summary = "Agent 자동 승인 설정 변경",
            description = "분류별로 계획 자동 승인을 켜고 끕니다. 켜진 분류의 작업만으로 이뤄지고 AI 검토 경고가 없으며 "
                    + "요청자가 모든 대상을 편집할 수 있는 계획은 승인 확인 없이 실행되고, 승인 기록에 "
                    + "approved_by=auto:user-setting이 남습니다. 대상 권한 검사는 건너뛰지 않습니다. "
                    + "분류에 없는 작업(삭제 등)은 항상 직접 승인해야 합니다. 바뀐 설정은 다음 승인 판단부터 적용됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "변경 후 설정",
            content = @Content(schema = @Schema(implementation = AgentAutoApprovalSettings.class))),
        @ApiResponse(responseCode = "400", description = "필드 누락 또는 정의되지 않은 필드",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping
    public ResponseEntity<AgentAutoApprovalSettings> updateAutoApprovalSettings(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody AgentAutoApprovalSettings request) {
        return ResponseEntity.ok(autoApprovalService.update(workspaceId, userId, request));
    }
}
