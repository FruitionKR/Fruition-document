package fruition.core.aihistory.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.document.service.AiCommandOutboxPublisher;
import fruition.core.authz.WorkspaceAiModelClient;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** lint 접수 상태와 Kafka command가 한 core DB 트랜잭션에 남는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LintOperationApiIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean AiCommandOutboxPublisher outboxPublisher;
    @MockBean WorkspaceAiModelClient workspaceAiModelClient;

    private String userId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_" + suffix;
        workspaceId = "ws_" + suffix;
        redisTemplate.opsForValue().set("authz:role:" + workspaceId + ":" + userId, "OWNER");
        org.mockito.Mockito.when(workspaceAiModelClient.get(workspaceId))
                .thenReturn(new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-5-nano"));
    }

    @Test
    void writeLint_returns202AndPersistsOperationWithCommand() throws Exception {
        String body = mockMvc.perform(post("/api/workspaces/" + workspaceId + "/wiki/maintenance/lint")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materialize_promotions\":true,\"dry_run\":false}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.operation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        String operationId = response.path("operation_id").asText();
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM ai_command_outbox WHERE run_id = ?", String.class,
                response.path("run_id").asText());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_operation_logs WHERE operation_id = ?", String.class, operationId))
                .isEqualTo("processing");
        assertThat(objectMapper.readTree(payload).path("operation_id").asText()).isEqualTo(operationId);
        assertThat(objectMapper.readTree(payload).path("workspace_id").asText()).isEqualTo(workspaceId);
    }

    @Test
    void dryRun_returns202AndQueuesWithoutOperation() throws Exception {
        String body = mockMvc.perform(post("/api/workspaces/" + workspaceId + "/wiki/maintenance/lint")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dry_run\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.operation_id").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM ai_command_outbox WHERE run_id = ?", String.class,
                response.path("run_id").asText());
        assertThat(objectMapper.readTree(payload).path("dry_run").asBoolean()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_operation_logs WHERE workspace_id = ? AND operation_type = 'lint'",
                Long.class, workspaceId)).isZero();
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com");
    }
}
