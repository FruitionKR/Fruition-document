package fruition.core.document.controller;

import fruition.core.document.dto.DocumentBlockResponse;
import fruition.core.document.dto.DocumentBlocksResponse;
import fruition.core.document.dto.DocumentUploadResponse;
import fruition.core.document.dto.DocumentContentSaveResponse;
import fruition.core.document.dto.DocumentContentDiffResponse;
import fruition.core.document.dto.DocumentDuplicateResponse;
import fruition.core.document.dto.DocumentExportResult;
import fruition.core.document.dto.DocumentLifecycleRequest;
import fruition.core.document.dto.DocumentLifecycleResponse;
import fruition.core.document.dto.DocumentRenameRequest;
import fruition.core.document.dto.DocumentRenameResponse;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.exception.InvalidDocumentConvertRequestException;
import fruition.core.document.exception.MarkdownDiffTooLargeException;
import fruition.core.document.service.DocumentService;
import fruition.core.document.service.DocumentExportService;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import fruition.core.config.SecurityConfig;
import fruition.core.CoreExceptionHandler;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.document.service.DocumentAssetContentService;
import fruition.shared.idempotency.IdempotencyInProgressException;
import fruition.shared.idempotency.IdempotencyConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.mock.web.MockMultipartFile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(DocumentController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class DocumentControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean DocumentService documentService;
    @MockBean DocumentExportService documentExportService;
    @MockBean DocumentAssetContentService documentAssetContentService;

    private String bearerToken() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void duplicateUploadReturns409EvenWhenStorageWrapsTheFailure(boolean wrapped) throws Exception {
        var violation = new org.hibernate.exception.ConstraintViolationException(
                "duplicate", new java.sql.SQLException("duplicate", "23505"), "uq_documents_active_name");
        RuntimeException failure = new org.springframework.dao.DataIntegrityViolationException("write failed", violation);
        if (wrapped) failure = new fruition.core.document.exception.DocumentUploadException("upload failed", failure);
        when(documentService.upload(eq(WORKSPACE_ID), eq(USER_ID), any(), any(), any())).thenThrow(failure);

        mockMvc.perform(multipart("/api/workspaces/" + WORKSPACE_ID + "/documents")
                        .file(new MockMultipartFile("file", "보고서.md", "text/markdown", "본문".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "duplicate-test"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_NAME"))
                .andExpect(jsonPath("$.error.message").value(containsString("다른 이름을 사용")));
    }

    @Test
    void getBlocks_existingDocument_returnsDocumentIdAndBlocksInOrder() throws Exception {
        DocumentBlocksResponse response = new DocumentBlocksResponse("doc_1f9a74af", List.of(
                new DocumentBlockResponse("B0005", "원본 문서의 다섯 번째 block 본문"),
                new DocumentBlockResponse("B0006", "원본 문서의 여섯 번째 block 본문")
        ));
        when(documentService.blocks(WORKSPACE_ID, USER_ID, "doc_1f9a74af")).thenReturn(response);

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/documents/doc_1f9a74af/blocks")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document_id").value("doc_1f9a74af"))
                .andExpect(jsonPath("$.blocks[0].block_id").value("B0005"))
                .andExpect(jsonPath("$.blocks[0].text").value("원본 문서의 다섯 번째 block 본문"))
                .andExpect(jsonPath("$.blocks[1].block_id").value("B0006"));
    }

    @Test
    void export_returnsUtf8MarkdownWithEncodedKoreanFilename() throws Exception {
        byte[] bytes = "# 최신 본문\n한글".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(documentExportService.exportMarkdown(
                WORKSPACE_ID, USER_ID, "doc_export"))
                .thenReturn(DocumentExportResult.markdown("회의 결과.md", bytes));

        mockMvc.perform(get(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_export/export")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/markdown;charset=UTF-8"))
                .andExpect(content().bytes(bytes))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION, containsString("filename*=")));
    }

    @Test
    void export_withImagesReturnsZipContentType() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3};
        when(documentExportService.exportMarkdown(WORKSPACE_ID, USER_ID, "doc_zip"))
                .thenReturn(DocumentExportResult.zip(
                        "이미지 문서.zip", bytes.length, new java.io.ByteArrayInputStream(bytes)));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/documents/doc_zip/export")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void getBlocks_unknownDocument_returns404() throws Exception {
        when(documentService.blocks(WORKSPACE_ID, USER_ID, "doc_unknown"))
                .thenThrow(new DocumentNotFoundException("doc_unknown"));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/documents/doc_unknown/blocks")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void getBlocks_notOwnedWorkspace_returns404() throws Exception {
        when(documentService.blocks(any(), any(), any()))
                .thenThrow(new WorkspaceNotFoundException(WORKSPACE_ID));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/documents/doc_1f9a74af/blocks")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));
    }

    @Test
    void getBlocks_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/documents/doc_1f9a74af/blocks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_withQuery_passesFilenameSearchQuery() throws Exception {
        when(documentService.findAll(WORKSPACE_ID, USER_ID, "보고서"))
                .thenReturn(new fruition.core.document.dto.DocumentListResponse(List.of()));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/documents")
                        .queryParam("query", "보고서")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").isArray());

        verify(documentService).findAll(WORKSPACE_ID, USER_ID, "보고서");
    }

    @Test
    void createMarkdown_passesIdempotencyKeyAndReturnsCreatedDocument() throws Exception {
        DocumentUploadResponse response = new DocumentUploadResponse(
                "doc_created",
                "새 문서.md",
                "text/markdown",
                0,
                DocumentStatus.completed,
                null,
                Instant.now(),
                true,
                1,
                DocumentRole.EDITABLE
        );
        when(documentService.createMarkdown(
                eq(WORKSPACE_ID), eq(USER_ID), eq("create-key"), any(MarkdownDocumentCreateRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/documents/markdown")
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "create-key")
                        .contentType("application/json")
                        .content("""
                                {"display_name":"새 문서","markdown":""}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("doc_created"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.current_version").value(1))
                .andExpect(jsonPath("$.source_uri").doesNotExist());

        verify(documentService).createMarkdown(
                eq(WORKSPACE_ID), eq(USER_ID), eq("create-key"), any(MarkdownDocumentCreateRequest.class));
    }

    @Test
    void upload_passesFolderIdToService() throws Exception {
        java.util.UUID folderId = java.util.UUID.fromString("55555555-5555-5555-5555-555555555555");
        MockMultipartFile file = new MockMultipartFile(
                "file", "노트.md", "text/markdown",
                "# 본문".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DocumentUploadResponse response = new DocumentUploadResponse(
                "doc_uploaded", "노트.md", "text/markdown", 0, DocumentStatus.completed,
                null, Instant.now(), true, 1, DocumentRole.EDITABLE);
        when(documentService.upload(eq(WORKSPACE_ID), eq(USER_ID), eq("up-key"), eq(folderId), any()))
                .thenReturn(response);

        mockMvc.perform(multipart("/api/workspaces/" + WORKSPACE_ID + "/documents")
                        .file(file)
                        .param("folder_id", folderId.toString())
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "up-key"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("doc_uploaded"));

        verify(documentService).upload(eq(WORKSPACE_ID), eq(USER_ID), eq("up-key"), eq(folderId), any());
    }

    @Test
    void upload_inProgressReturns409WithIdempotencyCode() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "노트.md", "text/markdown",
                "# 본문".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(documentService.upload(eq(WORKSPACE_ID), eq(USER_ID), eq("same-key"), eq(null), any()))
                .thenThrow(new IdempotencyInProgressException("같은 요청이 처리 중입니다."));

        mockMvc.perform(multipart("/api/workspaces/" + WORKSPACE_ID + "/documents")
                        .file(file)
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "same-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_IN_PROGRESS"));
    }

    @Test
    void upload_conflictReturnsCurrentIdempotencyContractCode() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "노트.md", "text/markdown",
                "# 본문".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(documentService.upload(eq(WORKSPACE_ID), eq(USER_ID), eq("same-key"), eq(null), any()))
                .thenThrow(new IdempotencyConflictException("다른 요청입니다."));

        mockMvc.perform(multipart("/api/workspaces/" + WORKSPACE_ID + "/documents")
                        .file(file)
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "same-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void duplicate_passesIdempotencyKeyAndReturnsCreatedDocument() throws Exception {
        when(documentService.duplicate(
                WORKSPACE_ID, USER_ID, "doc_source", "duplicate-key"))
                .thenReturn(new DocumentDuplicateResponse(
                        "doc_copy",
                        "보고서 복사본.md",
                        "보고서 복사본",
                        "text/markdown",
                        12,
                        1,
                        java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        "doc_source",
                        4
                ));

        mockMvc.perform(post(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_source/duplicate")
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "duplicate-key"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("doc_copy"))
                .andExpect(jsonPath("$.filename").value("보고서 복사본.md"))
                .andExpect(jsonPath("$.display_name").value("보고서 복사본"))
                .andExpect(jsonPath("$.current_version").value(1))
                .andExpect(jsonPath("$.folder_id").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.source_document_id").value("doc_source"))
                .andExpect(jsonPath("$.sort_order").value(4));

        verify(documentService).duplicate(
                WORKSPACE_ID, USER_ID, "doc_source", "duplicate-key");
    }

    @Test
    void delete_passesBaseVersionAndIdempotencyKey() throws Exception {
        when(documentService.delete(
                eq(WORKSPACE_ID),
                eq(USER_ID),
                eq("doc_delete"),
                eq("delete-key"),
                any(DocumentLifecycleRequest.class)
        )).thenReturn(new DocumentLifecycleResponse(
                "doc_delete", 2, true, Instant.now(), 3));

        mockMvc.perform(delete(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_delete")
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "delete-key")
                        .contentType("application/json")
                        .content("""
                                {"base_version":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_version").value(2))
                .andExpect(jsonPath("$.deleted").value(true));
    }

    @Test
    void restore_passesBaseVersionAndIdempotencyKey() throws Exception {
        when(documentService.restore(
                eq(WORKSPACE_ID),
                eq(USER_ID),
                eq("doc_restore"),
                eq("restore-key"),
                any(DocumentLifecycleRequest.class)
        )).thenReturn(new DocumentLifecycleResponse(
                "doc_restore", 3, false, null, 5));

        mockMvc.perform(post(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_restore/restore")
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "restore-key")
                        .contentType("application/json")
                        .content("""
                                {"base_version":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_version").value(3))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.sort_order").value(5));
    }

    @Test
    void saveContent_multipartPassesMarkdownAndBaseVersion() throws Exception {
        Instant updatedAt = Instant.now();
        when(documentService.saveContent(
                WORKSPACE_ID, USER_ID, "doc_edit", "# 변경\n", 3L, "write_1", null, null))
                .thenReturn(new DocumentContentSaveResponse(
                        "doc_edit", 4, "a".repeat(64), updatedAt, true));
        MockMultipartFile markdown = new MockMultipartFile(
                "markdown", "", "text/plain",
                "# 변경\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockMultipartFile baseRevision = new MockMultipartFile(
                "base_revision", "", "text/plain",
                "3".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockMultipartFile revisionWriteId = new MockMultipartFile(
                "revision_write_id", "", "text/plain",
                "write_1".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(multipart(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_edit/content")
                        .file(markdown)
                        .file(baseRevision)
                        .file(revisionWriteId)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document_id").value("doc_edit"))
                .andExpect(jsonPath("$.current_version").value(4))
                .andExpect(jsonPath("$.changed").value(true));

        verify(documentService).saveContent(
                WORKSPACE_ID, USER_ID, "doc_edit", "# 변경\n", 3L, "write_1", null, null);
    }

    @Test
    void saveContent_invalidBaseVersion_returns400() throws Exception {
        MockMultipartFile markdown = new MockMultipartFile(
                "markdown", "", "text/plain",
                "# 본문\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockMultipartFile baseRevision = new MockMultipartFile(
                "base_revision", "", "text/plain",
                "invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockMultipartFile revisionWriteId = new MockMultipartFile(
                "revision_write_id", "", "text/plain",
                "write_1".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(multipart(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_edit/content")
                        .file(markdown)
                        .file(baseRevision)
                        .file(revisionWriteId)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .header("Authorization", bearerToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_DOCUMENT_VERSION"));
    }

    @Test
    void saveContent_metadataMultipartDelegatesAssetSave() throws Exception {
        UUID attachmentId = UUID.randomUUID();
        String metadataJson = """
                {"markdown":"![](attachment://%s)","base_version":3}
                """.formatted(attachmentId);
        when(documentAssetContentService.save(
                eq(WORKSPACE_ID), eq(USER_ID), eq("doc_edit"), eq(metadataJson), any(),
                eq("operation_1")))
                .thenReturn(new DocumentContentSaveResponse(
                        "doc_edit", 4, "a".repeat(64), Instant.now(), true,
                        "![](/api/workspaces/" + WORKSPACE_ID + "/assets/" + UUID.randomUUID() + "/content)",
                        List.of()));

        mockMvc.perform(multipart(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_edit/content")
                        .file(new MockMultipartFile(
                                "metadata", "", "application/json", metadataJson.getBytes()))
                        .file(new MockMultipartFile(
                                "attachment_" + attachmentId, "image.png", "image/png", new byte[]{1}))
                        .file(new MockMultipartFile(
                                "apply_operation_id", "", "text/plain", "operation_1".getBytes()))
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_version").value(4))
                .andExpect(jsonPath("$.markdown").isString());

        verify(documentAssetContentService).save(
                eq(WORKSPACE_ID), eq(USER_ID), eq("doc_edit"), eq(metadataJson), any(),
                eq("operation_1"));
    }

    @Test
    void saveContent_metadataForwardsUnknownUploadedFileForContractValidation() throws Exception {
        String metadataJson = """
                {"markdown":"# 본문","base_version":3}
                """;
        when(documentAssetContentService.save(
                eq(WORKSPACE_ID), eq(USER_ID), eq("doc_edit"), eq(metadataJson), any(), eq(null)))
                .thenReturn(new DocumentContentSaveResponse(
                        "doc_edit", 3, "a".repeat(64), Instant.now(), false, "# 본문", List.of()));

        mockMvc.perform(multipart(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_edit/content")
                        .file(new MockMultipartFile(
                                "metadata", "", "application/json", metadataJson.getBytes()))
                        .file(new MockMultipartFile(
                                "unexpected_file", "image.png", "image/png", new byte[]{1}))
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk());

        var files = org.mockito.ArgumentCaptor.forClass(org.springframework.util.MultiValueMap.class);
        verify(documentAssetContentService).save(
                eq(WORKSPACE_ID), eq(USER_ID), eq("doc_edit"), eq(metadataJson), files.capture(), eq(null));
        assertThat(files.getValue()).containsKey("unexpected_file");
        assertThat(files.getValue()).doesNotContainKey("metadata");
    }

    @Test
    void compareVersions_returnsGitHubStyleDiff() throws Exception {
        DocumentContentDiffResponse response = new DocumentContentDiffResponse(
                "doc_edit", 1, 2, 1, 1,
                List.of(new DocumentContentDiffResponse.Hunk(
                        1, 1, 1, 1,
                        List.of(
                                new DocumentContentDiffResponse.Line(
                                        DocumentContentDiffResponse.Type.DELETE, 1, null, "기존"),
                                new DocumentContentDiffResponse.Line(
                                        DocumentContentDiffResponse.Type.ADD, null, 1, "변경")))));
        when(documentService.compareContentVersions(
                WORKSPACE_ID, USER_ID, "doc_edit", 1L, 2L)).thenReturn(response);

        mockMvc.perform(get(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_edit/diff")
                        .param("from_version", "1")
                        .param("to_version", "2")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from_version").value(1))
                .andExpect(jsonPath("$.to_version").value(2))
                .andExpect(jsonPath("$.additions").value(1))
                .andExpect(jsonPath("$.deletions").value(1))
                .andExpect(jsonPath("$.hunks[0].lines[0].type").value("DELETE"))
                .andExpect(jsonPath("$.hunks[0].lines[1].type").value("ADD"));
    }

    @Test
    void compareVersions_tooLargeDiffReturns422() throws Exception {
        when(documentService.compareContentVersions(
                WORKSPACE_ID, USER_ID, "doc_edit", 1L, 2L))
                .thenThrow(new MarkdownDiffTooLargeException(
                        "두 문서의 차이가 너무 커서 안전하게 비교할 수 없습니다."));

        mockMvc.perform(get(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_edit/diff")
                        .param("from_version", "1")
                        .param("to_version", "2")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("MARKDOWN_DIFF_TOO_LARGE"));
    }

    @Test
    void convertMarkdown_passesIdempotencyKeyAndReturns202WithPlaceholder() throws Exception {
        DocumentUploadResponse response = new DocumentUploadResponse(
                "doc_placeholder",
                "보고서.md",
                "text/markdown",
                15,
                DocumentStatus.processing,
                null,
                Instant.now(),
                true,
                1,
                DocumentRole.EDITABLE
        );
        when(documentService.convertToMarkdown(WORKSPACE_ID, USER_ID, "doc_pdf", "convert-key"))
                .thenReturn(response);

        mockMvc.perform(post(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_pdf/convert-markdown")
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "convert-key"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("doc_placeholder"))
                .andExpect(jsonPath("$.filename").value("보고서.md"))
                .andExpect(jsonPath("$.status").value("processing"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.document_role").value("EDITABLE"));

        verify(documentService).convertToMarkdown(WORKSPACE_ID, USER_ID, "doc_pdf", "convert-key");
    }

    @Test
    void convertMarkdown_unknownDocument_returns404() throws Exception {
        when(documentService.convertToMarkdown(WORKSPACE_ID, USER_ID, "doc_unknown", "convert-key"))
                .thenThrow(new DocumentNotFoundException("doc_unknown"));

        mockMvc.perform(post(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_unknown/convert-markdown")
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "convert-key"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void convertMarkdown_nonPdfDocument_returns400() throws Exception {
        when(documentService.convertToMarkdown(WORKSPACE_ID, USER_ID, "doc_md", "convert-key"))
                .thenThrow(new InvalidDocumentConvertRequestException(
                        "PDF 원본 문서만 Markdown으로 변환할 수 있습니다."));

        mockMvc.perform(post(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_md/convert-markdown")
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "convert-key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_DOCUMENT_CONVERT_REQUEST"));
    }

    @Test
    void rename_usesDisplayNameAndBaseVersion() throws Exception {
        Instant updatedAt = Instant.now();
        when(documentService.rename(
                eq(WORKSPACE_ID), eq(USER_ID), eq("doc_edit"), any(DocumentRenameRequest.class)))
                .thenReturn(new DocumentRenameResponse(
                        "doc_edit", "새 제목.md", "새 제목", 2, updatedAt, true));

        mockMvc.perform(patch(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_edit/rename")
                        .header("Authorization", bearerToken())
                        .contentType("application/json")
                        .content("""
                                {"display_name":"새 제목","base_version":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("새 제목.md"))
                .andExpect(jsonPath("$.display_name").value("새 제목"))
                .andExpect(jsonPath("$.current_version").value(2))
                .andExpect(jsonPath("$.changed").value(true));
    }
}
