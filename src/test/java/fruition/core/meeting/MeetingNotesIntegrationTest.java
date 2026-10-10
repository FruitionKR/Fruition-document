package fruition.core.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fruition.TestcontainersConfiguration;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
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
    static final AtomicReference<String> lastRequestId = new AtomicReference<>();
    static volatile int aiStatus = 200;
    /** 생성이 끝나기 전에 응답하는지 보려고 가짜 AI를 잡아 둔다. null이면 바로 답한다. */
    static volatile CountDownLatch gate;

    static final String DRAFT = """
            {"display_name":"출시 회의","markdown":"# 출시 회의\\n- 금요일 출시 (s1_a)",
             "summary":[{"text":"출시 일정을 정했다.","source_segment_ids":["s1_a"]}],
             "decisions":[{"text":"금요일 출시","source_segment_ids":["s1_a"]}],
             "action_items":[{"text":"민수: 배포 점검","source_segment_ids":["s1_a"]}],
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
            CountDownLatch held = gate;
            if (held != null) {
                try {
                    held.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            // 실제 ai-svc MeetingNotesRequest는 정의되지 않은 필드를 거부한다(extra="forbid"). 본문 run_id는 422다(#87).
            // provider·model은 필수다(ai#68). 빠지면 422다.
            String sent = lastRequest.get();
            boolean contractOk = !sent.contains("\"run_id\"") && sent.contains("\"provider\":\"gemini\"")
                    && sent.contains("\"model\":\"gemini-3.5-flash-lite\"");
            int status = contractOk ? aiStatus : 422;
            byte[] body = (status == 200 ? DRAFT : "{\"detail\":\"private-provider-detail\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
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

    @MockBean WorkspaceAiModelClient workspaceAiModelClient;
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
        when(workspaceAiModelClient.get(workspaceId))
                .thenReturn(new WorkspaceAiModelClient.AiModelSelection("gemini", "gemini-3.5-flash-lite"));
        aiStatus = 200;
        gate = null;
        calls.set(0);
    }

    @Test
    void generate_rendersBodyWithoutSegmentIdsAndReplaysSameKey() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        JsonNode notes = generated(meetingId, "k1");

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
        // 짧은 발화는 한 묶음으로 보내고, AI가 돌려준 묶음 ID는 묶인 원래 구간 ID들로 되돌린다.
        assertThat(notes.path("action_items").get(0).path("source_segment_ids"))
                .extracting(JsonNode::asText).containsExactly("s1_a", "s1_b");
        JsonNode sent = objectMapper.readTree(lastRequest.get());
        assertThat(sent.path("display_name").asText()).isEqualTo("출시 회의");
        // 사용량 귀속 run_id는 본문이 아니라 X-Request-Id 헤더로 보낸다(#87).
        assertThat(sent.has("run_id")).isFalse();
        assertThat(lastRequestId.get()).startsWith("meeting_notes:");
        assertThat(sent.path("provider").asText()).isEqualTo("gemini");
        assertThat(sent.path("model").asText()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(sent.path("segments")).extracting(s -> s.path("id").asText()).containsExactly("s1_a");
        assertThat(sent.path("segments").get(0).path("text").asText())
                .isEqualTo("출시는 금요일로 확정하겠습니다. 민수가 배포 점검을 맡겠습니다.");

        generate(meetingId, "k1", false).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.status").value("ready"));
        assertThat(calls.get()).isEqualTo(1);  // 같은 키 재시도는 AI를 다시 부르지 않는다
    }

    @Test
    void incompleteTranscript_requiresAllowPartial() throws Exception {
        String meetingId = meetingWithTranscript(null, "interrupted");
        generate(meetingId, "k1", false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_TRANSCRIPT_INCOMPLETE"));
        generate(meetingId, "k2", true).andExpect(status().isAccepted()).andExpect(jsonPath("$.partial").value(true));
        assertThat(awaitStatus(meetingId, "ready").path("partial").asBoolean()).isTrue();
    }

    @Test
    void failedRegeneration_keepsLastReadyDraftUsable() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        generated(meetingId, "k1");
        aiStatus = 502;
        generate(meetingId, "k2", false).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("generating"));

        // 생성 실패는 HTTP 오류가 아니라 그 버전의 failed 상태로 알린다(제공자 원문은 노출하지 않는다).
        JsonNode failed = awaitStatus(meetingId, "failed");
        assertThat(failed.toString()).doesNotContain("private-provider-detail");
        assertThat(failed.path("version").asInt()).isEqualTo(2);
        assertThat(failed.path("error_code").asText()).isEqualTo("MEETING_NOTES_FAILED");
        assertThat(failed.path("last_ready_version").asInt()).isEqualTo(1);
        apply(meetingId, 1, "a1", "{\"mode\":\"create\"}").andExpect(status().isOk());
    }

    @Test
    void create_isSavedOnceAcrossRetriesAndOnlyOncePerVersion() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        generated(meetingId, "k1");

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
    void create_sanitizesNotesMarkdownAndRetriesWithSameBody() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        generated(meetingId, "k1");
        String body = "{\"mode\":\"create\",\"markdown\":\"# 회의록\\n\\n<u>결정</u> [보기](javascript:alert(1))\"}";

        String documentId = json(apply(meetingId, 1, "a1", body).andExpect(status().isOk()))
                .path("applied").path("document_id").asText();
        apply(meetingId, 1, "a1", body).andExpect(status().isOk());  // 거른 본문으로 기록해 재시도도 같은 요청이다

        assertThat(markdownOf(documentId)).isEqualTo("# 회의록\n\n결정 보기");
    }

    @Test
    void append_mergesOnceAndRejectsChangedDocument() throws Exception {
        String documentId = createDocument("# 주간 회의\n\n- 기존 본문");
        String meetingId = meetingWithTranscript(documentId, "finished");
        generated(meetingId, "k1");

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
        generated(meetingId, "k1");
        generated(meetingId, "k2");
        apply(meetingId, 1, "a1", "{\"mode\":\"create\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_NOTES_OUTDATED"));

        String other = "user_" + UUID.randomUUID().toString().substring(0, 8);
        redisTemplate.opsForValue().set("authz:role:" + workspaceId + ":" + other, "OWNER");
        mockMvc.perform(get(notesUrl(meetingId))
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(other, other + "@x.com")))
                .andExpect(status().isNotFound());
    }

    @Test
    void longMeeting_bundlesUtterancesUnderAiSegmentLimit() throws Exception {
        String meetingId = meetingWithManyUtterances(3_000);
        generated(meetingId, "k1");

        JsonNode sent = objectMapper.readTree(lastRequest.get()).path("segments");
        assertThat(sent.size()).isLessThan(1_000);  // 발화 3,000개가 회의록 AI의 1,000구간 한도 안으로 묶인다
        int total = 0;
        for (JsonNode segment : sent) {
            assertThat(segment.path("text").asText().length()).isLessThanOrEqualTo(10_000);
            total += segment.path("text").asText().length();
            // 묶음 ID는 실제 저장된 구간 ID라 AI가 돌려준 근거를 그대로 찾을 수 있다.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM meeting_segments WHERE meeting_id = ? AND id = ?",
                    Integer.class, meetingId, segment.path("id").asText())).isEqualTo(1);
        }
        assertThat(total).isLessThanOrEqualTo(100_000);
    }

    @Test
    void generate_returnsBeforeAiFinishesAndPollingSeesResult() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        gate = new CountDownLatch(1);

        // AI가 아직 답하지 않았는데도 요청은 202로 끝난다.
        generate(meetingId, "k1", false).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("generating"));
        mockMvc.perform(get(notesUrl(meetingId)).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("generating"));

        gate.countDown();
        assertThat(awaitStatus(meetingId, "ready").path("display_name").asText()).isEqualTo("출시 회의");
    }

    // ---------- helpers

    /** 생성은 비동기다. 202로 받은 뒤 조회로 끝날 때까지 기다린다. */
    private JsonNode generated(String meetingId, String key) throws Exception {
        generate(meetingId, key, false).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("generating"));
        return awaitStatus(meetingId, "ready");
    }

    private JsonNode awaitStatus(String meetingId, String expected) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonNode notes = json(mockMvc.perform(get(notesUrl(meetingId)).header("Authorization", bearer()))
                    .andExpect(status().isOk()));
            if (expected.equals(notes.path("status").asText())) {
                return notes;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("회의록 초안이 " + expected + " 상태가 되지 않았습니다.");
    }

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

    private String meetingWithManyUtterances(int count) throws Exception {
        String meetingId = json(mockMvc.perform(post("/api/workspaces/" + workspaceId + "/meetings")
                        .header("Authorization", bearer()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"display_name\":\"긴 회의\",\"source\":\"live\"}"))
                .andExpect(status().isCreated())).path("meeting_id").asText();
        jdbc.update("INSERT INTO meeting_streams (id, meeting_id, stream_order, end_reason, started_at) VALUES (?, ?, 1, 'finished', now())",
                "stream_" + meetingId, meetingId);
        jdbc.update("""
                INSERT INTO meeting_segments (meeting_id, id, stream_id, position, text, created_at)
                SELECT ?, 's1_u' || n, ?, n, '네 그렇게 하겠습니다.', now() FROM generate_series(1, ?) AS n
                """, meetingId, "stream_" + meetingId, count);
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
