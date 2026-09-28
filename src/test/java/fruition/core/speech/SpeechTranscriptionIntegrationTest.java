package fruition.core.speech;

import com.sun.net.httpserver.HttpServer;
import fruition.TestcontainersConfiguration;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 채팅 음성 입력이 ai-svc 파일 전사 계약대로 중계되는지 가짜 ai-svc HTTP 서버로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SpeechTranscriptionIntegrationTest {

    /** ai-svc 응답을 테스트마다 바꾼다. 마지막 요청을 기록한다. */
    static final HttpServer FAKE_AI;
    static volatile int aiStatus = 200;
    static volatile String aiBody = "{\"text\":\"검색 인덱싱은 어떻게 동작해?\"}";
    static final AtomicReference<String> lastQuery = new AtomicReference<>();
    static final AtomicReference<String> lastContentType = new AtomicReference<>();
    static final AtomicReference<String> lastToken = new AtomicReference<>();
    static final AtomicReference<byte[]> lastBody = new AtomicReference<>();

    static {
        try {
            FAKE_AI = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        FAKE_AI.createContext("/speech/transcriptions", exchange -> {
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            lastToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            lastBody.set(exchange.getRequestBody().readAllBytes());
            byte[] body = aiBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(aiStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        FAKE_AI.start();
    }

    @DynamicPropertySource
    static void aiEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.speech.transcription-endpoint",
                () -> "http://127.0.0.1:" + FAKE_AI.getAddress().getPort() + "/speech/transcriptions");
    }

    @AfterAll
    static void stop() {
        FAKE_AI.stop(0);
    }

    @Autowired MockMvc mockMvc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JwtTokenProvider jwtTokenProvider;

    private String userId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_" + suffix;
        workspaceId = "ws_" + suffix;
        redisTemplate.opsForValue().set("authz:role:" + workspaceId + ":" + userId, "MEMBER");
        aiStatus = 200;
        aiBody = "{\"text\":\"검색 인덱싱은 어떻게 동작해?\"}";
        lastBody.set(null);
    }

    @Test
    void forwardsAudioToAiAndReturnsText() throws Exception {
        byte[] audio = "webm-audio".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post(url()).header("Authorization", bearer())
                        .contentType("audio/webm;codecs=opus").content(audio))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("검색 인덱싱은 어떻게 동작해?"));

        assertThat(lastBody.get()).isEqualTo(audio);
        assertThat(lastContentType.get()).isEqualTo("audio/webm");  // codecs 파라미터는 떼고 넘긴다
        assertThat(lastToken.get()).isEqualTo("test-internal-callback");
        assertThat(lastQuery.get()).isEqualTo("workspace_id=" + workspaceId + "&user_id=" + userId);
    }

    @Test
    void emptyRecognition_returnsEmptyText() throws Exception {
        aiBody = "{\"text\":\"\"}";
        mockMvc.perform(post(url()).header("Authorization", bearer()).contentType("audio/wav").content(new byte[]{1}))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(""));
    }

    @Test
    void invalidInput_isRejectedBeforeCallingAi() throws Exception {
        mockMvc.perform(post(url()).header("Authorization", bearer()).contentType("video/mp4").content(new byte[]{1}))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(post(url()).header("Authorization", bearer()).contentType("audio/webm").content(new byte[0]))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post(url()).header("Authorization", bearer()).contentType("audio/webm")
                        .content(new byte[SpeechController.MAX_AUDIO_BYTES + 1]))
                .andExpect(status().isPayloadTooLarge());
        assertThat(lastBody.get()).isNull();
    }

    @Test
    void nonMember_isRejectedBeforeCallingAi() throws Exception {
        String outsider = "user_" + UUID.randomUUID().toString().substring(0, 8);
        redisTemplate.opsForValue().set("authz:role:" + workspaceId + ":" + outsider, "NONE");
        mockMvc.perform(post(url())
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(outsider, outsider + "@example.com"))
                        .contentType("audio/webm").content(new byte[]{1}))
                .andExpect(status().isNotFound());
        assertThat(lastBody.get()).isNull();
    }

    @Test
    void aiFailures_areMappedWithoutLeakingProviderDetails() throws Exception {
        aiStatus = 502;
        aiBody = "{\"detail\":\"private-provider-detail\"}";
        String body = mockMvc.perform(post(url()).header("Authorization", bearer()).contentType("audio/webm").content(new byte[]{1}))
                .andExpect(status().isBadGateway())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("private-provider-detail");

        aiStatus = 401;
        mockMvc.perform(post(url()).header("Authorization", bearer()).contentType("audio/webm").content(new byte[]{1}))
                .andExpect(status().isServiceUnavailable());
        aiStatus = 422;
        mockMvc.perform(post(url()).header("Authorization", bearer()).contentType("audio/webm").content(new byte[]{1}))
                .andExpect(status().isUnprocessableEntity());
    }

    private String url() {
        return "/api/workspaces/" + workspaceId + "/speech/transcriptions";
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com");
    }
}
