package fruition.core.skill.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.shared.util.ErrorResponse;
import fruition.core.skill.dto.SkillAuthoringRequest;
import fruition.core.skill.dto.SkillPublishRequest;
import fruition.core.skill.dto.SkillUpdateRequest;
import fruition.core.skill.service.SkillService;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/skills")
@Tag(name = "Skills", description = "재사용 가능한 Agent Skill 작성, 게시 및 실행 설정 API")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @Operation(summary = "Skill 초안 작성", description = "자연어 요구를 바탕으로 저장 전 Skill 초안을 생성합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "초안 작성 성공",
            content = @Content(schema = @Schema(implementation = SkillAuthoringResponseSchema.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 요청",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스 또는 참조 문서를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Skill 이름 또는 버전 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "410", description = "Skill이 더 이상 유효하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "413", description = "참조 문서 또는 요청 본문이 너무 큼",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "422", description = "Skill 요청 검증 실패",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/author")
    public ResponseEntity<JsonNode> author(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SkillAuthoringRequest request,
            @org.springframework.web.bind.annotation.RequestParam(value = "run_id", required = false) String runId) {
        runId = runId == null ? "skill_" + java.util.UUID.randomUUID() : runId;
        return ResponseEntity.ok(skillService.author(workspaceId, userId, request, runId));
    }

    @Operation(summary = "Skill 게시", description = "작성된 Skill 정의를 검토 후 게시합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "게시 성공",
            content = @Content(schema = @Schema(implementation = SkillAuthoringResponseSchema.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 Skill 정의",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스 또는 참조 문서를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Skill 이름 또는 버전 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "410", description = "Skill이 더 이상 유효하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "413", description = "요청 본문이 너무 큼",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "422", description = "Skill 요청 검증 실패",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/author/publish")
    public ResponseEntity<JsonNode> publish(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SkillPublishRequest request,
            @org.springframework.web.bind.annotation.RequestParam(value = "run_id", required = false) String runId) {
        runId = runId == null ? "skill_" + java.util.UUID.randomUUID() : runId;
        return ResponseEntity.ok(skillService.publish(workspaceId, userId, request, runId));
    }

    @Operation(summary = "Skill 목록 조회", description = "현재 사용자가 사용할 수 있는 워크스페이스 Skill 목록을 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "목록 조회 성공",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = SkillResponseSchema.class)))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Skill 요청 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "410", description = "Skill이 더 이상 유효하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<JsonNode> list(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(skillService.list(workspaceId, userId));
    }

    @Operation(summary = "Skill 상세 조회", description = "Skill의 현재 정의와 실행 설정을 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "상세 조회 성공",
            content = @Content(schema = @Schema(implementation = SkillResponseSchema.class))),
        @ApiResponse(responseCode = "404", description = "Skill 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Skill 요청 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "410", description = "Skill이 더 이상 유효하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{skill_id}")
    public ResponseEntity<JsonNode> get(
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(description = "조회할 Skill ID", required = true)
            @PathVariable("skill_id") String skillId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(skillService.get(workspaceId, userId, skillId));
    }

    @Operation(summary = "Skill 수정", description = "Skill의 정의를 수정합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "수정 성공",
            content = @Content(schema = @Schema(implementation = SkillAuthoringResponseSchema.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 Skill 정의",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Skill 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Skill 이름 또는 버전 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "410", description = "Skill이 더 이상 유효하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "413", description = "요청 본문이 너무 큼",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "422", description = "Skill 요청 검증 실패",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{skill_id}")
    public ResponseEntity<JsonNode> update(
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(description = "수정할 Skill ID", required = true)
            @PathVariable("skill_id") String skillId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SkillUpdateRequest request,
            @org.springframework.web.bind.annotation.RequestParam(value = "run_id", required = false) String runId) {
        runId = runId == null ? "skill_" + java.util.UUID.randomUUID() : runId;
        return ResponseEntity.ok(skillService.update(workspaceId, userId, skillId, request, runId));
    }

    @Operation(summary = "Skill 활성화", description = "Skill을 Agent 실행 대상에 포함합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "활성화 성공",
            content = @Content(schema = @Schema(implementation = SkillResponseSchema.class))),
        @ApiResponse(responseCode = "404", description = "Skill 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Skill 요청 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "410", description = "Skill이 더 이상 유효하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{skill_id}/enable")
    public ResponseEntity<JsonNode> enable(
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(description = "활성화할 Skill ID", required = true)
            @PathVariable("skill_id") String skillId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(skillService.setEnabled(workspaceId, userId, skillId, true));
    }

    @Operation(summary = "Skill 비활성화", description = "Skill을 Agent 실행 대상에서 제외합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "비활성화 성공",
            content = @Content(schema = @Schema(implementation = SkillResponseSchema.class))),
        @ApiResponse(responseCode = "404", description = "Skill 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Skill 요청 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "410", description = "Skill이 더 이상 유효하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{skill_id}/disable")
    public ResponseEntity<JsonNode> disable(
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(description = "비활성화할 Skill ID", required = true)
            @PathVariable("skill_id") String skillId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(skillService.setEnabled(workspaceId, userId, skillId, false));
    }

    @Schema(name = "SkillResponse", description = "Skill 하나. 게시된 버전(enabled_version)과 가장 최근 버전(latest_version)이 다를 수 있다.",
            requiredProperties = {"id", "workspace_id", "scope_type", "owner_user_id", "slug", "status", "enabled_version", "latest_version"})
    private static final class SkillResponseSchema {
        @Schema(description = "Skill ID")
        public String id;
        @Schema(description = "team 범위일 때의 워크스페이스 ID. personal이면 null이다.", nullable = true)
        public String workspace_id;
        @Schema(description = "공개 범위", allowableValues = {"personal", "team"}, example = "personal")
        public String scope_type;
        @Schema(description = "personal 범위일 때의 소유자 사용자 ID. team이면 null이다.", nullable = true)
        public String owner_user_id;
        @Schema(description = "URL·참조에 쓰는 식별자", example = "meeting-notes")
        public String slug;
        @Schema(description = "Skill 상태", example = "published")
        public String status;
        @Schema(description = "현재 활성화된 버전. 없으면 null이다.",
                allOf = SkillVersionResponseSchema.class, nullable = true)
        public Object enabled_version;
        @Schema(description = "가장 최근에 만들어진 버전. 없으면 null이다.",
                allOf = SkillVersionResponseSchema.class, nullable = true)
        public Object latest_version;
    }

    @Schema(name = "SkillVersionResponse", description = "Skill의 특정 버전 내용",
            requiredProperties = {"id", "version", "name", "description", "instructions_markdown", "capabilities", "allowed_tools", "lint_result", "status"})
    private static final class SkillVersionResponseSchema {
        @Schema(description = "버전 ID")
        public String id;
        @Schema(description = "버전 번호. 게시할 때마다 1씩 올라간다.", example = "3")
        public int version;
        @Schema(description = "Skill 이름", example = "meeting-notes")
        public String name;
        @Schema(description = "Skill 설명")
        public String description;
        @Schema(description = "Skill 지침 Markdown")
        public String instructions_markdown;
        @Schema(description = "이 Skill이 수행할 수 있는 기능 목록")
        public java.util.List<String> capabilities;
        @Schema(description = "이 Skill이 쓸 수 있는 도구 목록")
        public java.util.List<String> allowed_tools;
        @Schema(description = "게시 전 점검 결과. 항목별 통과 여부가 담긴다.")
        public java.util.Map<String, Object> lint_result;
        @Schema(description = "버전 상태", example = "published")
        public String status;
    }

    @Schema(name = "SkillAuthoringResponse", requiredProperties = "status")
    private static final class SkillAuthoringResponseSchema {
        public String status;
        public String question;
        public String skill_id;
        public String version_id;
        public String scope_type;
        public String name;
        public String description;
        public String instructions_markdown;
        public String skill_markdown;
        public java.util.List<String> capabilities;
        public java.util.List<String> allowed_tools;
        public java.util.List<Object> issues;
    }
}
