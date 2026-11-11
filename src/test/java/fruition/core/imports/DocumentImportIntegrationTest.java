package fruition.core.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.authz.WorkspaceSettingsClient;
import fruition.core.document.service.DocumentAssetContentService;
import fruition.core.upload.UploadLimitService;
import fruition.core.usage.service.CreditService;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Notion 가져오기 세션과 POST /documents/markdown 업로드 한도를 실제 Postgres·MinIO와 API로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DocumentImportIntegrationTest {
    static final long QUOTA = 5L * 1024 * 1024 * 1024;
    static final String HEADER = "X-Fruition-Import-Id";
    static final String INVALID_BODY = """
            {"error":{"code":"IMPORT_INVALID","message":"가져오기 세션이 없거나 만료·완료되었습니다. 가져오기를 다시 시작해 주세요."}}
            """;

    /** 편입 command가 Kafka로 나가 outbox에서 사라지지 않게 한다. */
    @MockBean fruition.core.document.service.AiCommandOutboxPublisher outboxPublisher;
    @MockBean WorkspaceAiModelClient workspaceAiModelClient;
    @MockBean WorkspaceSettingsClient workspaceSettings;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtTokenProvider jwt;
    @Autowired UploadLimitService limits;
    @Autowired DocumentAssetContentService assetContent;
    @Autowired CreditService credits;
    @Autowired fruition.core.document.service.DocumentService documentService;
    @Autowired io.minio.MinioClient minio;
    @Autowired fruition.shared.util.StorageProperties storage;
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();

    private String userId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_import_" + suffix;
        workspaceId = "ws_import_" + suffix;
        grant(workspaceId, userId);
        when(workspaceAiModelClient.get(anyString()))
                .thenReturn(new WorkspaceAiModelClient.AiModelSelection("gemini", "gemini-3.5-flash-lite"));
        // 워크스페이스가 업로드 자동 편입을 켜 두어도 가져온 문서는 편입하지 않아야 한다.
        when(workspaceSettings.autoIngestMarkdownOnUpload(anyString())).thenReturn(true);
        when(workspaceSettings.autoIngestPdfOnConvert(anyString())).thenReturn(true);
    }

    // ---------- 세션 생성

    @Test
    void create_returnsOpenSessionWithQuota() throws Exception {
        seedUsage(100);
        JsonNode body = json(createImport("{\"source\":\"notion\",\"item_count\":3,\"total_bytes\":10}"), 201);
        assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("import_id", "status", "expires_at", "max_items", "quota");
        assertThat(UUID.fromString(body.path("import_id").asText())).isNotNull();
        assertThat(body.path("status").asText()).isEqualTo("open");
        assertThat(body.path("max_items").isNull()).isTrue();  // 항목 상한 없음. 필드는 계약 유지용으로 null
        assertThat(body.path("quota").path("used_bytes").asLong()).isEqualTo(100);
        assertThat(body.path("quota").path("limit_bytes").asLong()).isEqualTo(QUOTA);
        Long hours = jdbc.queryForObject(
                "SELECT round(extract(epoch FROM expires_at - created_at) / 3600) FROM document_imports WHERE id = ?",
                Long.class, UUID.fromString(body.path("import_id").asText()));
        assertThat(hours).isEqualTo(24);
    }

    @Test
    void create_rejectsQuotaAndUnknownSource_butHasNoItemCap() throws Exception {
        createImport("{\"source\":\"notion\",\"item_count\":5000,\"total_bytes\":1}").andExpect(status().isCreated());
        createImport("{\"source\":\"evernote\",\"item_count\":1,\"total_bytes\":1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        createImport("{\"source\":\"notion\",\"item_count\":-1,\"total_bytes\":1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        seedUsage(QUOTA - 10);
        createImport("{\"source\":\"notion\",\"item_count\":1,\"total_bytes\":11}")
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error.code").value("STORAGE_QUOTA_EXCEEDED"));
        createImport("{\"source\":\"notion\",\"item_count\":1000,\"total_bytes\":10}").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_imports WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(2);
    }

    // ---------- 가져오기 쓰기

    @Test
    void importHeader_marksDocument_andDoesNotAutoIngest() throws Exception {
        String importId = openImport();

        String documentId = json(createMarkdown("가져온.md", importId), 201).path("id").asText();

        var row = jdbc.queryForMap("SELECT import_id, origin, status, pipeline_run_id FROM documents WHERE id = ?", documentId);
        assertThat(row.get("import_id")).isEqualTo(UUID.fromString(importId));
        assertThat(row.get("origin")).isEqualTo("notion_import");
        assertThat(row.get("status")).isEqualTo("completed");
        assertThat(row.get("pipeline_run_id")).isNull();
        assertThat(aiRuns()).isZero();
        assertThat(itemsUsed(importId)).isEqualTo(1);
    }

    @Test
    void importHeader_stillEnforcesStorageQuota() throws Exception {
        String importId = openImport();
        seedUsage(QUOTA - 3);
        createMarkdown("ok.md", importId, "abc").andExpect(status().isCreated());
        createMarkdown("over.md", importId, "x").andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("STORAGE_QUOTA_EXCEEDED"));
        assertThat(documentCount()).isEqualTo(1);
        assertThat(itemsUsed(importId)).isEqualTo(1);  // 거절된 쓰기의 항목 증가는 함께 롤백된다
    }

    @Test
    void importHasNoItemCap() throws Exception {
        String importId = openImport();
        jdbc.update("UPDATE document_imports SET items_used = 5000 WHERE id = ?", UUID.fromString(importId));
        createMarkdown("more.md", importId).andExpect(status().isCreated());
        assertThat(itemsUsed(importId)).isEqualTo(5001);
    }

    @Test
    void invalidExpiredForeignOrCompletedImport_isRejected_neverFallsBack() throws Exception {
        createMarkdown("a.md", "not-a-uuid").andExpect(status().isBadRequest()).andExpect(content().json(INVALID_BODY, true));
        createMarkdown("a.md", UUID.randomUUID().toString()).andExpect(status().isBadRequest())
                .andExpect(content().json(INVALID_BODY, true));

        String expired = openImport();
        jdbc.update("UPDATE document_imports SET expires_at = now() - interval '1 second' WHERE id = ?",
                UUID.fromString(expired));
        createMarkdown("a.md", expired).andExpect(status().isBadRequest()).andExpect(content().json(INVALID_BODY, true));
        createFolder("폴더", expired).andExpect(status().isBadRequest()).andExpect(content().json(INVALID_BODY, true));

        // 다른 사용자의 세션, 같은 사용자의 다른 워크스페이스 세션
        String otherUser = "user_other_" + UUID.randomUUID();
        grant(workspaceId, otherUser);
        String foreign = json(mockMvc.perform(post(imports()).header("Authorization", bearer(otherUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"notion\",\"item_count\":1,\"total_bytes\":1}")), 201).path("import_id").asText();
        createMarkdown("a.md", foreign).andExpect(status().isBadRequest()).andExpect(content().json(INVALID_BODY, true));
        String otherWorkspace = "ws_other_" + UUID.randomUUID();
        grant(otherWorkspace, userId);
        String elsewhere = json(mockMvc.perform(post("/api/workspaces/" + otherWorkspace + "/imports")
                .header("Authorization", bearer(userId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"notion\",\"item_count\":1,\"total_bytes\":1}")), 201).path("import_id").asText();
        createMarkdown("a.md", elsewhere).andExpect(status().isBadRequest()).andExpect(content().json(INVALID_BODY, true));

        assertThat(documentCount()).isZero();
        mockMvc.perform(get(imports() + "/" + foreign).header("Authorization", bearer(userId)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("IMPORT_NOT_FOUND"));
    }

    @Test
    void folderAndImageWrites_useImportSession() throws Exception {
        String importId = openImport();
        createFolder("가져온 폴더", importId).andExpect(status().isCreated());
        String imported = json(createMarkdown("이미지.md", importId), 201).path("id").asText();
        String normal = json(createMarkdown("일반.md", null), 201).path("id").asText();

        saveImage(imported, importId);
        assertThat(itemsUsed(importId)).isEqualTo(2);

        assertThatThrownBy(() -> saveImage(normal, importId))
                .isInstanceOfSatisfying(DocumentImportException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IMPORT_INVALID"));
        saveImage(normal, null);
        assertThat(itemsUsed(importId)).isEqualTo(2);

        // 이미지도 저장 공간 한도는 그대로 본다.
        seedUsage(QUOTA - limits.usedBytes(userId));
        assertThatThrownBy(() -> saveImage(imported, importId))
                .isInstanceOfSatisfying(fruition.core.upload.UploadLimitException.class,
                        e -> assertThat(e.getCode()).isEqualTo("STORAGE_QUOTA_EXCEEDED"));
        assertThat(itemsUsed(importId)).isEqualTo(2);
    }

    @Test
    void complete_closesSession_andGetReportsCreatedDocuments() throws Exception {
        String importId = openImport();
        String first = json(createMarkdown("1.md", importId), 201).path("id").asText();
        String second = json(createMarkdown("2.md", importId), 201).path("id").asText();
        createMarkdown("일반.md", null).andExpect(status().isCreated());

        complete(importId, "{\"created\":2,\"failed\":1,\"skipped\":3}").andExpect(status().isOk())
                .andExpect(content().json("{\"import_id\":\"" + importId + "\",\"status\":\"completed\",\"document_count\":2}", true));
        createMarkdown("3.md", importId).andExpect(status().isBadRequest()).andExpect(content().json(INVALID_BODY, true));
        complete(importId, "{\"created\":9,\"failed\":0,\"skipped\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.document_count").value(2));

        JsonNode body = json(mockMvc.perform(get(imports() + "/" + importId).header("Authorization", bearer(userId))), 200);
        assertThat(body.path("status").asText()).isEqualTo("completed");
        assertThat(body.path("document_count").asInt()).isEqualTo(2);
        assertThat(body.path("items_used").asInt()).isEqualTo(2);
        assertThat(body.path("created").asInt()).isEqualTo(2);
        assertThat(body.path("failed").asInt()).isEqualTo(1);
        assertThat(body.path("skipped").asInt()).isEqualTo(3);
        assertThat(objectMapper.convertValue(body.path("document_ids"), String[].class)).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void clientFlow_emptyCreateThenContentSave_retriesDoNotDuplicate() throws Exception {
        String importId = openImport();
        String key = "notion-" + "a".repeat(64);
        String body = "{\"display_name\":\"페이지.md\",\"folder_id\":null,\"markdown\":\"\"}";
        String documentId = json(createWithKey(importId, key, body), 201).path("id").asText();
        assertThat(json(createWithKey(importId, key, body), 201).path("id").asText()).isEqualTo(documentId);
        assertThat(documentCount()).isEqualTo(1);
        assertThat(itemsUsed(importId)).isEqualTo(1);  // 멱등 재요청은 다시 세지 않는다

        // 이미지 없는 본문 저장(base_revision + revision_write_id), 같은 write ID 재시도는 같은 결과
        String writeId = "notion-" + "b".repeat(64);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(multipart("/api/workspaces/" + workspaceId + "/documents/" + documentId + "/content")
                            .file(new MockMultipartFile("markdown", "", "text/plain", "# 본문".getBytes(StandardCharsets.UTF_8)))
                            .file(new MockMultipartFile("base_revision", "", "text/plain", "1".getBytes()))
                            .file(new MockMultipartFile("revision_write_id", "", "text/plain", writeId.getBytes()))
                            .with(request -> { request.setMethod("PUT"); return request; })
                            .header("Authorization", bearer(userId)).header(HEADER, importId))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.current_version").value(2));
        }
        // 이미지 포함 저장(metadata)도 같은 세션으로, 같은 첨부 ID 재시도는 asset을 다시 만들지 않는다
        UUID attachment = UUID.randomUUID();
        byte[] image = png();
        for (int i = 0; i < 2; i++) {
            String metadata = "{\"markdown\":\"![그림](attachment://" + attachment + ")\",\"base_version\":2}";
            mockMvc.perform(multipart("/api/workspaces/" + workspaceId + "/documents/" + documentId + "/content")
                            .file(new MockMultipartFile("metadata", "", "application/json", metadata.getBytes(StandardCharsets.UTF_8)))
                            .file(new MockMultipartFile("attachment_" + attachment, "image.png", "image/png", image))
                            .with(request -> { request.setMethod("PUT"); return request; })
                            .header("Authorization", bearer(userId)).header(HEADER, importId))
                    .andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_assets WHERE uploaded_by = ?", Integer.class, userId))
                .isEqualTo(1);
        assertThat(itemsUsed(importId)).isEqualTo(2);

        // 세션 밖 문서에 헤더를 붙인 본문 저장은 거절한다
        String normal = json(createMarkdown("일반.md", null), 201).path("id").asText();
        mockMvc.perform(multipart("/api/workspaces/" + workspaceId + "/documents/" + normal + "/content")
                        .file(new MockMultipartFile("markdown", "", "text/plain", "x".getBytes()))
                        .file(new MockMultipartFile("base_revision", "", "text/plain", "1".getBytes()))
                        .file(new MockMultipartFile("revision_write_id", "", "text/plain", "w".getBytes()))
                        .with(request -> { request.setMethod("PUT"); return request; })
                        .header("Authorization", bearer(userId)).header(HEADER, importId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("IMPORT_INVALID"));
    }

    @Test
    void corsPreflight_allowsImportHeader() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .options("/api/workspaces/" + workspaceId + "/documents/markdown")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key,x-fruition-import-id"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsStringIgnoringCase("x-fruition-import-id")));
    }

    // ---------- 편입

    @Test
    void ingestEstimate_sumsDocumentLengthReservations() throws Exception {
        String importId = openImport();
        createMarkdown("1.md", importId).andExpect(status().isCreated());
        createMarkdown("2.md", importId).andExpect(status().isCreated());
        String trashed = json(createMarkdown("3.md", importId), 201).path("id").asText();
        jdbc.update("UPDATE documents SET deleted_at = now() WHERE id = ?", trashed);

        JsonNode body = json(mockMvc.perform(get(imports() + "/" + importId + "/ingest-estimate")
                .header("Authorization", bearer(userId))), 200);
        assertThat(body.path("document_count").asInt()).isEqualTo(2);
        long estimate = jdbc.queryForList("SELECT byte_size FROM documents WHERE import_id=?::uuid AND deleted_at IS NULL",
                Long.class, importId).stream().mapToLong(credits::estimateDocument).sum();
        assertThat(body.path("estimated_credits").asLong()).isEqualTo(estimate);
        assertThat(body.path("estimated_cost_usd").decimalValue()).isEqualByComparingTo(
                BigDecimal.valueOf(credits.estimateUsdMicro(estimate)).movePointLeft(6));
    }

    @Test
    void ingest_enqueuesOnlyImportDocuments() throws Exception {
        String importId = openImport();
        String first = json(createMarkdown("1.md", importId), 201).path("id").asText();
        String second = json(createMarkdown("2.md", importId), 201).path("id").asText();
        String normal = json(createMarkdown("일반.md", null), 201).path("id").asText();

        ingest(importId, "{\"document_ids\":[\"" + first + "\",\"" + normal + "\"]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(aiRuns()).isZero();

        ingest(importId, "{\"document_ids\":[\"" + first + "\"]}").andExpect(status().isAccepted())
                .andExpect(content().json("{\"queued\":1,\"remaining\":1}", true));
        ingest(importId, null).andExpect(status().isAccepted())
                .andExpect(content().json("{\"queued\":1,\"remaining\":0}", true));
        ingest(importId, null).andExpect(status().isAccepted())
                .andExpect(content().json("{\"queued\":0,\"remaining\":0}", true));

        assertThat(pipelineRun(first)).isNotNull();
        assertThat(pipelineRun(second)).isNotNull();
        assertThat(pipelineRun(normal)).isNull();
        assertThat(aiRuns()).isEqualTo(2);
    }

    @Test
    void estimateAndIngest_workAfterComplete_andNoBodyMeansAllRemaining() throws Exception {
        String importId = openImport();
        String first = json(createMarkdown("1.md", importId), 201).path("id").asText();
        complete(importId, "{\"created\":1,\"failed\":0,\"skipped\":0}").andExpect(status().isOk());

        mockMvc.perform(get(imports() + "/" + importId + "/ingest-estimate").header("Authorization", bearer(userId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.document_count").value(1));
        mockMvc.perform(post(imports() + "/" + importId + "/ingest").header("Authorization", bearer(userId)))
                .andExpect(status().isAccepted()).andExpect(content().json("{\"queued\":1,\"remaining\":0}", true));
        assertThat(pipelineRun(first)).isNotNull();
    }

    @Test
    void ingest_withoutWikiIngestPermission_isForbidden() throws Exception {
        String member = "user_member_" + UUID.randomUUID();
        redis.opsForValue().set("authz:role:v2:" + workspaceId + ":" + member, "{\"role\":\"MEMBER\",\"permissions\":[]}");
        String importId = json(mockMvc.perform(post(imports()).header("Authorization", bearer(member))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"notion\",\"item_count\":1,\"total_bytes\":1}")), 201).path("import_id").asText();
        mockMvc.perform(post("/api/workspaces/" + workspaceId + "/documents/markdown")
                        .header("Authorization", bearer(member)).header("Idempotency-Key", UUID.randomUUID().toString())
                        .header(HEADER, importId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"display_name\":\"a.md\",\"markdown\":\"# a\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post(imports() + "/" + importId + "/ingest").header("Authorization", bearer(member)))
                .andExpect(status().isForbidden());
    }

    // ---------- PDF 직접 업로드

    @Test
    void importPdf_directUpload_marksDocument_countsItem_andReplaysWithSameKey() throws Exception {
        String importId = openImport();
        String key = UUID.randomUUID().toString();

        String documentId = json(uploadPdf("자료.pdf", importId, key), 201).path("id").asText();
        String replayId = json(uploadPdf("자료.pdf", importId, key), 201).path("id").asText();

        assertThat(replayId).isEqualTo(documentId);
        var row = jdbc.queryForMap("SELECT import_id, origin, status, pipeline_run_id, mime_type FROM documents WHERE id = ?",
                documentId);
        assertThat(row.get("import_id")).isEqualTo(UUID.fromString(importId));
        assertThat(row.get("origin")).isEqualTo("notion_import");
        assertThat(row.get("mime_type")).isEqualTo("application/pdf");
        assertThat(row.get("pipeline_run_id")).isNull();
        assertThat(aiRuns()).isZero();
        assertThat(itemsUsed(importId)).isEqualTo(1);  // 멱등 재요청은 다시 세지 않는다
        assertThat(documentCount()).isEqualTo(1);
    }

    @Test
    void importPdf_invalidOrMismatchedSession_isRejected_andQuotaStillApplies() throws Exception {
        String importId = openImport();
        String closed = openImport();
        complete(closed, "{}").andExpect(status().isOk());

        startPdf("a.pdf", 5, closed).andExpect(status().isBadRequest()).andExpect(content().json(INVALID_BODY, true));
        String importTicket = startPdfTicket("a.pdf", importId);
        put(partUrl(importTicket), pdf());
        completePdf(importTicket, null, UUID.randomUUID().toString())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("IMPORT_INVALID"));
        String plainTicket = json(startPdf("b.pdf", pdf().length, null), 200).path("ticket").asText();
        put(partUrl(plainTicket), pdf());
        completePdf(plainTicket, importId, UUID.randomUUID().toString())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("IMPORT_INVALID"));
        assertThat(documentCount()).isZero();

        startPdf("big.pdf", 100L * 1024 * 1024 + 1, importId).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
        seedUsage(QUOTA - 3);
        startPdf("c.pdf", 5, importId).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("STORAGE_QUOTA_EXCEEDED"));
    }

    @Test
    void importedPdf_isConverted_withoutAutoIngest_andJoinsImportIngestOnlyAfterConversion() throws Exception {
        String importId = openImport();
        createMarkdown("본문.md", importId).andExpect(status().isCreated());
        String pdfId = json(uploadPdf("자료.pdf", importId, UUID.randomUUID().toString()), 201).path("id").asText();
        String converted = json(mockMvc.perform(post("/api/workspaces/" + workspaceId + "/documents/" + pdfId
                        + "/convert-markdown").header("Authorization", bearer(userId))
                .header("Idempotency-Key", UUID.randomUUID().toString())), 202).path("id").asText();
        assertThat(jdbc.queryForObject("SELECT import_id FROM documents WHERE id = ?", UUID.class, converted))
                .isEqualTo(UUID.fromString(importId));

        // 변환 중: PDF 원본과 변환 중인 문서는 편입 대상이 아니다.
        JsonNode during = json(estimate(importId), 200);
        assertThat(during.path("document_count").asInt()).isEqualTo(1);
        assertThat(during.path("converting_count").asInt()).isEqualTo(1);

        finishConversion(converted);
        assertThat(jdbc.queryForObject("SELECT status FROM documents WHERE id = ?", String.class, converted))
                .isEqualTo("completed");
        assertThat(ingestRuns()).isZero();  // 자동 편입 설정이 켜져 있어도 가져온 변환본은 편입하지 않는다

        JsonNode after = json(estimate(importId), 200);
        assertThat(after.path("document_count").asInt()).isEqualTo(2);
        assertThat(after.path("converting_count").asInt()).isZero();
        ingest(importId, null).andExpect(status().isAccepted())
                .andExpect(content().json("{\"queued\":2,\"remaining\":0}", true));
        assertThat(pipelineRun(converted)).doesNotStartWith("convert:");
        assertThat(pipelineRun(pdfId)).isNull();
        assertThat(ingestRuns()).isEqualTo(2);
    }

    @Test
    void importedPdf_isNotConvertedUntilRequested_andConvertEndpointQueuesOnlyUnconvertedPdfs() throws Exception {
        String importId = openImport();
        createMarkdown("본문.md", importId).andExpect(status().isCreated());
        String first = json(uploadPdf("하나.pdf", importId, UUID.randomUUID().toString()), 201).path("id").asText();
        String second = json(uploadPdf("둘.pdf", importId, UUID.randomUUID().toString()), 201).path("id").asText();
        json(uploadPdf("일반.pdf", null, UUID.randomUUID().toString()), 201);
        complete(importId, "{}").andExpect(status().isOk());

        // 업로드만으로는 변환하지 않는다. 변환 예상은 변환 1건 예상치 × 아직 변환하지 않은 PDF 수다.
        assertThat(convertedFrom(first)).isZero();
        JsonNode before = json(estimate(importId), 200);
        assertThat(before.path("pdf_unconverted_count").asInt()).isEqualTo(2);
        assertThat(before.path("convert_estimated_credits").asLong()).isEqualTo(2 * credits.estimate("convert"));
        assertThat(before.path("convert_estimated_cost_usd").decimalValue()).isEqualByComparingTo(
                BigDecimal.valueOf(credits.estimateUsdMicro("convert") * 2).movePointLeft(6));
        assertThat(before.path("document_count").asInt()).isEqualTo(1);

        mockMvc.perform(post(imports() + "/" + importId + "/convert").header("Authorization", bearer(userId)))
                .andExpect(status().isAccepted()).andExpect(content().json("{\"queued\":2,\"remaining\":0}", true));
        mockMvc.perform(post(imports() + "/" + importId + "/convert").header("Authorization", bearer(userId)))
                .andExpect(status().isAccepted()).andExpect(content().json("{\"queued\":0,\"remaining\":0}", true));
        assertThat(convertedFrom(first)).isEqualTo(1);
        assertThat(convertedFrom(second)).isEqualTo(1);
        JsonNode after = json(estimate(importId), 200);
        assertThat(after.path("pdf_unconverted_count").asInt()).isZero();
        assertThat(after.path("converting_count").asInt()).isEqualTo(2);
    }

    @Test
    void convert_failedConversionCanBeRequestedAgain() throws Exception {
        String importId = openImport();
        String pdfId = json(uploadPdf("하나.pdf", importId, UUID.randomUUID().toString()), 201).path("id").asText();
        mockMvc.perform(post(imports() + "/" + importId + "/convert").header("Authorization", bearer(userId)))
                .andExpect(status().isAccepted());
        jdbc.update("UPDATE documents SET status = 'failed' WHERE source_document_id = ?", pdfId);
        assertThat(json(estimate(importId), 200).path("pdf_unconverted_count").asInt()).isEqualTo(1);
    }

    // ---------- 일반 POST /documents/markdown 업로드 한도

    @Test
    void markdownCreate_withoutImport_enforcesQuota_andHasNoDailyCount() throws Exception {
        // 예전 하루 개수 기록(V86 user_upload_events)이 많아도 막지 않는다. 하루 개수 한도는 없다.
        jdbc.update("""
                INSERT INTO user_upload_events (user_id, workspace_id, kind, byte_size)
                SELECT ?, ?, 'document', 1 FROM generate_series(1, 300)
                """, userId, workspaceId);
        createMarkdown("a.md", null, "12345").andExpect(status().isCreated());
        seedUsage(QUOTA - limits.usedBytes(userId));
        createMarkdown("b.md", null, "x").andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("STORAGE_QUOTA_EXCEEDED"));
        assertThat(documentCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_upload_events WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(300);  // 새 기록을 남기지 않는다
    }

    @Test
    void markdownCreate_idempotentReplay_isNotCountedAgain() throws Exception {
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/workspaces/" + workspaceId + "/documents/markdown")
                            .header("Authorization", bearer(userId)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"display_name\":\"a.md\",\"markdown\":\"# a\"}"))
                    .andExpect(status().isCreated());
        }
        assertThat(documentCount()).isEqualTo(1);
    }

    // ---------- PDF helpers

    private static byte[] pdf() {
        return "%PDF-1.4\n%test\n".getBytes(StandardCharsets.US_ASCII);
    }

    private ResultActions startPdf(String name, long size, String importId) throws Exception {
        var request = post("/api/workspaces/" + workspaceId + "/documents/uploads").header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"filename\":\"" + name + "\",\"size\":" + size + "}");
        if (importId != null) request = request.header(HEADER, importId);
        return mockMvc.perform(request);
    }

    private String startPdfTicket(String name, String importId) throws Exception {
        return json(startPdf(name, pdf().length, importId), 200).path("ticket").asText();
    }

    private String partUrl(String ticket) throws Exception {
        return json(mockMvc.perform(post("/api/workspaces/" + workspaceId + "/documents/uploads/parts")
                .header("Authorization", bearer(userId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticket\":\"" + ticket + "\",\"first_part\":1,\"count\":1}")), 200)
                .path("parts").get(0).path("url").asText();
    }

    private void put(String url, byte[] bytes) throws Exception {
        var response = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes))
                .header("Content-Type", "application/pdf").build(), java.net.http.HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private ResultActions completePdf(String ticket, String importId, String key) throws Exception {
        var request = post("/api/workspaces/" + workspaceId + "/documents/uploads/complete")
                .header("Authorization", bearer(userId)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"ticket\":\"" + ticket + "\"}");
        if (importId != null) request = request.header(HEADER, importId);
        return mockMvc.perform(request);
    }

    private ResultActions uploadPdf(String name, String importId, String key) throws Exception {
        String ticket = startPdfTicket(name, importId);
        put(partUrl(ticket), pdf());
        return completePdf(ticket, importId, key);
    }

    private ResultActions estimate(String importId) throws Exception {
        return mockMvc.perform(get(imports() + "/" + importId + "/ingest-estimate").header("Authorization", bearer(userId)));
    }

    /** converter worker의 1쪽짜리 결과를 저장하고 완료 이벤트를 반영한다. */
    private void finishConversion(String documentId) throws Exception {
        String resultKey = "tmp/convert/" + workspaceId + "/" + documentId + "/0.md";
        byte[] markdown = "<!-- page 1 -->\n\n# 변환 본문\n".getBytes(StandardCharsets.UTF_8);
        minio.putObject(io.minio.PutObjectArgs.builder().bucket(storage.getBucket()).object(resultKey)
                .stream(new java.io.ByteArrayInputStream(markdown), markdown.length, -1).build());
        var event = objectMapper.createObjectNode().put("run_id", "convert:" + documentId + ":0")
                .put("kind", "convert_batch").put("status", "succeeded");
        event.putObject("payload").put("page_start", 1).put("page_end", 1).put("total_pages", 1)
                .put("result_key", resultKey);
        documentService.applyConvertBatch(event);
    }

    private int convertedFrom(String pdfId) {
        return jdbc.queryForObject("SELECT count(*) FROM documents WHERE source_document_id = ? AND deleted_at IS NULL",
                Integer.class, pdfId);
    }

    private int ingestRuns() {
        return jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE user_id = ? AND kind <> 'convert'",
                Integer.class, userId);
    }

    // ---------- helpers

    private String openImport() throws Exception {
        return json(createImport("{\"source\":\"notion\",\"item_count\":10,\"total_bytes\":100}"), 201)
                .path("import_id").asText();
    }

    private ResultActions createImport(String body) throws Exception {
        return mockMvc.perform(post(imports()).header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions complete(String importId, String body) throws Exception {
        return mockMvc.perform(post(imports() + "/" + importId + "/complete").header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions ingest(String importId, String body) throws Exception {
        var request = post(imports() + "/" + importId + "/ingest").header("Authorization", bearer(userId));
        if (body != null) request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(request);
    }

    private ResultActions createMarkdown(String name, String importId) throws Exception {
        return createMarkdown(name, importId, "# " + name);
    }

    private ResultActions createMarkdown(String name, String importId, String markdown) throws Exception {
        var request = post("/api/workspaces/" + workspaceId + "/documents/markdown")
                .header("Authorization", bearer(userId)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("display_name", name, "markdown", markdown)));
        if (importId != null) request = request.header(HEADER, importId);
        return mockMvc.perform(request);
    }

    private ResultActions createWithKey(String importId, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + workspaceId + "/documents/markdown")
                .header("Authorization", bearer(userId)).header("Idempotency-Key", key).header(HEADER, importId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions createFolder(String name, String importId) throws Exception {
        var request = post("/api/workspaces/" + workspaceId + "/folders")
                .header("Authorization", bearer(userId)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}");
        if (importId != null) request = request.header(HEADER, importId);
        return mockMvc.perform(request);
    }

    private void saveImage(String documentId, String importId) throws Exception {
        long base = jdbc.queryForObject("SELECT revision FROM document_edit_states WHERE document_id = ?",
                Long.class, documentId);
        UUID placeholder = UUID.randomUUID();
        String metadata = "{\"markdown\":\"![그림](attachment://" + placeholder + ")\",\"base_version\":" + base + "}";
        MultiValueMap<String, MultipartFile> parts = new LinkedMultiValueMap<>();
        parts.add("attachment_" + placeholder,
                new MockMultipartFile("attachment_" + placeholder, "image.png", "image/png", png()));
        assetContent.save(workspaceId, userId, documentId, metadata, parts, null, importId);
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, (int) (Math.random() * 0xFFFFFF));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private void grant(String workspace, String user) {
        redis.opsForValue().set("authz:role:v2:" + workspace + ":" + user, "{\"role\":\"OWNER\",\"permissions\":[]}");
    }

    private void seedUsage(long bytes) {
        jdbc.update("""
                INSERT INTO document_assets (id, workspace_id, uploaded_by, original_filename, content_type, byte_size,
                                             width, height, content_hash, storage_key, created_at)
                VALUES (?, ?, ?, 'seed.png', 'image/png', ?, 1, 1, ?, ?, now())
                """, UUID.randomUUID(), workspaceId, userId, bytes, "0".repeat(64), "seed/" + UUID.randomUUID());
    }

    private int itemsUsed(String importId) {
        return jdbc.queryForObject("SELECT items_used FROM document_imports WHERE id = ?", Integer.class,
                UUID.fromString(importId));
    }

    private int documentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM documents WHERE user_id = ?", Integer.class, userId);
    }

    private int aiRuns() {
        return jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE user_id = ?", Integer.class, userId);
    }

    private String pipelineRun(String documentId) {
        return jdbc.queryForObject("SELECT pipeline_run_id FROM documents WHERE id = ?", String.class, documentId);
    }

    private String imports() {
        return "/api/workspaces/" + workspaceId + "/imports";
    }

    private String bearer(String user) {
        return "Bearer " + jwt.generateAccessToken(user, user + "@example.com");
    }

    private JsonNode json(ResultActions actions, int status) throws Exception {
        return objectMapper.readTree(actions.andExpect(status().is(status)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }
}
