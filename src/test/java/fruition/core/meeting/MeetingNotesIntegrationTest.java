package fruition.core.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 회의록 초안 생성·버전·저장을 가짜 ai-svc 회의록 서버와 실제 문서 저장 경로로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MeetingNotesIntegrationTest {

    static final HttpServer FAKE_AI;
    static final AtomicInteger calls = new AtomicInteger();
    static final AtomicReference<String> lastRequest = new AtomicReference<>();
    static volatile int aiStatus = 200;

    static final String DRAFT = """
            {"display_name":"출시 회의","markdown":"# 출시 회의\\n- 금요일 출시 (s1_a)",
             "summary":[{"text":"출시 일정을 정했다.","source_segment_ids":["s1_a"]}],
             "decisions":[{"text":"금요일 출시","source_segment_ids":["s1_a"]}],
             "action_items":[{"text":"민수: 배포 점검","source_segment_ids":["s1_b"]}],
             "open_questions":[]}
            """;

    static {
        try {
            FAKE_AI = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        FAKE_AI.createContext("/meeting-notes/preview", exchange -> {
            calls.incrementAndGet();
            lastRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = (aiStatus == 200 ? DRAFT : "{\"detail\":\"private-provider-detail\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(aiStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        FAKE_AI.start();
    }

    @DynamicPropertySource
    static void aiEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.speech.meeting-notes-endpoint",
                () -> "http://127.0.0.1:" + FAKE_AI.getAddress().getPort() + "/meeting-notes/preview");
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

    private String userId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_" + suffix;
        workspaceId = "ws_" + suffix;
        redisTemplate.opsForValue().set("authz:role:" + workspaceId + ":" + userId, "OWNER");
        aiStatus = 200;
        calls.set(0);
    }

    @Test
    void generate_rendersBodyWithoutSegmentIdsAndReplaysSameKey() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        JsonNode notes = json(generate(meetingId, "k1", false).andExpect(status().isOk()));

        assertThat(notes.path("version").asInt()).isEqualTo(1);
        assertThat(notes.path("status").asText()).isEqualTo("ready");
        assertThat(notes.path("markdown").asText()).isEqualTo("""
                # 출시 회의

                ## 요약
                - 출시 일정을 정했다.

                ## 결정 사항
                - 금요일 출시

                ## 할 일
                - 민수: 배포 점검

                ## 미결 사항
                - 확인된 내용 없음""");
        assertThat(notes.path("action_items").get(0).path("source_segment_ids").get(0).asText()).isEqualTo("s1_b");
        JsonNode sent = objectMapper.readTree(lastRequest.get());
        assertThat(sent.path("display_name").asText()).isEqualTo("출시 회의");
        assertThat(sent.path("segments")).extracting(s -> s.path("id").asText()).containsExactly("s1_a", "s1_b");

        generate(meetingId, "k1", false).andExpect(jsonPath("$.version").value(1));
        assertThat(calls.get()).isEqualTo(1);  // 같은 키 재시도는 AI를 다시 부르지 않는다
    }

    @Test
    void incompleteTranscript_requiresAllowPartial() throws Exception {
        String meetingId = meetingWithTranscript(null, "interrupted");
        generate(meetingId, "k1", false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_TRANSCRIPT_INCOMPLETE"));
        generate(meetingId, "k2", true).andExpect(status().isOk()).andExpect(jsonPath("$.partial").value(true));
    }

    @Test
    void failedRegeneration_keepsLastReadyDraftUsable() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        generate(meetingId, "k1", false).andExpect(status().isOk());
        aiStatus = 502;
        String failure = generate(meetingId, "k2", false).andExpect(status().isBadGateway())
                .andReturn().getResponse().getContentAsString();
        assertThat(failure).doesNotContain("private-provider-detail");

        mockMvc.perform(get(notesUrl(meetingId)).header("Authorization", bearer()))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.error_code").value("MEETING_NOTES_FAILED"))
                .andExpect(jsonPath("$.last_ready_version").value(1));
        apply(meetingId, 1, "a1", "{\"mode\":\"create\"}").andExpect(status().isOk());
    }

    @Test
    void create_isSavedOnceAcrossRetriesAndOnlyOncePerVersion() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        generate(meetingId, "k1", false);

        String first = json(apply(meetingId, 1, "a1", "{\"mode\":\"create\",\"display_name\":\"출시 회의록\"}")
                .andExpect(status().isOk())).path("applied").path("document_id").asText();
        String retried = json(apply(meetingId, 1, "a1", "{\"mode\":\"create\",\"display_name\":\"출시 회의록\"}")
                .andExpect(status().isOk())).path("applied").path("document_id").asText();

        assertThat(retried).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE workspace_id = ?", Integer.class, workspaceId))
                .isEqualTo(1);
        assertThat(markdownOf(first)).startsWith("# 출시 회의\n\n## 요약").doesNotContain("s1_a");
        apply(meetingId, 1, "a2", "{\"mode\":\"create\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_NOTES_ALREADY_APPLIED"));
        // 같은 키로 내용이 다른 요청은 처음 기록한 요청으로 다시 저장하지 않는다.
        apply(meetingId, 1, "a1", "{\"mode\":\"create\",\"display_name\":\"다른 이름\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void append_mergesOnceAndRejectsChangedDocument() throws Exception {
        String documentId = createDocument("# 주간 회의\n\n- 기존 본문");
        String meetingId = meetingWithTranscript(documentId, "finished");
        generate(meetingId, "k1", false);

        JsonNode preview = json(mockMvc.perform(post(notesUrl(meetingId) + "/1/append-preview")
                .header("Authorization", bearer())).andExpect(status().isOk()));
        long base = preview.path("base_revision").asLong();
        assertThat(preview.path("markdown").asText()).startsWith("# 주간 회의\n\n- 기존 본문\n\n# 출시 회의\n");

        // 미리보기 이후 다른 곳에서 문서가 바뀌면 저장하지 않는다.
        saveContent(documentId, "# 주간 회의\n\n- 다른 곳에서 수정", base);
        apply(meetingId, 1, "a1", appendBody(base)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_REVISION_CHANGED"));

        long fresh = json(mockMvc.perform(post(notesUrl(meetingId) + "/1/append-preview")
                .header("Authorization", bearer()))).path("base_revision").asLong();
        apply(meetingId, 1, "a2", appendBody(fresh)).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied.mode").value("append"));
        apply(meetingId, 1, "a2", appendBody(fresh)).andExpect(status().isOk());  // 같은 요청 재시도
        apply(meetingId, 1, "a2", "{\"mode\":\"append\",\"base_revision\":" + fresh + ",\"markdown\":\"# 다른 회의록\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        String saved = markdownOf(documentId);
        assertThat(saved).startsWith("# 주간 회의\n\n- 다른 곳에서 수정\n\n# 출시 회의\n");
        assertThat(saved.split("# 출시 회의", -1)).hasSize(2);  // 두 번 붙지 않았다
    }

    @Test
    void olderDraftAndOtherUser_areRejected() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        generate(meetingId, "k1", false);
        generate(meetingId, "k2", false);
        apply(meetingId, 1, "a1", "{\"mode\":\"create\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_NOTES_OUTDATED"));

        String other = "user_" + UUID.randomUUID().toString().substring(0, 8);
        redisTemplate.opsForValue().set("authz:role:" + workspaceId + ":" + other, "OWNER");
        mockMvc.perform(get(notesUrl(meetingId))
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(other, other + "@x.com")))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers

    private String meetingWithTranscript(String documentId, String endReason) throws Exception {
        String body = documentId == null ? "{\"display_name\":\"출시 회의\",\"source\":\"live\"}"
                : "{\"display_name\":\"출시 회의\",\"source\":\"live\",\"document_id\":\"" + documentId + "\"}";
        String meetingId = json(mockMvc.perform(post("/api/workspaces/" + workspaceId + "/meetings")
                        .header("Authorization", bearer()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())).path("meeting_id").asText();
        jdbc.update("INSERT INTO meeting_streams (id, meeting_id, stream_order, end_reason, started_at) VALUES (?, ?, 1, ?, now())",
                "stream_" + meetingId, meetingId, endReason);
        jdbc.update("INSERT INTO meeting_segments (meeting_id, id, stream_id, position, text, created_at) VALUES (?, 's1_a', ?, 1, ?, now())",
                meetingId, "stream_" + meetingId, "출시는 금요일로 확정하겠습니다.");
        jdbc.update("INSERT INTO meeting_segments (meeting_id, id, stream_id, position, text, created_at) VALUES (?, 's1_b', ?, 2, ?, now())",
                meetingId, "stream_" + meetingId, "민수가 배포 점검을 맡겠습니다.");
        return meetingId;
    }

    private String createDocument(String markdown) throws Exception {
        return json(mockMvc.perform(post("/api/workspaces/" + workspaceId + "/documents/markdown")
                        .header("Authorization", bearer()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("display_name", "주간 회의", "markdown", markdown))))
                .andExpect(status().isCreated())).path("id").asText();
    }

    private void saveContent(String documentId, String markdown, long base) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart(org.springframework.http.HttpMethod.PUT,
                                "/api/workspaces/" + workspaceId + "/documents/" + documentId + "/content")
                        .part(new org.springframework.mock.web.MockPart("markdown", markdown.getBytes(StandardCharsets.UTF_8)))
                        .part(new org.springframework.mock.web.MockPart("base_revision", String.valueOf(base).getBytes()))
                        .part(new org.springframework.mock.web.MockPart("revision_write_id", UUID.randomUUID().toString().getBytes()))
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());
    }

    private String markdownOf(String documentId) {
        return jdbc.queryForObject("SELECT markdown FROM document_edit_states WHERE document_id = ?", String.class, documentId);
    }

    private ResultActions generate(String meetingId, String key, boolean allowPartial) throws Exception {
        return mockMvc.perform(post(notesUrl(meetingId) + "?allow_partial=" + allowPartial)
                .header("Authorization", bearer()).header("Idempotency-Key", key));
    }

    private ResultActions apply(String meetingId, int version, String key, String body) throws Exception {
        return mockMvc.perform(post(notesUrl(meetingId) + "/" + version + "/apply")
                .header("Authorization", bearer()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String appendBody(long base) {
        return "{\"mode\":\"append\",\"base_revision\":" + base + "}";
    }

    private String notesUrl(String meetingId) {
        return "/api/workspaces/" + workspaceId + "/meetings/" + meetingId + "/notes";
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com");
    }
}
