package fruition.core.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.shared.ai.AiModelCatalog;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI Models", description = "선택 가능한 AI 모델 카탈로그와 워크스페이스 모델 설정 API")
@RestController
@RequestMapping("/api/workspaces/{workspace_id}/ai-model-settings")
public class WorkspaceAiModelSettingsController {
    private final WorkspaceAccessGuard accessGuard;
    private final WorkspaceAiModelClient client;
    private final AiModelCatalog catalog;

    public WorkspaceAiModelSettingsController(WorkspaceAccessGuard accessGuard,
                                              WorkspaceAiModelClient client,
                                              AiModelCatalog catalog) {
        this.accessGuard = accessGuard;
        this.client = client;
        this.catalog = catalog;
    }

    @Operation(
        summary = "워크스페이스 AI 모델 설정 조회",
        description = "ingest·lint 작업에 쓰는 provider/model 설정을 반환합니다. OWNER와 MEMBER 모두 조회할 수 있습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = SettingsResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<SettingsResponse> get(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId) {
        accessGuard.requireMember(workspaceId, userId);
        // MEMBER에게는 read-only UI를 보여줄 수 있도록 변경 가능 여부(OWNER)를 함께 내려준다.
        boolean canUpdate = accessGuard.isOwner(workspaceId, userId);
        return ResponseEntity.ok(new SettingsResponse(client.get(workspaceId), canUpdate));
    }

    @Operation(
        summary = "워크스페이스 AI 모델 설정 변경",
        description = "ingest·lint에 쓸 provider/model을 바꿉니다. OWNER만 호출할 수 있고, "
                + "활성 model catalog에 있는 조합만 허용합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "변경 성공",
            content = @Content(schema = @Schema(implementation = SettingsResponse.class))),
        @ApiResponse(responseCode = "400", description = "catalog에 없는 provider/model 조합",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "OWNER가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping
    public ResponseEntity<SettingsResponse> update(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId,
            @Valid @RequestBody SettingsRequest request) {
        String role = accessGuard.getRole(workspaceId, userId);
        if ("NONE".equals(role)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
        if (!"OWNER".equals(role)) {
            throw new WorkspaceAiModelForbiddenException();
        }
        AiModelCatalog.AiModel selected = catalog.resolve(
                request.ingestLint().provider(), request.ingestLint().model());
        // 여기까지 왔으면 호출자는 OWNER이므로 can_update는 항상 true다.
        return ResponseEntity.ok(new SettingsResponse(
                client.update(workspaceId, selected.provider(), selected.model()), true));
    }

    @Schema(description = "AI 모델 설정 변경 요청")
    public record SettingsRequest(
            @JsonProperty("ingest_lint") @NotNull @Valid
            @Schema(description = "ingest·lint에 쓸 provider/model 조합")
            AiModelSelection ingestLint) {}

    // WorkspaceAiModelClient의 동명 record와 단순 이름이 겹쳐 명세에서 한쪽이 덮인다. 요청은
    // 필수·제약이 있고 응답은 없으므로 합쳐지면 계약이 틀어진다 — 스키마 이름을 분리한다.
    @Schema(name = "AiModelSelectionRequest",
            description = "설정할 provider와 model 조합. 활성 catalog에 있는 짝만 허용된다.")
    public record AiModelSelection(
            @NotBlank
            @Schema(description = "LLM provider", allowableValues = {"openai", "gemini", "claude"},
                    example = "openai")
            String provider,

            @NotBlank
            @Schema(description = "모델명", example = "gpt-5-nano")
            String model) {}

    @Schema(description = "AI 모델 설정. 기본값은 gemini + gemini-3.1-flash-lite다.")
    public record SettingsResponse(
            @JsonProperty("ingest_lint")
            @Schema(description = "ingest·lint에 쓰는 provider/model 조합")
            WorkspaceAiModelClient.AiModelSelection ingestLint,

            @JsonProperty("can_update")
            @Schema(description = "호출자가 이 설정을 변경할 수 있는지(워크스페이스 OWNER 여부)", example = "true")
            boolean canUpdate) {}
}
