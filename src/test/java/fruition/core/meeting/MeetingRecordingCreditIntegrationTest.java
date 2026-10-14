package fruition.core.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.shared.security.JwtTokenProvider;
import fruition.shared.util.StorageProperties;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 크레딧 사전 승인(enforce)이 켜진 환경에서 시작은 예약 없이 미리 거절하고, 확정은 예약 실패 시 객체를 지운다. */
@SpringBootTest(properties = "app.billing.enforce=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MeetingRecordingCreditIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtTokenProvider jwt;
    @Autowired MinioClient minio;
    @Autowired StorageProperties storage;
    @MockBean fruition.core.document.service.AiCommandOutboxPublisher publisher;

    @Test
    void insufficientCredit_isRejectedAtStart_andAtCompleteTheObjectIsDeleted() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String user = "user_" + suffix, ws = "ws_" + suffix;
        redis.opsForValue().set("authz:role:v2:" + ws + ":" + user, "{\"role\":\"OWNER\",\"permissions\":[]}");
        String bearer = "Bearer " + jwt.generateAccessToken(user, user + "@example.com");
        String base = "/api/workspaces/" + ws + "/meetings";
        String meetingId = objectMapper.readTree(mockMvc.perform(post(base).header("Authorization", bearer)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"upload\"}")).andReturn().getResponse().getContentAsString()).path("meeting_id").asText();

        // 잔액이 없으면 티켓을 주지 않는다.
        startUpload(base, meetingId, bearer).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_CREDIT"));

        // 시작 때는 충분했지만 확정 전에 잔액이 사라지면 확정이 거절되고 올린 객체가 남지 않는다.
        jdbc.update("INSERT INTO credit_accounts (user_id, balance) VALUES (?, 100000000) ON CONFLICT (user_id) DO UPDATE SET balance = 100000000", user);
        ResultActions started = startUpload(base, meetingId, bearer).andExpect(status().isOk());
        String ticket = objectMapper.readTree(started.andReturn().getResponse().getContentAsString()).path("ticket").asText();
        String body = "{\"ticket\":\"" + ticket + "\",\"first_part\":1,\"count\":1}";
        JsonNode parts = objectMapper.readTree(mockMvc.perform(post(base + "/" + meetingId + "/recording/uploads/parts")
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
        var put = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(parts.path("parts").get(0).path("url").asText()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2, 3})).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(200);
        jdbc.update("UPDATE credit_accounts SET balance = 0 WHERE user_id = ?", user);

        mockMvc.perform(post(base + "/" + meetingId + "/recording/uploads/complete").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"ticket\":\"" + ticket + "\"}"))
                .andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_CREDIT"));
        int objects = 0;
        for (var item : minio.listObjects(ListObjectsArgs.builder().bucket(storage.getBucket())
                .prefix("meetings/" + meetingId + "/").recursive(true).build())) {
            item.get();
            objects++;
        }
        assertThat(objects).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM meetings WHERE id = ?", String.class, meetingId)).isEqualTo("awaiting_upload");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_command_outbox WHERE payload LIKE ?", Integer.class,
                "%" + meetingId + "%")).isZero();
    }

    private ResultActions startUpload(String base, String meetingId, String bearer) throws Exception {
        return mockMvc.perform(post(base + "/" + meetingId + "/recording/uploads").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"content_type\":\"audio/wav\",\"size\":3}"));
    }
}
