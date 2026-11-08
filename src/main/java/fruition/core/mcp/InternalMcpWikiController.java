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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/mcp/wiki")
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class InternalMcpWikiController {
    private final McpWikiApprovalService service;
    public InternalMcpWikiController(McpWikiApprovalService service) { this.service = service; }

    @Operation(operationId = "lookupMcpWikiApproval",
            summary = "AI apply 경계의 authoritative approval/source lookup")
    @PostMapping("/approvals/lookup")
    public ResponseEntity<McpWikiApprovalService.ApprovalStatus> lookup(@Valid @RequestBody ApprovalLookupBody body) {
        if (!"mcp.wiki-approval-lookup.v1".equals(body.schemaVersion())) throw new IllegalArgumentException("schema_version");
        return ResponseEntity.ok(service.lookup(body.toCommand()));
    }

    @Operation(operationId = "receiveMcpWikiPublication",
            summary = "Body-free durable MCP wiki publication 수신 및 fee reconciliation")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "수신·중복 판정·수수료 결정을 원자적으로 반영"),
            @ApiResponse(responseCode = "409", description = "Canonical event 또는 stable pair 충돌")
    })
    @PostMapping("/publications/receipt")
    public ResponseEntity<Void> publication(@Valid @RequestBody PublicationBody body) {
        service.acceptPublication(body.toEvent());
        return ResponseEntity.noContent().build();
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ApprovalLookupBody(
            @NotBlank @JsonProperty("schema_version") String schemaVersion,
            @NotBlank @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") @JsonProperty("approval_id") String approvalId,
            @NotBlank @Size(max=255) @JsonProperty("operation_id") String operationId,
            @NotBlank @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") @JsonProperty("draft_id") String draftId,
            @Min(1) @JsonProperty("draft_version") long draftVersion,
            @NotBlank @Pattern(regexp="^sha256:[0-9a-f]{64}$") @JsonProperty("draft_hash") String draftHash,
            @NotBlank @Pattern(regexp="^sha256:[0-9a-f]{64}$") @JsonProperty("public_state_hash") String publicStateHash,
            @NotBlank @Size(max=255) @JsonProperty("workspace_id") String workspaceId,
            @NotBlank @Size(max=255) @JsonProperty("user_id") String userId,
            @NotBlank @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") @JsonProperty("grant_id") String grantId,
            @NotBlank @Size(max=2048) @JsonProperty("client_id") String clientId,
            @Min(1) @JsonProperty("policy_generation") long policyGeneration,
            @NotNull @Size(min=1,max=64) @Valid @JsonProperty("source_snapshots") List<SourceBody> sourceSnapshots) {
        @JsonAnySetter public void rejectUnknown(String name,Object value){throw new IllegalArgumentException(name);}
        McpWikiApprovalService.ApprovalLookup toCommand(){return new McpWikiApprovalService.ApprovalLookup(
                approvalId,operationId,draftId,draftVersion,draftHash,publicStateHash,workspaceId,userId,grantId,
                clientId,policyGeneration,sourceSnapshots.stream().map(SourceBody::toSource).toList());}
    }
    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record SourceBody(
            @NotBlank @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") @JsonProperty("snapshot_id") String snapshotId,
            @NotBlank @Size(max=255) @JsonProperty("document_id") String documentId,
            @Min(1) @JsonProperty("source_version") long sourceVersion,
            @NotBlank @Pattern(regexp="^sha256:[0-9a-f]{64}$") @JsonProperty("source_content_hash") String sourceContentHash){
        @JsonAnySetter public void rejectUnknown(String name,Object value){throw new IllegalArgumentException(name);}
        McpWikiApprovalService.Source toSource(){return new McpWikiApprovalService.Source(snapshotId,documentId,sourceVersion,sourceContentHash);}
    }
    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record PublicationBody(
            @NotBlank @JsonProperty("event_id") String eventId,
            @NotBlank @JsonProperty("event_type") String eventType,
            @NotBlank @JsonProperty("approval_id") String approvalId,
            @NotBlank @JsonProperty("operation_id") String operationId,
            @NotBlank @JsonProperty("draft_id") String draftId,
            @NotBlank @JsonProperty("workspace_id") String workspaceId,
            @NotBlank @JsonProperty("user_id") String userId,
            @NotBlank @JsonProperty("grant_id") String grantId,
            @NotBlank @JsonProperty("client_id") String clientId,
            @NotBlank String kind,@NotBlank String outcome,boolean changed,
            @NotBlank @JsonProperty("published_reference_hash") String publishedReferenceHash,
            @NotBlank @JsonProperty("committed_at") String committedAt){
        @JsonAnySetter public void rejectUnknown(String name,Object value){throw new IllegalArgumentException(name);}
        McpWikiApprovalService.PublicationEvent toEvent(){return new McpWikiApprovalService.PublicationEvent(eventId,eventType,
                approvalId,operationId,draftId,workspaceId,userId,grantId,clientId,kind,outcome,changed,
                publishedReferenceHash,committedAt);}
    }
}
