package fruition.core.wikischema.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.shared.util.ErrorResponse;
import fruition.core.wikischema.dto.WikiSchemaDraftRequest;
import fruition.core.wikischema.dto.WikiSchemaPreviewRequest;
import fruition.core.wikischema.service.WikiSchemaService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/wiki-schema")
@Tag(name = "Wiki Schema", description = "Wiki 생성 규칙의 미리보기, 초안 저장 및 활성화 API")
public class WikiSchemaController {

    private final WikiSchemaService wikiSchemaService;

    public WikiSchemaController(WikiSchemaService wikiSchemaService) {
        this.wikiSchemaService = wikiSchemaService;
    }

    @Operation(summary = "Wiki Schema 미리보기", description = "Schema 규칙을 저장하지 않고 적용해 예상 Wiki 구조를 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "미리보기 생성 성공",
            content = @Content(schema = @Schema(implementation = WikiSchemaPreviewResponseSchema.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 Schema 또는 입력",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "422", description = "Schema 요청 검증 실패",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/preview")
    public ResponseEntity<JsonNode> preview(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody WikiSchemaPreviewRequest request) {
        return ResponseEntity.ok(wikiSchemaService.preview(workspaceId, userId, request));
    }

    @Operation(summary = "Wiki Schema 초안 생성", description = "검토할 Wiki 생성 규칙을 초안 상태로 저장합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "초안 생성 성공",
            content = @Content(schema = @Schema(implementation = WikiSchemaDraftResponseSchema.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 Schema 정의",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "422", description = "Schema 요청 검증 실패",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/drafts")
    public ResponseEntity<JsonNode> createDraft(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody WikiSchemaDraftRequest request) {
        return ResponseEntity.ok(wikiSchemaService.createDraft(workspaceId, userId, request));
    }

    @Operation(summary = "Wiki Schema 활성화", description = "선택한 Wiki Schema ID의 활성화를 요청합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "활성화 성공",
            content = @Content(schema = @Schema(implementation = WikiSchemaResponseSchema.class))),
        @ApiResponse(responseCode = "404", description = "Schema 또는 워크스페이스를 찾을 수 없음",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{schema_id}/activate")
    public ResponseEntity<JsonNode> activate(
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(description = "활성화할 Wiki Schema ID", required = true)
            @PathVariable("schema_id") String schemaId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(wikiSchemaService.activate(workspaceId, userId, schemaId));
    }

    @Operation(summary = "활성 Wiki Schema 조회", description = "활성 Schema가 없으면 null을 포함한 200 응답을 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "활성 Schema 조회 성공(null 가능)",
            content = @Content(schema = @Schema(implementation = WikiSchemaResponseSchema.class, nullable = true))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/active")
    public ResponseEntity<JsonNode> getActive(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(wikiSchemaService.getActive(workspaceId, userId));
    }

    @Schema(name = "WikiSchemaPreviewResponse", description = "저장하지 않고 스키마 해석 결과만 보여주는 응답",
            requiredProperties = {"fragments", "issues", "preview_markdown", "has_blocked_issues"})
    private static final class WikiSchemaPreviewResponseSchema {
        @Schema(description = "용도별로 쪼갠 스키마 조각")
        public WikiSchemaFragmentsSchema fragments;
        @Schema(description = "해석 중 발견한 문제 목록")
        public java.util.List<WikiSchemaIssueSchema> issues;
        @Schema(description = "해석 결과를 사람이 읽도록 정리한 Markdown")
        public String preview_markdown;
        @Schema(description = "활성화를 막는 blocked 문제가 있는지 여부", example = "false")
        public boolean has_blocked_issues;
    }

    @Schema(name = "WikiSchemaDraftResponse", description = "초안 생성 결과",
            requiredProperties = "wiki_schema")
    private static final class WikiSchemaDraftResponseSchema {
        @Schema(description = "생성된 스키마 초안")
        public WikiSchemaResponseSchema wiki_schema;
    }

    @Schema(name = "WikiSchemaResponse", description = "Wiki 스키마 하나. 활성화되면 Wiki 생성·질의에 쓰인다.",
            requiredProperties = {"id", "workspace_id", "user_id", "name", "raw_markdown", "fragments", "issues", "preview_markdown", "has_blocked_issues", "status", "schema_version"})
    private static final class WikiSchemaResponseSchema {
        @Schema(description = "스키마 ID")
        public String id;
        @Schema(description = "이 스키마가 속한 워크스페이스 ID", example = "ws_9d47a0e9a6324341b47562553b75f92a")
        public String workspace_id;
        @Schema(description = "초안을 만든 사용자 ID", example = "user_3f1c8a6b52d7411e9c04ab5d2e7f6081")
        public String user_id;
        @Schema(description = "스키마 이름", example = "설계 문서 스키마")
        public String name;
        @Schema(description = "입력으로 준 원본 Markdown")
        public String raw_markdown;
        @Schema(description = "용도별로 쪼갠 스키마 조각")
        public WikiSchemaFragmentsSchema fragments;
        @Schema(description = "해석 중 발견한 문제 목록")
        public java.util.List<WikiSchemaIssueSchema> issues;
        @Schema(description = "해석 결과를 사람이 읽도록 정리한 Markdown")
        public String preview_markdown;
        @Schema(description = "활성화를 막는 blocked 문제가 있는지 여부", example = "false")
        public boolean has_blocked_issues;
        @Schema(description = "스키마 상태", example = "draft")
        public String status;
        @Schema(description = "스키마 형식 버전", example = "v1")
        public String schema_version;
        @Schema(description = "생성 시각(ISO-8601 UTC)", nullable = true,
                example = "2026-08-13T04:25:24.371948Z")
        public String created_at;
        @Schema(description = "마지막 변경 시각(ISO-8601 UTC)", nullable = true,
                example = "2026-08-13T04:25:24.371948Z")
        public String updated_at;
        @Schema(description = "활성화된 시각(ISO-8601 UTC). 활성화 전이면 null이다.", nullable = true)
        public String activated_at;
    }

    @Schema(name = "WikiSchemaFragmentsResponse", description = "스키마를 쓰이는 곳별로 쪼갠 조각. 각 조각이 해당 단계의 프롬프트에 들어간다.",
            requiredProperties = {"global_markdown", "query_markdown", "ingest_markdown", "edit_markdown", "concept_markdown", "template_markdown"})
    private static final class WikiSchemaFragmentsSchema {
        @Schema(description = "모든 단계에 공통으로 들어가는 규칙")
        public String global_markdown;
        @Schema(description = "질의 단계에만 적용되는 규칙")
        public String query_markdown;
        @Schema(description = "문서 ingest 단계에만 적용되는 규칙")
        public String ingest_markdown;
        @Schema(description = "편집 단계에만 적용되는 규칙")
        public String edit_markdown;
        @Schema(description = "Concept 페이지 작성 규칙")
        public String concept_markdown;
        @Schema(description = "페이지 템플릿 정의")
        public String template_markdown;
    }

    @Schema(name = "WikiSchemaIssueResponse", description = "스키마 해석 중 발견한 문제 하나",
            requiredProperties = {"severity", "category", "text", "reason"})
    private static final class WikiSchemaIssueSchema {
        @Schema(description = "심각도. blocked면 활성화할 수 없고 unclear는 경고다.",
                allowableValues = {"blocked", "unclear"}, example = "unclear")
        public String severity;
        @Schema(description = "문제 분류")
        public String category;
        @Schema(description = "문제가 된 원문 구절")
        public String text;
        @Schema(description = "왜 문제인지에 대한 설명")
        public String reason;
        @Schema(description = "문제가 발견된 섹션 이름. 특정할 수 없으면 null이다.", nullable = true)
        public String section;
    }
}
