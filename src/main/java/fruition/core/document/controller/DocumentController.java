package fruition.core.document.controller;

import fruition.shared.util.ErrorResponse;
import fruition.core.document.service.DocumentService;
import fruition.core.document.service.DocumentExportService;
import fruition.core.document.dto.DocumentBlocksResponse;
import fruition.core.document.dto.DocumentContentSaveResponse;
import fruition.core.document.dto.DocumentContentDiffResponse;
import fruition.core.document.dto.DocumentContentRestoreRequest;
import fruition.core.document.dto.DocumentContentVersionListResponse;
import fruition.core.document.dto.DocumentContentVersionResponse;
import fruition.core.document.dto.DocumentDetailResponse;
import fruition.core.document.dto.DocumentDuplicateResponse;
import fruition.core.document.dto.DocumentIngestResponse;
import fruition.core.document.dto.DocumentExportResult;
import fruition.core.document.dto.DocumentListResponse;
import fruition.core.document.dto.DocumentLifecycleRequest;
import fruition.core.document.dto.DocumentLifecycleResponse;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.dto.DocumentOriginalResult;
import fruition.core.document.dto.DocumentRenameRequest;
import fruition.core.document.dto.DocumentRenameResponse;
import fruition.core.document.dto.DocumentUploadResponse;
import fruition.core.document.dto.DocumentTrashResponse;
import fruition.core.document.exception.InvalidDocumentVersionException;
import fruition.core.document.service.DocumentAssetContentService;
import fruition.core.document.exception.InvalidMarkdownContentException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.util.LinkedMultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.Set;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/documents")
@Tag(name = "Documents", description = "Markdown 편집 문서와 업로드 원본 관리 API")
public class DocumentController {

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "text/markdown",
            "text/x-markdown"
    );

    private final DocumentService documentService;
    private final DocumentExportService documentExportService;
    private final DocumentAssetContentService documentAssetContentService;

    public DocumentController(
            DocumentService documentService,
            DocumentExportService documentExportService,
            DocumentAssetContentService documentAssetContentService
    ) {
        this.documentService = documentService;
        this.documentExportService = documentExportService;
        this.documentAssetContentService = documentAssetContentService;
    }

    @Operation(
        summary = "문서 업로드",
        description = "PDF 또는 Markdown 파일을 업로드합니다. Markdown은 편집 상태와 처리 큐를 생성하고, PDF는 읽기 전용 원본으로만 저장합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "업로드 성공",
            content = @Content(schema = @Schema(implementation = DocumentUploadResponse.class))),
        @ApiResponse(responseCode = "400", description = "파일 없음 또는 잘못된 요청",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Idempotency-Key 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "415", description = "지원하지 않는 파일 형식",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "요청 멱등 키", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(value = "folder_id", required = false) java.util.UUID folderId,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of("INVALID_REQUEST", "파일이 없거나 비어 있습니다."));
        }

        String mimeType = file.getContentType();
        String normalizedFilename = file.getOriginalFilename() == null
                ? ""
                : file.getOriginalFilename().toLowerCase(java.util.Locale.ROOT);
        boolean isMdByExtension = normalizedFilename.endsWith(".md")
                || normalizedFilename.endsWith(".markdown");

        if (!ALLOWED_MIME_TYPES.contains(mimeType) && !isMdByExtension) {
            return ResponseEntity
                    .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(ErrorResponse.of("UNSUPPORTED_FILE_TYPE", "PDF 또는 Markdown 파일만 업로드할 수 있습니다."));
        }

        DocumentUploadResponse response = documentService.upload(workspaceId, userId, idempotencyKey, folderId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
        summary = "Markdown 문서 생성",
        description = "표시 이름과 전체 Markdown 본문으로 즉시 편집 가능한 문서를 생성합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "생성 성공 또는 멱등 재요청",
            content = @Content(schema = @Schema(implementation = DocumentUploadResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 본문 또는 Idempotency-Key",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Idempotency-Key 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "413", description = "Markdown 5MB 초과",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(path = "/markdown", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentUploadResponse> createMarkdown(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "요청 멱등 키", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody MarkdownDocumentCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentService.createMarkdown(workspaceId, userId, idempotencyKey, request));
    }

    @Operation(summary = "문서 목록 조회", description = "활성 문서의 호환용 평면 목록을 반환하며 파일명 검색을 지원합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "목록 조회 성공",
            content = @Content(schema = @Schema(implementation = DocumentListResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<DocumentListResponse> list(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @RequestParam(value = "query", required = false) String query) {
        return ResponseEntity.ok(documentService.findAll(workspaceId, userId, query));
    }

    @Operation(summary = "문서 상세 조회", description = "특정 문서의 상세 정보를 반환합니다. 연결된 Wiki 페이지 목록이 포함됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "상세 조회 성공",
            content = @Content(schema = @Schema(implementation = DocumentDetailResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{document_id}")
    public ResponseEntity<DocumentDetailResponse> getById(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId) {
        return ResponseEntity.ok(documentService.findById(workspaceId, userId, documentId));
    }

    @Operation(summary = "원본 문서 조회", description = "MinIO에 저장된 원본 파일을 스트리밍합니다. PDF는 inline, 그 외는 attachment로 반환됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "원본 파일 반환"),
        @ApiResponse(responseCode = "404", description = "문서, 원본 파일 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{document_id}/original")
    public ResponseEntity<InputStreamResource> getOriginal(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId) {
        DocumentOriginalResult result = documentService.getOriginal(workspaceId, userId, documentId);

        String disposition = isInlineable(result.mimeType())
                ? "inline; filename=\"" + result.filename() + "\""
                : "attachment; filename=\"" + result.filename() + "\"";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(new InputStreamResource(result.inputStream()));
    }

    private boolean isInlineable(String mimeType) {
        return mimeType != null && (mimeType.startsWith("text/") || mimeType.equals("application/pdf"));
    }

    @Operation(
        summary = "Markdown 원문 내보내기",
        description = "최신 Markdown 편집본을 내보냅니다. 관리 이미지가 있으면 이미지와 Markdown을 ZIP으로 반환합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Markdown 다운로드"),
        @ApiResponse(responseCode = "404", description = "workspace, Markdown 문서 또는 편집 상태를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{document_id}/export")
    public ResponseEntity<InputStreamResource> export(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId) {
        DocumentExportResult result =
                documentExportService.exportMarkdown(workspaceId, userId, documentId);
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(result.filename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .contentLength(result.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .body(new InputStreamResource(result.content()));
    }

    @Operation(summary = "원본 문서 block 목록 조회", description = "원본 문서를 block 단위로 나눈 텍스트 목록을 반환합니다. 답변 인용 클릭 시 원본 block 하이라이트에 사용됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = DocumentBlocksResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{document_id}/blocks")
    public ResponseEntity<DocumentBlocksResponse> blocks(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId) {
        return ResponseEntity.ok(documentService.blocks(workspaceId, userId, documentId));
    }

    @Operation(summary = "문서 삭제", description = "원본과 편집 상태를 유지한 채 문서를 소프트 삭제합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "삭제 성공",
            content = @Content(schema = @Schema(implementation = DocumentLifecycleResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 base_version 또는 Idempotency-Key",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "문서 소유자가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "문서 version 또는 멱등 키 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{document_id}")
    public ResponseEntity<DocumentLifecycleResponse> delete(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId,
            @Parameter(description = "요청 멱등 키", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DocumentLifecycleRequest request) {
        return ResponseEntity.ok(documentService.delete(
                workspaceId, userId, documentId, idempotencyKey, request));
    }

    @Operation(summary = "문서 휴지통", description = "워크스페이스에서 소프트 삭제된 문서를 삭제 시각 역순으로 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "휴지통 조회 성공",
            content = @Content(schema = @Schema(implementation = DocumentTrashResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/trash")
    public ResponseEntity<DocumentTrashResponse> trash(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(documentService.trash(workspaceId, userId));
    }

    @Operation(summary = "문서 복구", description = "삭제 문서를 역할별 최상위 마지막 위치에 복구합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "복구 성공",
            content = @Content(schema = @Schema(implementation = DocumentLifecycleResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 base_version 또는 Idempotency-Key",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "문서 소유자가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "삭제 문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "문서 version 또는 멱등 키 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{document_id}/restore")
    public ResponseEntity<DocumentLifecycleResponse> restore(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId,
            @Parameter(description = "요청 멱등 키", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DocumentLifecycleRequest request) {
        return ResponseEntity.ok(documentService.restore(
                workspaceId, userId, documentId, idempotencyKey, request));
    }

    @Operation(summary = "문서 이름 변경", description = "Notion의 page title처럼 표시 이름만 변경하며 본문과 Wiki 제목은 유지합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "이름 변경 성공",
            content = @Content(schema = @Schema(implementation = DocumentRenameResponse.class))),
        @ApiResponse(responseCode = "400", description = "유효하지 않은 파일명",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "문서 소유자가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "문서 version 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{document_id}/rename")
    public ResponseEntity<DocumentRenameResponse> rename(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId,
            @Valid @RequestBody DocumentRenameRequest request) {
        return ResponseEntity.ok(documentService.rename(workspaceId, userId, documentId, request));
    }

    @Operation(
        summary = "Markdown 문서 복제",
        description = "문서 소유자가 최신 Markdown 편집본을 같은 부모의 마지막 위치에 새 문서로 복제합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "복제 성공 또는 멱등 재요청",
            content = @Content(schema = @Schema(implementation = DocumentDuplicateResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 Idempotency-Key",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "문서 소유자가 아니거나 편집 문서가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Idempotency-Key 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{document_id}/duplicate")
    public ResponseEntity<DocumentDuplicateResponse> duplicate(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId,
            @Parameter(description = "요청 멱등 키", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentService.duplicate(workspaceId, userId, documentId, idempotencyKey));
    }

    @Operation(
        summary = "Markdown 본문 저장",
        description = "전체 Markdown과 신규 이미지를 저장합니다. base_revision이 현재 편집 revision과 일치할 때만 반영하며 revision_write_id 재시도는 기존 결과를 반환합니다. 이미지 포함 저장은 metadata part를 사용합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "저장 성공 또는 동일 본문 no-op",
            content = @Content(schema = @Schema(implementation = DocumentContentSaveResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 Markdown, base_revision 또는 revision_write_id",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "문서 소유자가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "편집 revision 또는 revision_write_id 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "413", description = "Markdown 5MB 또는 이미지 제한 초과",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "415", description = "지원하지 않는 이미지 형식",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping(path = "/{document_id}/content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentContentSaveResponse> saveContent(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId,
            @Parameter(description = "이미지 포함 저장 metadata JSON")
            @RequestPart(value = "metadata", required = false) String metadata,
            @Parameter(description = "저장할 전체 Markdown 본문")
            @RequestPart(value = "markdown", required = false) String markdown,
            @Parameter(description = "클라이언트가 조회한 현재 편집 revision", example = "1")
            @RequestPart(value = "base_revision", required = false) String baseRevision,
            @Parameter(description = "같은 저장 재시도에 재사용하는 write ID")
            @RequestPart(value = "revision_write_id", required = false) String revisionWriteId,
            @Parameter(description = "저장 출처. AI 편집 승인 시 \"agent\", 수동 저장 시 생략합니다.")
            @RequestPart(value = "source", required = false) String source,
            @Parameter(description = "AI 편집 적용 표. `POST /agent/turn` 응답의 apply_operation_id를 그대로 전달하면 AI 작업 로그가 남습니다.")
            @RequestPart(value = "apply_operation_id", required = false) String applyOperationId,
            MultipartHttpServletRequest multipartRequest) {
        if (metadata != null) {
            // attachment 후보뿐 아니라 업로드된 file part를 모두 넘겨, 계약에 없는 part는 parser가 거절하게 한다.
            LinkedMultiValueMap<String, MultipartFile> fileParts = new LinkedMultiValueMap<>();
            multipartRequest.getMultiFileMap().forEach((partName, files) -> {
                boolean uploadedFile = files.stream().anyMatch(file ->
                        file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank());
                if (partName.startsWith("attachment_") || uploadedFile) {
                    fileParts.put(partName, files);
                }
            });
            return ResponseEntity.ok(documentAssetContentService.save(
                    workspaceId, userId, documentId, metadata, fileParts, applyOperationId));
        }
        if (markdown == null || baseRevision == null || revisionWriteId == null) {
            throw new InvalidMarkdownContentException(
                    "markdown·base_revision·revision_write_id part가 필요합니다. (이미지 포함 저장은 metadata part 사용)");
        }
        return ResponseEntity.ok(
                documentService.saveContent(
                        workspaceId, userId, documentId, markdown, parseBaseVersion(baseRevision),
                        revisionWriteId, source, applyOperationId));
    }

    @Operation(
        summary = "PDF Markdown 변환",
        description = "PDF 원본 문서를 Markdown 문서로 변환합니다. 변환 결과를 담을 편집 가능 placeholder 문서를 즉시 만들어 반환하고, 실제 변환은 백그라운드에서 진행됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "변환 요청 접수 및 placeholder 문서 생성",
            content = @Content(schema = @Schema(implementation = DocumentUploadResponse.class))),
        @ApiResponse(responseCode = "400", description = "PDF 원본 문서가 아니거나 잘못된 Idempotency-Key",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Idempotency-Key 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{document_id}/convert-markdown")
    public ResponseEntity<DocumentUploadResponse> convertMarkdown(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "PDF 원본 문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId,
            @Parameter(description = "요청 멱등 키", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(documentService.convertToMarkdown(workspaceId, userId, documentId, idempotencyKey));
    }

    @Operation(
        summary = "문서 재ingest",
        description = "Markdown 문서를 Wiki 파이프라인에 넣습니다. 일반 문서는 최신 편집본을 원본으로 승격하고, 채팅 문서는 저장된 문답과 출처 블록을 그대로 사용합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "재처리 큐 등록됨",
            content = @Content(schema = @Schema(implementation = DocumentIngestResponse.class))),
        @ApiResponse(responseCode = "400", description = "편집 가능한 Markdown 문서가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "문서 소유자가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "이미 처리 중인 문서",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{document_id}/ingest")
    public ResponseEntity<DocumentIngestResponse> ingest(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(documentService.ingest(workspaceId, userId, documentId));
    }

    @Operation(summary = "콘텐츠 버전 이력 목록",
        description = "편집 가능 Markdown 문서의 콘텐츠 버전 이력을 최신 순으로 반환합니다. 본문은 제외한 메타데이터만 제공합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = DocumentContentVersionListResponse.class))),
        @ApiResponse(responseCode = "400", description = "편집 가능한 Markdown 문서가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{document_id}/versions")
    public ResponseEntity<DocumentContentVersionListResponse> listVersions(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId) {
        return ResponseEntity.ok(documentService.listContentVersions(workspaceId, userId, documentId));
    }

    @Operation(summary = "콘텐츠 버전 단건 조회", description = "특정 버전의 전체 Markdown 본문을 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = DocumentContentVersionResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 해당 버전을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{document_id}/versions/{version}")
    public ResponseEntity<DocumentContentVersionResponse> getVersion(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId,
            @PathVariable("version") long version) {
        return ResponseEntity.ok(documentService.getContentVersion(workspaceId, userId, documentId, version));
    }

    @Operation(summary = "콘텐츠 버전 비교",
        description = "두 Markdown 버전을 줄 단위로 비교해 GitHub 스타일 diff hunk를 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "비교 성공",
            content = @Content(schema = @Schema(implementation = DocumentContentDiffResponse.class))),
        @ApiResponse(responseCode = "400", description = "편집 가능한 Markdown 문서가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 비교할 버전을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "문서 차이가 너무 커서 안전하게 비교할 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{document_id}/diff")
    public ResponseEntity<DocumentContentDiffResponse> compareVersions(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId,
            @RequestParam("from_version") long fromVersion,
            @RequestParam("to_version") long toVersion) {
        return ResponseEntity.ok(documentService.compareContentVersions(
                workspaceId, userId, documentId, fromVersion, toVersion));
    }

    @Operation(summary = "콘텐츠 버전 복원",
        description = "과거 버전을 새 버전으로 복원합니다(비파괴적). base_version이 현재 version과 일치할 때만 반영합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "복원 성공 또는 동일 본문 no-op",
            content = @Content(schema = @Schema(implementation = DocumentContentSaveResponse.class))),
        @ApiResponse(responseCode = "400", description = "편집 가능한 Markdown 문서가 아니거나 base_version 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "문서 소유자가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 해당 버전을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "문서 version 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{document_id}/versions/{version}/restore")
    public ResponseEntity<DocumentContentSaveResponse> restoreVersion(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId,
            @PathVariable("version") long version,
            @Valid @RequestBody DocumentContentRestoreRequest request) {
        return ResponseEntity.ok(
                documentService.restoreContentVersion(workspaceId, userId, documentId, version, request.baseVersion()));
    }

    private long parseBaseVersion(String baseVersion) {
        try {
            long parsed = Long.parseLong(baseVersion);
            if (parsed < 1) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new InvalidDocumentVersionException(
                    "base_version은 1 이상의 정수여야 합니다.");
        }
    }
}
