package fruition.core.agent.controller;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.agent.service.McpAgentApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Service-authenticated, body-free reconciliation lookup. It never replays execution. */
@RestController
@RequestMapping("/internal/agent/mcp")
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpAgentReceiptController {
    private final McpAgentApprovalService approvals;

    public McpAgentReceiptController(McpAgentApprovalService approvals) {
        this.approvals = approvals;
    }

    @Operation(operationId = "lookupMcpAgentReceipt",
            summary = "MCP Agent operation의 body-free 실행 영수증 조회")
    @PostMapping("/receipts/lookup")
    public ResponseEntity<McpAgentApprovalService.ReceiptStatus> lookup(@Valid @RequestBody LookupRequest request) {
        return ResponseEntity.ok(approvals.lookup(new McpAgentApprovalService.ReceiptLookup(
                request.approvalId(), request.operationId(), request.workspaceId(), request.userId(),
                request.grantId(), request.clientId())));
    }

    @Operation(operationId = "lookupMcpAgentApproval",
            summary = "MCP Agent 명시 승인 상태 조회")
    @PostMapping("/approvals/lookup")
    public ResponseEntity<McpAgentApprovalService.ApprovalStatus> lookupApproval(
            @Valid @RequestBody ApprovalLookupRequest request) {
        return ResponseEntity.ok(approvals.lookupApproval(new McpAgentApprovalService.ApprovalLookup(
                request.approvalId(), request.runId(), request.workspaceId(), request.userId(),
                request.planVersion(), request.operationHash(), request.operationIds(), request.grantId(),
                request.clientId(), request.policyGeneration())));
    }

    public record LookupRequest(
            @NotBlank @JsonProperty("approval_id") String approvalId,
            @NotBlank @JsonProperty("operation_id") String operationId,
            @NotBlank @JsonProperty("workspace_id") String workspaceId,
            @NotBlank @JsonProperty("user_id") String userId,
            @NotBlank @JsonProperty("grant_id") String grantId,
            @NotBlank @JsonProperty("client_id") String clientId) {
        @JsonAnySetter public void rejectUnknown(String name, Object ignored) {
            throw new IllegalArgumentException("Unknown property: " + name);
        }
    }


    public record ApprovalLookupRequest(
            @NotBlank @JsonProperty("approval_id") String approvalId,
            @NotBlank @JsonProperty("run_id") String runId,
            @NotBlank @JsonProperty("workspace_id") String workspaceId,
            @NotBlank @JsonProperty("user_id") String userId,
            @Min(1) @JsonProperty("plan_version") int planVersion,
            @NotBlank @Size(min = 64, max = 64) @JsonProperty("operation_hash") String operationHash,
            @NotNull @Size(min = 1, max = 20) @JsonProperty("operation_ids") List<@NotBlank String> operationIds,
            @NotBlank @JsonProperty("grant_id") String grantId,
            @NotBlank @JsonProperty("client_id") String clientId,
            @Min(1) @JsonProperty("policy_generation") long policyGeneration) {
        @JsonAnySetter public void rejectUnknown(String name, Object ignored) {
            throw new IllegalArgumentException("Unknown property: " + name);
        }
    }
}
