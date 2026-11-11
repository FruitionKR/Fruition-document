package fruition.core.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.authz.WorkspaceSettingsClient;
import fruition.core.document.service.DocumentDirectUploadService;
import fruition.core.document.service.McpDocumentDataGateway;
import fruition.core.mcp.McpDocumentUploadTools;
import fruition.core.mcp.McpIdentity;
import fruition.core.mcp.McpToolException;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 웹 직접 업로드 API(start → parts → complete)의 PDF·Markdown·txt와, 같은 서비스를 감싼 MCP 업로드 도구를 실제 Postgres·MinIO로
 * 확인한다. MCP 업로드는 워크스페이스 자동 편입이 켜져 있어도 서버 편입을 하지 않는다(ADR 0048).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DocumentDirectUploadIntegrationTest {
    static final byte[] PDF = "%PDF-1.4\n%test\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] MARKDOWN = "# 제목\n\n본문\n".getBytes(StandardCharsets.UTF_8);

    /** 편입 command가 Kafka로 나가지 않게 한다. */
    @MockBean fruition.core.document.service.AiCommandOutboxPublisher outboxPublisher;
    @MockBean WorkspaceAiModelClient workspaceAiModelClient;
    @MockBean WorkspaceSettingsClient workspaceSettings;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtTokenProvider jwt;
    @Autowired DocumentDirectUploadService uploads;
    @Autowired fruition.core.document.repository.DocumentRepository documentRepository;
    @Autowired fruition.core.document.repository.FolderRepository folderRepository;
    @Autowired fruition.core.document.service.DocumentAccessPolicy accessPolicy;
    @Autowired fruition.core.document.repository.PostgresDocumentEditStore editStore;
    @Autowired fruition.core.document.service.DocumentEditStateInitializer editStateInitializer;
    @Autowired fruition.core.document.repository.ConvertCommandOutbox convertOutbox;
    private final HttpClient http = HttpClient.newHttpClient();
    private McpDocumentUploadTools tools;
    private String userId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_direct_" + suffix;
        workspaceId = "ws_direct_" + suffix;
        grant(userId);
        when(workspaceAiModelClient.get(anyString()))
                .thenReturn(new WorkspaceAiModelClient.AiModelSelection("gemini", "gemini-3.5-flash-lite"));
        // 업로드 자동 편입을 켜 둔다: 웹 Markdown은 편입되고 MCP 업로드는 편입되지 않아야 한다.
        when(workspaceSettings.autoIngestMarkdownOnUpload(anyString())).thenReturn(true);
        tools = new McpDocumentUploadTools(uploads, new McpDocumentDataGateway(documentRepository, folderRepository,
                accessPolicy, editStore, editStateInitializer, convertOutbox), objectMapper);
    }

    // ---------- 웹 API

    @Test
    void web_markdownAndTxt_becomeEditableDocuments_withSameAutoIngestAsRegularUpload() throws Exception {
        JsonNode md = json(webUpload("노트.md", MARKDOWN), 201);
        JsonNode txt = json(webUpload("메모.txt", MARKDOWN), 201);

        assertThat(md.path("mime_type").asText()).isEqualTo("text/markdown");
        assertThat(md.path("editable").asBoolean()).isTrue();
        assertThat(md.path("wiki_ingest").path("status").asText()).isEqualTo("QUEUED");
        assertThat(txt.path("filename").asText()).isEqualTo("메모.md");
        assertThat(txt.path("mime_type").asText()).isEqualTo("text/markdown");
        assertThat(body(md.path("id").asText())).isEqualTo("# 제목\n\n본문\n");
        assertThat(ingestRuns()).isEqualTo(2);
    }

    @Test
    void web_pdf_behavesAsBefore() throws Exception {
        JsonNode pdf = json(webUpload("자료.pdf", PDF), 201);

        assertThat(pdf.path("mime_type").asText()).isEqualTo("application/pdf");
        assertThat(pdf.path("wiki_ingest").path("status").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(ingestRuns()).isZero();
    }

    @Test
    void web_rejectsUnsupportedTypeOversizedMarkdownAndInvalidUtf8() throws Exception {
        webStart("a.docx", 5).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_FILE_TYPE"));
        webStart("big.md", 5L * 1024 * 1024 + 1).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("MARKDOWN_CONTENT_TOO_LARGE"));
        byte[] invalid = {(byte) 0xC3, (byte) 0x28};
        webUpload("bad.md", invalid).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MARKDOWN_CONTENT"));
        assertThat(documentCount()).isZero();
    }

    @Test
    void web_foreignTicketIsRejected() throws Exception {
        String ticket = json(webStart("a.md", MARKDOWN.length), 200).path("ticket").asText();
        put(webPartUrl(ticket), MARKDOWN);
        String other = "user_other_" + UUID.randomUUID().toString().substring(0, 8);
        grant(other);
        mockMvc.perform(post(uploadsPath() + "/complete").header("Authorization", bearer(other))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ticket\":\"" + ticket + "\"}"))
                .andExpect(status().isForbidden());
        assertThat(documentCount()).isZero();
    }

    // ---------- MCP 도구

    @Test
    void mcp_markdownTxtAndPdf_areCreatedOnComplete_withoutServerAutoIngest() throws Exception {
        Map<String, Object> md = mcpUpload("노트.md", MARKDOWN);
        Map<String, Object> txt = mcpUpload("메모.txt", MARKDOWN);
        Map<String, Object> pdf = mcpUpload("자료.pdf", PDF);

        assertThat(md.get("mime_type")).isEqualTo("text/markdown");
        assertThat(txt.get("filename")).isEqualTo("메모.md");
        assertThat(pdf.get("mime_type")).isEqualTo("application/pdf");
        for (Map<String, Object> doc : java.util.List.of(md, txt, pdf)) {
            // MCP 업로드는 서버 자동 편입 판정을 하지 않는다(가져오기와 같은 분기).
            assertThat(((Map<?, ?>) doc.get("wiki_ingest")).get("status")).isEqualTo("NOT_APPLICABLE");
            String id = (String) doc.get("id");
            assertThat(jdbc.queryForObject("SELECT origin FROM documents WHERE id = ?", String.class, id)).isEqualTo("upload");
            assertThat(jdbc.queryForObject("SELECT pipeline_run_id FROM documents WHERE id = ?", String.class, id)).isNull();
            assertThat(doc.get("content_hash")).isEqualTo("sha256:"
                    + jdbc.queryForObject("SELECT content_hash FROM documents WHERE id = ?", String.class, id));
        }
        assertThat((String) pdf.get("next_step")).contains("start_pdf_conversion");
        assertThat((String) md.get("next_step")).contains("start_ingest");
        assertThat(body((String) md.get("id"))).isEqualTo("# 제목\n\n본문\n");
        assertThat(ingestRuns()).isZero();
    }

    @Test
    void mcp_rejectionsUseStableCodes() throws Exception {
        assertCode(() -> tools.invoke(identity(userId), "start_document_upload",
                startArgs("a.docx", 5)), "UNSUPPORTED_FILE_TYPE");
        assertCode(() -> tools.invoke(identity(userId), "start_document_upload",
                startArgs("big.md", 5L * 1024 * 1024 + 1)), "MARKDOWN_CONTENT_TOO_LARGE");
        assertCode(() -> tools.invoke(identity(userId), "start_document_upload",
                startArgs("big.pdf", 100L * 1024 * 1024 + 1)), "FILE_TOO_LARGE");
        assertCode(() -> mcpUpload("bad.md", new byte[]{(byte) 0xC3, (byte) 0x28}), "INVALID_MARKDOWN_CONTENT");

        String ticket = mcpStart("a.md", MARKDOWN.length);
        put(mcpPartUrl(ticket), MARKDOWN);
        String other = "user_other_" + UUID.randomUUID().toString().substring(0, 8);
        grant(other);
        assertCode(() -> tools.invoke(identity(other), "complete_document_upload",
                Map.of("idempotency_key", UUID.randomUUID().toString(), "ticket", ticket)), "HANDLE_NOT_FOUND");
        assertThatThrownBy(() -> tools.invoke(identity(userId), "start_document_upload",
                Map.of("filename", "a.md", "size", 1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(documentCount()).isZero();
    }

    // ---------- helpers

    private Map<String, Object> mcpUpload(String name, byte[] bytes) throws Exception {
        String ticket = mcpStart(name, bytes.length);
        put(mcpPartUrl(ticket), bytes);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tools.invoke(identity(userId), "complete_document_upload",
                Map.of("idempotency_key", UUID.randomUUID().toString(), "ticket", ticket));
        return out;
    }

    @SuppressWarnings("unchecked")
    private String mcpStart(String name, long size) {
        var out = (Map<String, Object>) tools.invoke(identity(userId), "start_document_upload", startArgs(name, size));
        assertThat(out.get("part_count")).isEqualTo(1);
        return (String) out.get("ticket");
    }

    @SuppressWarnings("unchecked")
    private String mcpPartUrl(String ticket) {
        var out = (Map<String, Object>) tools.invoke(identity(userId), "get_upload_part_urls",
                Map.of("ticket", ticket, "first_part", 1, "count", 1));
        return (String) ((java.util.List<Map<String, Object>>) out.get("parts")).get(0).get("url");
    }

    private static Map<String, Object> startArgs(String name, long size) {
        Map<String, Object> args = new HashMap<>();
        args.put("filename", name);
        args.put("size", size);
        args.put("folder_id", null);
        return args;
    }

    private McpIdentity identity(String user) {
        return new McpIdentity(user, workspaceId, "https://client.example/client.json", UUID.randomUUID().toString(), 1,
                Set.of("write_propose"), "OWNER", Set.of());
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(McpToolException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private ResultActions webStart(String name, long size) throws Exception {
        return mockMvc.perform(post(uploadsPath()).header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"filename\":\"" + name + "\",\"size\":" + size + "}"));
    }

    private String webPartUrl(String ticket) throws Exception {
        return json(mockMvc.perform(post(uploadsPath() + "/parts").header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticket\":\"" + ticket + "\",\"first_part\":1,\"count\":1}")), 200)
                .path("parts").get(0).path("url").asText();
    }

    private ResultActions webUpload(String name, byte[] bytes) throws Exception {
        String ticket = json(webStart(name, bytes.length), 200).path("ticket").asText();
        put(webPartUrl(ticket), bytes);
        return mockMvc.perform(post(uploadsPath() + "/complete").header("Authorization", bearer(userId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"ticket\":\"" + ticket + "\"}"));
    }

    /** MCP 클라이언트처럼 Content-Type 없이 조각을 PUT한다. */
    private void put(String url, byte[] bytes) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private String body(String documentId) {
        return jdbc.queryForObject("SELECT markdown FROM document_edit_states WHERE document_id = ?", String.class, documentId);
    }

    private int ingestRuns() {
        return jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE user_id = ? AND kind <> 'convert'",
                Integer.class, userId);
    }

    private int documentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM documents WHERE user_id = ?", Integer.class, userId);
    }

    private void grant(String user) {
        redis.opsForValue().set("authz:role:v2:" + workspaceId + ":" + user, "{\"role\":\"OWNER\",\"permissions\":[]}");
    }

    private String uploadsPath() {
        return "/api/workspaces/" + workspaceId + "/documents/uploads";
    }

    private String bearer(String user) {
        return "Bearer " + jwt.generateAccessToken(user, user + "@example.com");
    }

    private JsonNode json(ResultActions actions, int status) throws Exception {
        return objectMapper.readTree(actions.andExpect(status().is(status)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }
}
