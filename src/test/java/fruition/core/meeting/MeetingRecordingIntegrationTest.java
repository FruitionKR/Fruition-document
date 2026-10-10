package fruition.core.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fruition.TestcontainersConfiguration;
import fruition.shared.security.JwtTokenProvider;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 녹음 원본 업로드·파일 전사 작업자·재생 주소·회의 삭제를 실제 MinIO와 가짜 ai-svc 파일 전사로 확인한다. */
@SpringBootTest(properties = "app.meeting.transcription-poll-interval-ms=200")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MeetingRecordingIntegrationTest {

    static final HttpServer FAKE_AI;
    static volatile int aiStatus = 200;
    static final String DEFAULT_TEXT = "출시는 금요일로 하겠습니다. 민수가 점검을 맡나요?\n네, 맡겠습니다!";
    static volatile String aiText = DEFAULT_TEXT;
    static final AtomicReference<String> lastContentType = new AtomicReference<>();
    static final AtomicReference<String> lastRequestId = new AtomicReference<>();
    static final AtomicReference<String> lastQuery = new AtomicReference<>();

    static {
        try {
            FAKE_AI = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        FAKE_AI.createContext("/speech/transcriptions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            lastRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = (aiStatus == 200
                    ? new ObjectMapper().createObjectNode().put("text", aiText).toString()
                    : "{\"detail\":\"private\"}").getBytes(StandardCharsets.UTF_8);
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
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired MinioClient minio;
    @Autowired StorageProperties storage;

    private String userId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_" + suffix;
        workspaceId = "ws_" + suffix;
        redisTemplate.opsForValue().set("authz:role:" + workspaceId + ":" + userId, "OWNER");
        aiStatus = 200;
        aiText = DEFAULT_TEXT;
    }

    @Test
    void uploadedRecording_isTranscribedIntoSentencesAndPlayable() throws Exception {
        String meetingId = createMeeting("upload");
        upload(meetingId, "audio/mp4", "m4a-bytes".getBytes()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("transcribing"));

        JsonNode meeting = awaitStatus(meetingId, "open");
        assertThat(lastContentType.get()).isEqualTo("audio/mp4");
        // 사용량 귀속 run_id는 쿼리가 아니라 X-Request-Id 헤더로 보낸다(#87).
        assertThat(lastRequestId.get()).startsWith("meeting_transcription:");
        assertThat(lastQuery.get()).doesNotContain("run_id");
        // 문장을 약 1,000자 단위로 묶는다(회의록 AI의 1,000구간 한도).
        assertThat(meeting.path("segments")).extracting(s -> s.path("id").asText() + "=" + s.path("text").asText())
                .containsExactly("s1_seg_0001=출시는 금요일로 하겠습니다. 민수가 점검을 맡나요? 네, 맡겠습니다!");
        assertThat(meeting.path("transcript_complete").asBoolean()).isTrue();
        assertThat(meeting.path("has_recording").asBoolean()).isTrue();

        String url = json(mockMvc.perform(get(base() + "/" + meetingId + "/recording-url").header("Authorization", bearer()))
                .andExpect(status().isOk())).path("url").asText();
        try (var in = URI.create(url).toURL().openStream()) {
            assertThat(in.readAllBytes()).isEqualTo("m4a-bytes".getBytes());
        }
    }

    @Test
    void failedTranscription_canBeRetriedByUploadingAgain() throws Exception {
        String meetingId = createMeeting("upload");
        aiStatus = 422;
        upload(meetingId, "audio/wav", new byte[]{1, 2}).andExpect(status().isAccepted());
        JsonNode failed = awaitStatus(meetingId, "failed");
        assertThat(failed.path("error").asText()).isEqualTo("지원하지 않거나 인식할 수 없는 녹음 파일입니다.");
        String firstKey = recordingKey(meetingId);

        aiStatus = 200;
        upload(meetingId, "audio/webm;codecs=opus", new byte[]{3}).andExpect(status().isAccepted());
        assertThat(awaitStatus(meetingId, "open").path("error").isNull()).isTrue();
        // 다른 형식으로 다시 올려도 이전 원본이 남지 않는다(회의 삭제로도 지울 수 없게 되는 것을 막는다).
        assertThat(recordingKey(meetingId)).endsWith(".webm").isNotEqualTo(firstKey);
        assertThat(storedKeys(meetingId)).containsExactly(recordingKey(meetingId));
    }

    @Test
    void liveMeeting_keepsOneRecordingOnlyAfterLiveEnds() throws Exception {
        String meetingId = createMeeting("live");
        redisTemplate.opsForValue().set("speech:live:" + meetingId, "someone");
        upload(meetingId, "audio/webm", new byte[]{1}).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_LIVE_IN_USE"));
        redisTemplate.delete("speech:live:" + meetingId);

        upload(meetingId, "audio/webm", new byte[]{1}).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("open"));
        upload(meetingId, "audio/webm", new byte[]{2}).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_RECORDING_NOT_ALLOWED"));
        // 거절된 업로드는 상태 변경 전에 걸러지거나, 걸러지지 않아도 방금 쓴 객체를 지운다.
        assertThat(storedKeys(meetingId)).containsExactly(recordingKey(meetingId));
    }

    @Test
    void invalidFiles_areRejected() throws Exception {
        String meetingId = createMeeting("upload");
        upload(meetingId, "video/mp4", new byte[]{1}).andExpect(status().isUnsupportedMediaType());
        upload(meetingId, "audio/wav", new byte[0]).andExpect(status().isUnprocessableEntity());
        upload(meetingId, "audio/wav", new byte[(int) MeetingRecordingService.MAX_BYTES + 1]).andExpect(status().isPayloadTooLarge());
        assertThat(json(mockMvc.perform(get(base() + "/" + meetingId).header("Authorization", bearer())))
                .path("status").asText()).isEqualTo("awaiting_upload");
    }

    @Test
    void delete_removesRecordingAndRows_andRequiresOwnerAndNoLive() throws Exception {
        String meetingId = createMeeting("upload");
        upload(meetingId, "audio/mp4", new byte[]{1}).andExpect(status().isAccepted());
        awaitStatus(meetingId, "open");
        String key = jdbc.queryForObject("SELECT recording_key FROM meetings WHERE id = ?", String.class, meetingId);

        String other = "user_" + UUID.randomUUID().toString().substring(0, 8);
        redisTemplate.opsForValue().set("authz:role:" + workspaceId + ":" + other, "OWNER");
        mockMvc.perform(delete(base() + "/" + meetingId)
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(other, other + "@x.com")))
                .andExpect(status().isNotFound());

        redisTemplate.opsForValue().set("speech:live:" + meetingId, "someone");
        mockMvc.perform(delete(base() + "/" + meetingId).header("Authorization", bearer())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_LIVE_IN_USE"));
        redisTemplate.delete("speech:live:" + meetingId);

        // 전사 중(선점됨)인 회의는 지울 수 없다. 원본도 그대로 남는다.
        jdbc.update("UPDATE meetings SET status = 'transcribing', claimed_at = now() WHERE id = ?", meetingId);
        mockMvc.perform(delete(base() + "/" + meetingId).header("Authorization", bearer())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_TRANSCRIBING"));
        assertThat(storedKeys(meetingId)).containsExactly(key);
        jdbc.update("UPDATE meetings SET status = 'open', claimed_at = NULL WHERE id = ?", meetingId);

        mockMvc.perform(delete(base() + "/" + meetingId).header("Authorization", bearer())).andExpect(status().isNoContent());
        mockMvc.perform(get(base() + "/" + meetingId).header("Authorization", bearer())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meeting_segments WHERE meeting_id = ?", Integer.class, meetingId)).isZero();
        assertThatThrownBy(() -> minio.statObject(StatObjectArgs.builder().bucket(storage.getBucket()).object(key).build()))
                .hasMessageContaining("Object does not exist");
    }

    @Test
    void longTranscript_isPackedUnderNotesLimitsOrRejected() throws Exception {
        aiText = "네. ".repeat(3_000);  // 짧은 발화 3,000개
        String meetingId = createMeeting("upload");
        upload(meetingId, "audio/mp4", new byte[]{1}).andExpect(status().isAccepted());
        JsonNode meeting = awaitStatus(meetingId, "open");
        assertThat(meeting.path("segments").size()).isBetween(1, 1_000);
        meeting.path("segments").forEach(s -> assertThat(s.path("text").asText().length()).isLessThanOrEqualTo(1_000));

        aiText = "가".repeat(100_001);
        String tooLong = createMeeting("upload");
        upload(tooLong, "audio/mp4", new byte[]{1}).andExpect(status().isAccepted());
        assertThat(awaitStatus(tooLong, "failed").path("error").asText()).contains("100,000자");
    }

    @Test
    void segments_packSentencesUpToTargetLength() {
        String sentence = "가".repeat(400) + ".";
        assertThat(MeetingTranscriptionWorker.segments(String.join(" ", sentence, sentence, sentence)))
                .extracting(String::length).containsExactly(803, 401);  // 두 문장+공백까지 1,000자 이하
        assertThat(MeetingTranscriptionWorker.segments("가".repeat(5_000))).extracting(String::length)
                .containsExactly(5_000);  // 한 문장이 길면 그대로(구간당 10,000자 한도 안)
        assertThat(MeetingTranscriptionWorker.segments("")).isEmpty();
    }

    @Test
    void sentences_splitOnPunctuationAndLongText() {
        assertThat(MeetingTranscriptionWorker.sentences("  가. 나?\n\n다!  라")).containsExactly("가.", "나?", "다!", "라");
        assertThat(MeetingTranscriptionWorker.sentences("")).isEmpty();
        assertThat(MeetingTranscriptionWorker.sentences("가".repeat(20_001))).extracting(String::length)
                .containsExactly(10_000, 10_000, 1);
    }

    // ---------- helpers

    private String createMeeting(String source) throws Exception {
        return json(mockMvc.perform(post(base()).header("Authorization", bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"display_name\":\"녹음 회의\",\"source\":\"" + source + "\"}"))
                .andExpect(status().isCreated())).path("meeting_id").asText();
    }

    private ResultActions upload(String meetingId, String contentType, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart(HttpMethod.PUT, base() + "/" + meetingId + "/recording")
                .file(new MockMultipartFile("file", "recording", contentType, bytes))
                .header("Authorization", bearer()));
    }

    private JsonNode awaitStatus(String meetingId, String expected) throws Exception {
        JsonNode meeting = null;
        for (int i = 0; i < 100; i++) {
            meeting = json(mockMvc.perform(get(base() + "/" + meetingId).header("Authorization", bearer())));
            if (expected.equals(meeting.path("status").asText())) {
                return meeting;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("상태가 " + expected + "가 되지 않았습니다: " + meeting);
    }

    private String recordingKey(String meetingId) {
        return jdbc.queryForObject("SELECT recording_key FROM meetings WHERE id = ?", String.class, meetingId);
    }

    private java.util.List<String> storedKeys(String meetingId) throws Exception {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (var item : minio.listObjects(io.minio.ListObjectsArgs.builder().bucket(storage.getBucket())
                .prefix("meetings/" + meetingId + "/").recursive(true).build())) {
            keys.add(item.get().objectName());
        }
        return keys;
    }

    private String base() {
        return "/api/workspaces/" + workspaceId + "/meetings";
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com");
    }
}
