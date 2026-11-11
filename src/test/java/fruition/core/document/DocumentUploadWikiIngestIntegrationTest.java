package fruition.core.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.authz.WorkspaceSettingsClient;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 크레딧 사전 승인(enforce)이 켜진 환경에서 Markdown 업로드 응답의 wiki_ingest가 자동 편입 등록 결과를 알려 주는지
 * 실제 CreditService·Postgres로 확인한다. 크레딧이 모자라도 업로드는 201이고 문서는 uploaded로 남는다.
 */
@SpringBootTest(properties = "app.billing.enforce=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DocumentUploadWikiIngestIntegrationTest {

    /** 편입 command가 Kafka로 나가지 않게 한다. */
    @MockBean fruition.core.document.service.AiCommandOutboxPublisher outboxPublisher;
    @MockBean WorkspaceAiModelClient workspaceAiModelClient;
    @MockBean WorkspaceSettingsClient workspaceSettings;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtTokenProvider jwt;

    private String userId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_ingest_" + suffix;
        workspaceId = "ws_ingest_" + suffix;
        // OWNER는 wiki.ingest를 가진다.
        redis.opsForValue().set("authz:role:v2:" + workspaceId + ":" + userId, "{\"role\":\"OWNER\",\"permissions\":[]}");
        when(workspaceAiModelClient.get(anyString()))
                .thenReturn(new WorkspaceAiModelClient.AiModelSelection("gemini", "gemini-3.5-flash-lite"));
        when(workspaceSettings.autoIngestMarkdownOnUpload(anyString())).thenReturn(true);
    }

    @Test
    void insufficientCredit_uploadSucceeds_andReportsSkipped() throws Exception {
        JsonNode body = upload("a.md");

        assertThat(body.path("wiki_ingest").path("status").asText()).isEqualTo("SKIPPED");
        assertThat(body.path("wiki_ingest").path("reason").asText()).isEqualTo("INSUFFICIENT_CREDIT");
        assertThat(documentStatus(body)).isEqualTo("uploaded");
        assertThat(ingestCommands()).isZero();
    }

    @Test
    void sufficientCredit_reportsQueued() throws Exception {
        credit(100_000_000L, false);

        JsonNode body = upload("a.md");

        assertThat(body.path("wiki_ingest").path("status").asText()).isEqualTo("QUEUED");
        assertThat(body.path("wiki_ingest").path("reason").isNull()).isTrue();
        assertThat(documentStatus(body)).isEqualTo("processing");
        assertThat(ingestCommands()).isEqualTo(1);
    }

    @Test
    void multipleUploads_laterFileIsSkippedOnceReservationsUseTheBalance() throws Exception {
        // 16바이트 본문의 예약액은 203 milli-KRW다. 잔액 100을 모두 예약하면 다음 편입은 거절된다.
        credit(100L, false);

        JsonNode first = upload("first.md");
        JsonNode second = upload("second.txt");

        assertThat(first.path("wiki_ingest").path("status").asText()).isEqualTo("QUEUED");
        assertThat(second.path("wiki_ingest").path("status").asText()).isEqualTo("SKIPPED");
        assertThat(second.path("wiki_ingest").path("reason").asText()).isEqualTo("INSUFFICIENT_CREDIT");
        assertThat(documentStatus(second)).isEqualTo("uploaded");
    }

    @Test
    void limitExemptAccount_withoutBalance_reportsQueued() throws Exception {
        credit(0L, true);

        JsonNode body = upload("a.md");

        assertThat(body.path("wiki_ingest").path("status").asText()).isEqualTo("QUEUED");
        assertThat(ingestCommands()).isEqualTo(1);
    }

    @Test
    void autoIngestDisabled_reportsNotApplicable() throws Exception {
        when(workspaceSettings.autoIngestMarkdownOnUpload(anyString())).thenReturn(false);
        credit(100_000_000L, false);

        JsonNode body = upload("a.md");

        assertThat(body.path("wiki_ingest").path("status").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(body.path("wiki_ingest").path("reason").asText()).isEqualTo("DISABLED");
        assertThat(ingestCommands()).isZero();
    }

    @Test
    void folderUpload_insufficientCredit_reportsSkipped() throws Exception {
        // 폴더를 지정하면 바깥 트랜잭션(폴더 잠금)이 커밋될 때 자동 편입이 돈다.
        UUID folderId = insertFolder();

        JsonNode body = upload("a.md", UUID.randomUUID().toString(), folderId);

        assertThat(body.path("folder_id").asText()).isEqualTo(folderId.toString());
        assertThat(body.path("wiki_ingest").path("status").asText()).isEqualTo("SKIPPED");
        assertThat(body.path("wiki_ingest").path("reason").asText()).isEqualTo("INSUFFICIENT_CREDIT");
        assertThat(documentStatus(body)).isEqualTo("uploaded");
        assertThat(ingestCommands()).isZero();
    }

    @Test
    void folderUpload_sufficientCredit_reportsQueued() throws Exception {
        UUID folderId = insertFolder();
        credit(100_000_000L, false);

        JsonNode body = upload("a.md", UUID.randomUUID().toString(), folderId);

        assertThat(body.path("folder_id").asText()).isEqualTo(folderId.toString());
        assertThat(body.path("wiki_ingest").path("status").asText()).isEqualTo("QUEUED");
        assertThat(body.path("wiki_ingest").path("reason").isNull()).isTrue();
        assertThat(documentStatus(body)).isEqualTo("processing");
        assertThat(ingestCommands()).isEqualTo(1);
    }

    @Test
    void idempotentReplay_returnsStoredResponseWithoutWikiIngest() throws Exception {
        String key = UUID.randomUUID().toString();
        upload("a.md", key);

        JsonNode replayed = upload("a.md", key);

        assertThat(replayed.has("wiki_ingest")).isFalse();
    }

    private JsonNode upload(String filename) throws Exception {
        return upload(filename, UUID.randomUUID().toString());
    }

    private JsonNode upload(String filename, String idempotencyKey) throws Exception {
        return upload(filename, idempotencyKey, null);
    }

    private JsonNode upload(String filename, String idempotencyKey, UUID folderId) throws Exception {
        var request = multipart("/api/workspaces/" + workspaceId + "/documents");
        if (folderId != null) request.param("folder_id", folderId.toString());
        String response = mockMvc.perform(request
                        .file(new MockMultipartFile("file", filename, "text/markdown",
                                "# 제목\n\n본문".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + jwt.generateAccessToken(userId, userId + "@example.com"))
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response);
    }

    private UUID insertFolder() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO folders(id, workspace_id, name, sort_order, created_at, updated_at) VALUES (?, ?, ?, 0, now(), now())",
                id, workspaceId, "폴더-" + id);
        return id;
    }

    private void credit(long balanceMilli, boolean limitExempt) {
        jdbc.update("INSERT INTO credit_accounts (user_id, balance, limit_exempt) VALUES (?, ?, ?)",
                userId, balanceMilli, limitExempt);
    }

    private String documentStatus(JsonNode body) {
        return jdbc.queryForObject("SELECT status FROM documents WHERE id = ?", String.class, body.path("id").asText());
    }

    private int ingestCommands() {
        return jdbc.queryForObject("SELECT count(*) FROM ai_command_outbox WHERE payload LIKE ?", Integer.class,
                "%" + userId + "%");
    }
}
