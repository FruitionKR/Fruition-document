package fruition.core.mcp;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/mcp/wiki-drafts")
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpWikiDraftController {
    private final McpWikiApprovalService service;
    public McpWikiDraftController(McpWikiApprovalService service) { this.service = service; }

    @Operation(operationId = "getMcpWikiDraft",
            summary = "MCP wiki 전체 draft 승인 미리보기와 상태 조회")
    @GetMapping("/{draft_id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable("workspace_id") String workspaceId,
                                                       @AuthenticationPrincipal String userId,
                                                       @PathVariable("draft_id") String draftId) {
        requireUuid4(draftId);
        return ResponseEntity.ok(service.detail(workspaceId, userId, draftId));
    }

    @Operation(operationId = "approveMcpWikiDraft", summary = "현재 owner 권한으로 전체 draft 명시 승인")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "승인·publication·settlement 상태"),
            @ApiResponse(responseCode = "402", description = "최대 금액 전체 예약에 필요한 credit 부족"),
            @ApiResponse(responseCode = "409", description = "Draft/source/public 상태 또는 멱등 충돌")
    })
    @PostMapping("/{draft_id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable("workspace_id") String workspaceId,
                                                        @AuthenticationPrincipal String userId,
                                                        @PathVariable("draft_id") String draftId,
                                                        @Valid @RequestBody ApprovalBody request) {
        requireUuid4(draftId);
        if (!draftId.equals(request.draftId())) throw new IllegalArgumentException("draft_id mismatch");
        return ResponseEntity.ok(service.approve(workspaceId, userId, request.toCommand()));
    }

    @Operation(operationId = "rejectMcpWikiDraft", summary = "승인 전 전체 draft 거절")
    @PostMapping("/{draft_id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable("workspace_id") String workspaceId,
                                                       @AuthenticationPrincipal String userId,
                                                       @PathVariable("draft_id") String draftId,
                                                       @Valid @RequestBody EmptyBody ignored) {
        requireUuid4(draftId);
        return ResponseEntity.ok(service.reject(workspaceId, userId, draftId));
    }

    @Operation(operationId = "cancelMcpWikiDraft", summary = "전체 draft 취소 및 apply fence")
    @PostMapping("/{draft_id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable("workspace_id") String workspaceId,
                                                       @AuthenticationPrincipal String userId,
                                                       @PathVariable("draft_id") String draftId,
                                                       @Valid @RequestBody EmptyBody ignored) {
        requireUuid4(draftId);
        return ResponseEntity.ok(service.cancel(workspaceId, userId, draftId));
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ApprovalBody(
            @NotBlank @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") @JsonProperty("draft_id") String draftId,
            @Min(1) @JsonProperty("draft_version") long draftVersion,
            @NotBlank @Pattern(regexp = "^sha256:[0-9a-f]{64}$") @JsonProperty("draft_hash") String draftHash,
            @NotBlank @Pattern(regexp = "^sha256:[0-9a-f]{64}$") @JsonProperty("public_state_hash") String publicStateHash) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException(name); }
        McpWikiApprovalService.ApprovalRequest toCommand() {
            return new McpWikiApprovalService.ApprovalRequest(draftId, draftVersion, draftHash, publicStateHash);
        }
    }
    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE, maxProperties = 0)
    public record EmptyBody() {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException(name); }
    }
    private static void requireUuid4(String value) {
        try { java.util.UUID id=java.util.UUID.fromString(value); if(id.version()!=4||!id.toString().equals(value)) throw new IllegalArgumentException(); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("draft_id"); }
    }
}
