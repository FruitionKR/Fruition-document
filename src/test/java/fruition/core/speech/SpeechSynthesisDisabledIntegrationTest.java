package fruition.core.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.repository.ChatSessionRepository;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 답변 듣기 비용 스위치(app.speech.synthesis.enabled=false): 저장본이 있어도 재생하지 않고, 크레딧 예약·ai 호출 없이
 * 403 SPEECH_SYNTHESIS_DISABLED로 거절한다. 모델 카탈로그는 speech_synthesis_enabled=false를 알린다.
 * ai 주소는 닫힌 포트라 호출이 일어나면 403이 아니라 502·503이 된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.speech.synthesis.enabled=false")
@Import(TestcontainersConfiguration.class)
class SpeechSynthesisDisabledIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired ChatSessionRepository sessions;
    @Autowired ChatMessageRepository messages;
    @Autowired SpeechAudioStore store;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void rejectsBeforeReplayBillingOrAiCall() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        redisTemplate.opsForValue().set("authz:role:v2:" + workspaceId + ":" + userId, "{\"role\":\"MEMBER\",\"permissions\":[]}");
        String sessionId = "session_" + suffix;
        ChatSession session = sessions.save(new ChatSession(sessionId, workspaceId, userId, "제목"));
        String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        messages.save(new ChatMessage(messageId, session, "pair_" + messageId, "assistant", "답변입니다.", "completed",
                Instant.now(), null));
        // 켜져 있을 때 만든 저장본이 있어도 꺼지면 재생하지 않는다.
        Path pcm = Files.createTempFile("speech-disabled-", ".pcm");
        Files.write(pcm, new byte[] {1, 2, 3, 4});
        store.save(workspaceId, userId, messageId, pcm);

        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/workspaces/"
                        + workspaceId + "/speech/synthesis"))
                .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message_id\":\"" + messageId + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        JsonNode error = JSON.readTree(response.body()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("SPEECH_SYNTHESIS_DISABLED");
        assertThat(error.path("message").asText()).isEqualTo("지금은 답변 듣기를 쓸 수 없어요.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE user_id = ?", Integer.class, userId))
                .isZero();
    }

    @Test
    void catalogReportsSpeechSynthesisDisabled() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai-models"))
                .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken("user_catalog", "c@example.com"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("speech_synthesis_enabled").isBoolean()).isTrue();
        assertThat(JSON.readTree(response.body()).path("speech_synthesis_enabled").asBoolean()).isFalse();
    }
}
