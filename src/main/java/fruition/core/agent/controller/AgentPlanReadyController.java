package fruition.core.agent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.agent.dto.AgentPlanReadyRequest;
import fruition.core.agent.service.AgentTurnService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ai-svc agent worker가 계획을 승인 대기로 저장한 뒤 부른다(X-Agent-Service-Token). 사용자의 자동 승인 설정과
 * 대상 권한으로 승인 확인을 건너뛸 수 있으면 승인하고, 아니면 계획을 그대로 둔다. 응답은 그 시점의 AgentRun이다.
 */
@RestController
@RequestMapping("/internal/agent/runs")
public class AgentPlanReadyController {

    private final AgentTurnService agentTurnService;

    public AgentPlanReadyController(AgentTurnService agentTurnService) {
        this.agentTurnService = agentTurnService;
    }

    @PostMapping("/{run_id}/plan-ready")
    public ResponseEntity<JsonNode> planReady(
            @PathVariable("run_id") String runId,
            @Valid @RequestBody AgentPlanReadyRequest request) {
        return ResponseEntity.ok(agentTurnService.autoApprove(request.workspaceId(), request.userId(), runId));
    }
}
