package fruition.core.imports;

import fruition.core.imports.DocumentImportDtos.CompleteRequest;
import fruition.core.imports.DocumentImportDtos.CompleteResponse;
import fruition.core.imports.DocumentImportDtos.CreateRequest;
import fruition.core.imports.DocumentImportDtos.CreateResponse;
import fruition.core.imports.DocumentImportDtos.ImportResponse;
import fruition.core.imports.DocumentImportDtos.IngestEstimateResponse;
import fruition.core.imports.DocumentImportDtos.IngestRequest;
import fruition.core.imports.DocumentImportDtos.IngestResponse;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/imports")
@Tag(name = "Imports", description = "외부 내보내기(Notion Markdown & CSV) 가져오기 세션")
public class DocumentImportController {
    private final DocumentImportService imports;

    public DocumentImportController(DocumentImportService imports) {
        this.imports = imports;
    }

    @Operation(operationId = "createDocumentImport", summary = "가져오기 세션 시작",
            description = "세션은 24시간 뒤 만료됩니다. 이 세션의 import_id를 X-Fruition-Import-Id 헤더로 문서 생성·폴더 생성·본문 저장에 "
                    + "붙이면 만든 문서를 이 세션으로 표시하고 자동 편입하지 않습니다. 저장 용량(5GB)과 파일별 한도는 일반 업로드와 같고, 세션 항목 수 상한은 없습니다(max_items는 항상 null).")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "세션 생성",
            content = @Content(schema = @Schema(implementation = CreateResponse.class))),
        @ApiResponse(responseCode = "400", description = "source가 notion이 아니거나 값이 없거나 음수(INVALID_REQUEST)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스 멤버가 아님(WORKSPACE_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "413", description = "used_bytes + total_bytes가 저장 공간 한도 초과(STORAGE_QUOTA_EXCEEDED)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<CreateResponse> create(@PathVariable("workspace_id") String workspaceId,
                                                 @AuthenticationPrincipal String userId,
                                                 @RequestBody(required = false) CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(imports.create(workspaceId, userId, request));
    }

    @Operation(operationId = "completeDocumentImport", summary = "가져오기 세션 완료",
            description = "세션을 닫습니다. 이후 이 세션으로 쓰면 400 IMPORT_INVALID입니다. 이미 완료한 세션은 같은 결과를 돌려줍니다. "
                    + "document_count는 서버가 센 실제 생성 문서 수입니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "완료",
            content = @Content(schema = @Schema(implementation = CompleteResponse.class))),
        @ApiResponse(responseCode = "400", description = "음수 값(INVALID_REQUEST)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "내 세션이 아님(IMPORT_NOT_FOUND) 또는 워크스페이스 멤버가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{import_id}/complete")
    public CompleteResponse complete(@PathVariable("workspace_id") String workspaceId,
                                     @AuthenticationPrincipal String userId,
                                     @PathVariable("import_id") String importId,
                                     @RequestBody(required = false) CompleteRequest request) {
        return imports.complete(workspaceId, userId, importId, request);
    }

    @Operation(operationId = "getDocumentImport", summary = "가져오기 세션 조회")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회",
            content = @Content(schema = @Schema(implementation = ImportResponse.class))),
        @ApiResponse(responseCode = "404", description = "내 세션이 아님(IMPORT_NOT_FOUND) 또는 워크스페이스 멤버가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{import_id}")
    public ImportResponse get(@PathVariable("workspace_id") String workspaceId,
                              @AuthenticationPrincipal String userId,
                              @PathVariable("import_id") String importId) {
        return imports.get(workspaceId, userId, importId);
    }

    @Operation(operationId = "estimateDocumentImportIngest", summary = "가져온 문서 편입 예상 비용",
            description = "아직 편입하지 않은 가져온 문서를 모두 편입할 때의 예상치입니다(문서 편입 1건 예상치 × 문서 수). 실제 비용은 문서 길이에 따라 다릅니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "예상치",
            content = @Content(schema = @Schema(implementation = IngestEstimateResponse.class))),
        @ApiResponse(responseCode = "404", description = "내 세션이 아님(IMPORT_NOT_FOUND) 또는 워크스페이스 멤버가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{import_id}/ingest-estimate")
    public IngestEstimateResponse estimate(@PathVariable("workspace_id") String workspaceId,
                                           @AuthenticationPrincipal String userId,
                                           @PathVariable("import_id") String importId) {
        return imports.estimate(workspaceId, userId, importId);
    }

    @Operation(operationId = "ingestDocumentImport", summary = "가져온 문서 위키 편입",
            description = "문서마다 수동 위키 편입(POST /documents/{id}/ingest)과 같은 경로로 큐에 넣습니다(권한·AI 한도·크레딧 동일). "
                    + "한 번에 20개까지 넣으며, document_ids를 생략하면 아직 편입하지 않은 문서 중 앞 20개를 넣습니다. "
                    + "remaining이 0이 될 때까지 다시 부르면 됩니다. 첫 문서부터 거절되면 그 오류(403·402·429 등)를 그대로 돌려줍니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "큐 등록",
            content = @Content(schema = @Schema(implementation = IngestResponse.class))),
        @ApiResponse(responseCode = "400", description = "document_ids가 비었거나 20개 초과, 또는 이 세션 문서가 아님(INVALID_REQUEST)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "402", description = "크레딧 부족(INSUFFICIENT_CREDIT)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "wiki.ingest 권한 없음(WORKSPACE_PERMISSION_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "내 세션이 아님(IMPORT_NOT_FOUND) 또는 워크스페이스 멤버가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "429", description = "AI 사용 한도 초과(AI_USAGE_LIMIT_EXCEEDED)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{import_id}/ingest")
    public ResponseEntity<IngestResponse> ingest(@PathVariable("workspace_id") String workspaceId,
                                                 @AuthenticationPrincipal String userId,
                                                 @PathVariable("import_id") String importId,
                                                 @RequestBody(required = false) IngestRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(imports.ingest(workspaceId, userId, importId, request == null ? null : request.documentIds()));
    }
}
