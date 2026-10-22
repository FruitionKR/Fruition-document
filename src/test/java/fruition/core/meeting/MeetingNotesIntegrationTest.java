package fruition.core.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.aitask.service.AiTaskResultConsumer;
import fruition.core.usage.service.CreditService;
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
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 회의록 초안 생성(outbox 명령)·결과 이벤트 반영·버전·저장을 실제 문서 저장 경로로 확인한다. */
@SpringBootTest(properties = "app.billing.enforce=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MeetingNotesIntegrationTest {

    static final String DRAFT = """
            {"display_name":"출시 회의","markdown":"# 출시 회의\\n- 금요일 출시 (s1_a)",
             "summary":[{"text":"출시 일정을 정했다.","source_segment_ids":["s1_a"]}],
             "decisions":[{"text":"금요일 출시","source_segment_ids":["s1_a"]}],
             "action_items":[{"text":"민수: 배포 점검","source_segment_ids":["s1_a"]}],
             "open_questions":[]}
            """;

    /** 시안 구성 AI 응답(Fruition-ai docs/api/speech.md). 근거 ID는 본문에 없고 배열에만 있다. */
    static final String DESIGNED_DRAFT = """
            {"display_name":"출시 회의",
             "markdown":"# 출시 회의\\n\\n- 날짜: 2026-10-19\\n- 참석자: 김민수, 이지은\\n\\n### 회의 요약\\n- 출시 일정을 정했다.\\n\\n### 주요 논의\\n#### 출시 일정\\n- QA 기간을 검토했다.\\n\\n### 결정 사항\\n- 금요일 출시\\n\\n### 해야 할 일\\n- [ ] 배포 점검 · 김민수 · 기한 미정\\n\\n### 다음 일정\\n- 다음 주 <img src=x>점검 회의",
             "meeting_date":"2026-10-19","attendees":["김민수","이지은"],
             "summary":[{"text":"출시 일정을 정했다.","source_segment_ids":["s1_a"]}],
             "discussion":[{"topic":"출시 일정","items":[{"text":"QA 기간을 검토했다.","source_segment_ids":["s1_a"]}]}],
             "decisions":[{"text":"금요일 출시","source_segment_ids":["s1_a"]}],
             "action_items":[{"text":"배포 점검","owner":"김민수","due":null,"source_segment_ids":["s1_a"]}],
             "next_schedule":[{"text":"다음 주 점검 회의","source_segment_ids":[]}],
             "open_questions":[{"text":"할인 여부","source_segment_ids":["s1_a"]}]}
            """;

    /** 명령이 Kafka로 나가 outbox에서 사라지지 않게 한다(다른 outbox 통합 테스트와 같다). */
    @MockBean fruition.core.document.service.AiCommandOutboxPublisher outboxPublisher;
    @MockBean WorkspaceAiModelClient workspaceAiModelClient;
    @MockBean fruition.core.authz.AccessUserClient accessUserClient;
    @MockBean fruition.core.aitask.repository.PipelineTaskCancellationClient pipeline;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AiTaskResultConsumer consumer;
    @Autowired CreditService credits;
    @Autowired MeetingNotesService notesService;

    private String userId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_" + suffix;
        workspaceId = "ws_" + suffix;
        redisTemplate.opsForValue().set("authz:role:v2:" + workspaceId + ":" + userId, "{\"role\":\"OWNER\",\"permissions\":[]}");
        when(workspaceAiModelClient.get(workspaceId))
                .thenReturn(new WorkspaceAiModelClient.AiModelSelection("gemini", "gemini-3.5-flash-lite"));
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
        JsonNode sent = command(meetingId, 1);
        assertThat(sent.path("kind").asText()).isEqualTo("meeting_notes");
        assertThat(sent.path("run_id").asText()).isEqualTo(runId(meetingId, 1));
        assertThat(sent.path("workspace_id").asText()).isEqualTo(workspaceId);
        assertThat(sent.path("user_id").asText()).isEqualTo(userId);
        assertThat(sent.path("meeting_id").asText()).isEqualTo(meetingId);
        assertThat(sent.path("display_name").asText()).isEqualTo("출시 회의");
        assertThat(sent.path("provider").asText()).isEqualTo("gemini");
        assertThat(sent.path("model").asText()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(sent.path("segments")).extracting(s -> s.path("id").asText()).containsExactly("s1_a");
        assertThat(sent.path("segments").get(0).path("text").asText())
                .isEqualTo("출시는 금요일로 확정하겠습니다. 민수가 배포 점검을 맡겠습니다.");
        assertThat(sent.path("segments").get(0).size()).isEqualTo(2);
        // 회의 날짜는 회의를 만든 날의 한국 날짜다. 표시명을 모르면 참석자는 싣지 않는다.
        assertThat(sent.path("meeting_date").asText()).isEqualTo(jdbc.queryForObject(
                "SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date::text FROM meetings WHERE id = ?", String.class, meetingId));
        assertThat(sent.has("attendees")).isFalse();
        // 이전 AI 응답이면 새 필드는 비어 있다.
        assertThat(notes.path("meeting_date").isNull()).isTrue();
        assertThat(notes.path("attendees")).isEmpty();
        assertThat(notes.path("discussion")).isEmpty();
        assertThat(notes.path("next_schedule")).isEmpty();
        assertThat(notes.path("action_items").get(0).path("owner").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE id = ? AND kind = 'meeting_notes'",
                Integer.class, runId(meetingId, 1))).isEqualTo(1);  // 취소·사용량 추적용 run 등록

        generate(meetingId, "k1", false).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.status").value("ready"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meeting_notes WHERE meeting_id = ?", Integer.class,
                meetingId)).isEqualTo(1);  // 같은 키 재시도는 새 작업을 만들지 않는다
    }

    @Test
    void designedDraft_usesAiMarkdownAndReturnsNewFields() throws Exception {
        when(accessUserClient.getDisplayName(userId)).thenReturn(" 김민수 ");
        String meetingId = meetingWithTranscript(null, "finished");
        grant();
        generate(meetingId, "k1", false).andExpect(status().isAccepted());
        // 실시간 받아쓰기를 한 회의 소유자는 참석자로 보낸다.
        assertThat(command(meetingId, 1).path("attendees")).extracting(JsonNode::asText).containsExactly("김민수");
        succeed(meetingId, 1, DESIGNED_DRAFT);
        JsonNode notes = awaitStatus(meetingId, "ready");

        // AI 본문을 그대로 쓰되 원시 HTML은 걷어낸다.
        assertThat(notes.path("markdown").asText())
                .startsWith("# 출시 회의\n\n- 날짜: 2026-10-19\n- 참석자: 김민수, 이지은\n\n### 회의 요약")
                .contains("### 주요 논의\n#### 출시 일정", "- [ ] 배포 점검 · 김민수 · 기한 미정", "### 다음 일정")
                .doesNotContain("<img", "s1_a", "## 미결 사항");
        assertThat(notes.path("meeting_date").asText()).isEqualTo("2026-10-19");
        assertThat(notes.path("attendees")).extracting(JsonNode::asText).containsExactly("김민수", "이지은");
        assertThat(notes.path("discussion").get(0).path("topic").asText()).isEqualTo("출시 일정");
        // 주제 항목의 근거도 묶음 ID에서 원래 구간 ID로 되돌린다.
        assertThat(notes.path("discussion").get(0).path("items").get(0).path("source_segment_ids"))
                .extracting(JsonNode::asText).containsExactly("s1_a", "s1_b");
        JsonNode action = notes.path("action_items").get(0);
        assertThat(action.path("text").asText()).isEqualTo("배포 점검");
        assertThat(action.path("owner").asText()).isEqualTo("김민수");
        assertThat(action.path("due").isNull()).isTrue();
        assertThat(notes.path("next_schedule").get(0).path("text").asText()).isEqualTo("다음 주 점검 회의");
        assertThat(notes.path("open_questions").get(0).path("text").asText()).isEqualTo("할인 여부");

        // 새 문서 저장도 같은 본문을 쓴다.
        String documentId = json(apply(meetingId, 1, "a1", "{\"mode\":\"create\"}").andExpect(status().isOk()))
                .path("applied").path("document_id").asText();
        assertThat(markdownOf(documentId)).isEqualTo(notes.path("markdown").asText());
    }

    @Test
    void incompleteTranscript_requiresAllowPartial() throws Exception {
        String meetingId = meetingWithTranscript(null, "interrupted");
        grant();
        generate(meetingId, "k1", false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_TRANSCRIPT_INCOMPLETE"));
        generate(meetingId, "k2", true).andExpect(status().isAccepted()).andExpect(jsonPath("$.partial").value(true));
        succeed(meetingId, 1);
        assertThat(awaitStatus(meetingId, "ready").path("partial").asBoolean()).isTrue();
    }

    @Test
    void failedRegeneration_keepsLastReadyDraftUsable() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        generated(meetingId, "k1");
        generate(meetingId, "k2", false).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("generating"));
        fail(meetingId, 2, "pipeline_error: private-provider-detail");

        // 생성 실패는 HTTP 오류가 아니라 그 버전의 failed 상태로 알린다(원문 오류는 노출하지 않는다).
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
                .isEqualTo(1);  // 복제본은 아래에서 만든다
        assertThat(markdownOf(first)).startsWith("# 출시 회의\n\n## 요약").doesNotContain("s1_a");
        JsonNode created = listedDocument(first);
        assertThat(created.path("ai_origin").asText()).isEqualTo("meeting_notes");
        assertThat(created.path("last_ai_change").isNull()).isTrue();
        // 복제본도 AI 결과물이므로 원본의 AI 출처를 잇는다.
        String copy = json(mockMvc.perform(post("/api/workspaces/" + workspaceId + "/documents/" + first + "/duplicate")
                .header("Authorization", bearer()).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().is2xxSuccessful())).path("id").asText();
        assertThat(listedDocument(copy).path("ai_origin").asText()).isEqualTo("meeting_notes");
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
    void append_mergesOnceEvenAfterDocumentChanged() throws Exception {
        String documentId = createDocument("# 주간 회의\n\n- 기존 본문");
        String meetingId = meetingWithTranscript(documentId, "finished");
        generated(meetingId, "k1");

        JsonNode preview = json(mockMvc.perform(post(notesUrl(meetingId) + "/1/append-preview")
                .header("Authorization", bearer())).andExpect(status().isOk()));
        long base = preview.path("base_revision").asLong();
        assertThat(preview.path("markdown").asText()).startsWith("# 주간 회의\n\n- 기존 본문\n\n# 출시 회의\n");

        // 미리보기 이후 다른 곳에서 문서가 바뀌어도 거절하지 않고 지금 본문 끝에 덧붙인다(자동 병합, ADR 0032).
        saveContent(documentId, "# 주간 회의\n\n- 다른 곳에서 수정", base);
        long fresh = base + 1;
        apply(meetingId, 1, "a1", appendBody(base)).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied.mode").value("append"));
        apply(meetingId, 1, "a1", appendBody(base)).andExpect(status().isOk());  // 같은 요청 재시도
        apply(meetingId, 1, "a1", "{\"mode\":\"append\",\"base_revision\":" + base + ",\"markdown\":\"# 다른 회의록\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        String saved = markdownOf(documentId);
        assertThat(saved).startsWith("# 주간 회의\n\n- 다른 곳에서 수정\n\n# 출시 회의\n");
        assertThat(saved.split("# 출시 회의", -1)).hasSize(2);  // 두 번 붙지 않았다

        // 사용자가 만든 문서라 ai_origin은 없고, 회의록을 덧붙인 버전이 AI 변경으로 보인다.
        JsonNode listed = listedDocument(documentId);
        assertThat(listed.path("ai_origin").isNull()).isTrue();
        assertThat(listed.path("last_ai_change").path("source").asText()).isEqualTo("meeting_notes");
        assertThat(listed.path("last_ai_change").path("version").asLong()).isEqualTo(fresh + 1);
        assertThat(listed.path("last_ai_change").path("operation_id").isNull()).isTrue();
        JsonNode versions = json(mockMvc.perform(get("/api/workspaces/" + workspaceId + "/documents/" + documentId + "/versions")
                .header("Authorization", bearer())).andExpect(status().isOk())).path("versions");
        assertThat(versions.get(0).path("source").asText()).isEqualTo("meeting_notes");
        assertThat(versions.get(1).path("source").asText()).isEqualTo("user");
    }

    @Test
    void olderDraftAndOtherUser_areRejected() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        generated(meetingId, "k1");
        generated(meetingId, "k2");
        apply(meetingId, 1, "a1", "{\"mode\":\"create\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_NOTES_OUTDATED"));

        String other = "user_" + UUID.randomUUID().toString().substring(0, 8);
        redisTemplate.opsForValue().set("authz:role:v2:" + workspaceId + ":" + other, "{\"role\":\"OWNER\",\"permissions\":[]}");
        mockMvc.perform(get(notesUrl(meetingId))
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(other, other + "@x.com")))
                .andExpect(status().isNotFound());
    }

    @Test
    void longMeeting_bundlesUtterancesUnderAiSegmentLimit() throws Exception {
        String meetingId = meetingWithManyUtterances(3_000);
        generated(meetingId, "k1");

        JsonNode sent = command(meetingId, 1).path("segments");
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
    void generate_returnsBeforeResultAndPollingSeesResult() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        grant();

        // 결과 이벤트가 오기 전에도 요청은 202로 끝난다.
        generate(meetingId, "k1", false).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("generating"));
        mockMvc.perform(get(notesUrl(meetingId)).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("generating"));

        succeed(meetingId, 1);
        assertThat(awaitStatus(meetingId, "ready").path("display_name").asText()).isEqualTo("출시 회의");
    }

    @Test
    void failedEvent_keepsAiCodesAndMapsOtherErrors() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        grant();
        generate(meetingId, "k1", false).andExpect(status().isAccepted());
        fail(meetingId, 1, "MEETING_NOTES_UNAVAILABLE");
        assertThat(awaitStatus(meetingId, "failed").path("error_code").asText()).isEqualTo("MEETING_NOTES_UNAVAILABLE");

        generate(meetingId, "k2", false).andExpect(status().isAccepted());
        fail(meetingId, 2, "anything_else");
        assertThat(awaitStatus(meetingId, "failed").path("error_code").asText()).isEqualTo("MEETING_NOTES_FAILED");
    }

    @Test
    void duplicateAndLateEvents_areNoOps() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        JsonNode first = generated(meetingId, "k1");
        succeed(meetingId, 1);  // 재전달
        fail(meetingId, 1, "late_failure");  // 이미 끝난 run의 늦은 실패
        assertThat(json(mockMvc.perform(get(notesUrl(meetingId)).header("Authorization", bearer()))))
                .isEqualTo(first);

        // 정리된(failed) 행에 늦게 온 성공 이벤트는 되살리지 않는다.
        generate(meetingId, "k2", false).andExpect(status().isAccepted());
        jdbc.update("UPDATE meeting_notes SET updated_at = now() - interval '31 minutes' "
                + "WHERE meeting_id = ? AND version = 2", meetingId);
        assertThat(awaitStatus(meetingId, "failed").path("error_code").asText()).isEqualTo("MEETING_NOTES_TIMEOUT");
        succeed(meetingId, 2);
        assertThat(awaitStatus(meetingId, "failed").path("version").asInt()).isEqualTo(2);
    }

    @Test
    void insufficientCredit_failsVersionWithoutRegisteringRun() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");

        generate(meetingId, "k1", false).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.error_code").value("INSUFFICIENT_CREDIT"));

        String runId = runId(meetingId, 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE id = ?", Integer.class, runId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_command_outbox WHERE run_id = ?", Integer.class, runId))
                .isZero();
    }

    @Test
    void generate_writesRowReservationAndCommandAtomically() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        String other = meetingWithTranscript(null, "finished");
        grant();
        // 크레딧 예약 뒤 outbox 저장에서 실패하게 한다(이 워크스페이스 명령만).
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_meeting_notes_outbox() RETURNS trigger AS $$
                BEGIN
                  IF NEW.message_key = '%s' THEN RAISE EXCEPTION 'outbox down'; END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql""".formatted(workspaceId));
        jdbc.execute("CREATE TRIGGER fail_meeting_notes_outbox BEFORE INSERT ON ai_command_outbox "
                + "FOR EACH ROW EXECUTE FUNCTION fail_meeting_notes_outbox()");
        try {
            generate(meetingId, "k1", false).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("failed"))
                    .andExpect(jsonPath("$.error_code").value("MEETING_NOTES_FAILED"));
        } finally {
            jdbc.execute("DROP TRIGGER fail_meeting_notes_outbox ON ai_command_outbox");
            jdbc.execute("DROP FUNCTION fail_meeting_notes_outbox()");
        }

        String runId = runId(meetingId, 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE id = ?", Integer.class, runId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries WHERE run_id = ?", Integer.class, runId))
                .isZero();
        // generating 행이 남지 않아 같은 워크스페이스의 다음 생성이 막히지 않는다.
        generate(other, "k2", false).andExpect(status().isAccepted());
        // generating 행·크레딧 예약·outbox 명령은 같은 트랜잭션(xmin)에서 쓴다.
        String otherRun = runId(other, 1);
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT xmin::text FROM meeting_notes WHERE ai_run_id = ?
                UNION SELECT DISTINCT xmin::text FROM ai_command_outbox WHERE run_id = ?
                UNION SELECT DISTINCT xmin::text FROM credit_entries WHERE run_id = ?
                """, String.class, otherRun, otherRun, otherRun)).hasSize(1);
    }

    @Test
    void oneGenerationPerWorkspace_otherWorkspaceUnaffected() throws Exception {
        String first = meetingWithTranscript(null, "finished");
        String second = meetingWithTranscript(null, "finished");
        grant();
        generate(first, "k1", false).andExpect(status().isAccepted());
        generate(second, "k2", false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_NOTES_BUSY"));
        generate(first, "k1", false).andExpect(status().isAccepted());  // 같은 키 재시도는 BUSY가 아니다

        succeed(first, 1);  // 끝나면 자리가 풀린다
        awaitStatus(first, "ready");
        generate(second, "k2", false).andExpect(status().isAccepted());

        // 다른 워크스페이스는 영향이 없다.
        String firstWorkspace = workspaceId;
        setUp();
        String other = meetingWithTranscript(null, "finished");
        grant();
        generate(other, "k3", false).andExpect(status().isAccepted());
        assertThat(firstWorkspace).isNotEqualTo(workspaceId);
    }

    @Test
    void concurrentRequestsInSameWorkspace_admitOne() throws Exception {
        String first = meetingWithTranscript(null, "finished");
        String second = meetingWithTranscript(null, "finished");
        grant();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (String meeting : java.util.List.of(first, second)) {
            results.add(pool.submit(() -> {
                start.await();
                return generate(meeting, "key-" + meeting, false).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        java.util.List<Integer> statuses = new java.util.ArrayList<>();
        for (var result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(202, 409);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM meeting_notes n JOIN meetings m ON m.id = n.meeting_id
                WHERE m.workspace_id = ? AND n.status = 'generating'
                """, Integer.class, workspaceId)).isEqualTo(1);
    }

    @Test
    void expiry_failsNoteAndRequestsAiCancellation() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        grant();
        generate(meetingId, "k1", false).andExpect(status().isAccepted());
        jdbc.update("UPDATE meeting_notes SET updated_at = now() - interval '31 minutes' WHERE meeting_id = ?", meetingId);

        assertThat(awaitStatus(meetingId, "failed").path("error_code").asText()).isEqualTo("MEETING_NOTES_TIMEOUT");
        assertCancellationRequested(runId(meetingId, 1));
    }

    @Test
    void cancel_byOwner_releasesSlotAndIgnoresLateResult() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        String second = meetingWithTranscript(null, "finished");
        grant();
        generate(meetingId, "k1", false).andExpect(status().isAccepted());

        String other = "user_" + UUID.randomUUID().toString().substring(0, 8);
        redisTemplate.opsForValue().set("authz:role:v2:" + workspaceId + ":" + other, "{\"role\":\"OWNER\",\"permissions\":[]}");
        mockMvc.perform(post(notesUrl(meetingId) + "/cancel")
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(other, other + "@x.com")))
                .andExpect(status().isNotFound());
        assertThat(awaitStatus(meetingId, "generating").path("version").asInt()).isEqualTo(1);

        mockMvc.perform(post(notesUrl(meetingId) + "/cancel").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.error_code").value("MEETING_NOTES_CANCELLED"));
        assertCancellationRequested(runId(meetingId, 1));
        mockMvc.perform(post(notesUrl(meetingId) + "/cancel").header("Authorization", bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_NOTES_NOT_GENERATING"));

        succeed(meetingId, 1);  // 취소 뒤 늦게 온 성공은 되살리지 않는다
        assertThat(awaitStatus(meetingId, "failed").path("error_code").asText()).isEqualTo("MEETING_NOTES_CANCELLED");
        generate(second, "k2", false).andExpect(status().isAccepted());  // 자리가 풀렸다
    }

    @Test
    void expiry_freesWorkspaceSlotForOtherMeeting() throws Exception {
        String first = meetingWithTranscript(null, "finished");
        String second = meetingWithTranscript(null, "finished");
        grant();
        generate(first, "k1", false).andExpect(status().isAccepted());
        generate(second, "k2", false).andExpect(status().isConflict());
        jdbc.update("UPDATE meeting_notes SET updated_at = now() - interval '31 minutes' WHERE meeting_id = ?", first);

        generate(second, "k2", false).andExpect(status().isAccepted());  // 조회 없이도 만료를 정리한다
        assertThat(jdbc.queryForObject("SELECT error_code FROM meeting_notes WHERE meeting_id = ?", String.class, first))
                .isEqualTo("MEETING_NOTES_TIMEOUT");
    }

    @Test
    void sweep_expiresStaleNoteWithoutAnyRequest() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        grant();
        generate(meetingId, "k1", false).andExpect(status().isAccepted());
        jdbc.update("UPDATE meeting_notes SET updated_at = now() - interval '31 minutes' WHERE meeting_id = ?", meetingId);

        notesService.sweepExpired();

        assertThat(jdbc.queryForObject("SELECT error_code FROM meeting_notes WHERE meeting_id = ?", String.class, meetingId))
                .isEqualTo("MEETING_NOTES_TIMEOUT");
        assertCancellationRequested(runId(meetingId, 1));
    }

    @Test
    void cancelRequestedRun_advancesToCancelledAndQueuesUsageCollection() throws Exception {
        String meetingId = meetingWithTranscript(null, "finished");
        grant();
        generate(meetingId, "k1", false).andExpect(status().isAccepted());
        String runId = runId(meetingId, 1);
        when(pipeline.cancel(eq(runId), eq(workspaceId), eq(userId), any())).thenReturn(
                new fruition.core.aitask.repository.PipelineTaskCancellationClient.TaskStatus(runId, "cancelled", null));

        mockMvc.perform(post(notesUrl(meetingId) + "/cancel").header("Authorization", bearer())).andExpect(status().isOk());

        for (int i = 0; i < 100 && !"cancelled".equals(jdbc.queryForObject(
                "SELECT status FROM ai_task_runs WHERE id = ?", String.class, runId)); i++) {
            Thread.sleep(100);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task_runs WHERE id = ?", String.class, runId))
                .isEqualTo("cancelled");
        // 수집 큐 행은 처리되면 지워지므로, 큐에 넣는 전이가 일어났음은 취소 완료 상태로 확인한다.
    }

    // ---------- helpers

    /** 생성은 비동기다. 202로 받은 뒤 성공 이벤트를 주입하고 조회로 반영을 확인한다. */
    private JsonNode generated(String meetingId, String key) throws Exception {
        grant();
        generate(meetingId, key, false).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("generating"));
        succeed(meetingId, jdbc.queryForObject("SELECT max(version) FROM meeting_notes WHERE meeting_id = ?",
                Integer.class, meetingId));
        return awaitStatus(meetingId, "ready");
    }

    /** enforce에서 명령 예약이 통과하도록 잔액을 넣는다. */
    private void grant() {
        credits.post(userId, "grant", 10_000_000, "grant:" + UUID.randomUUID(), "테스트");
    }

    private String runId(String meetingId, int version) {
        return jdbc.queryForObject("SELECT ai_run_id FROM meeting_notes WHERE meeting_id = ? AND version = ?",
                String.class, meetingId, version);
    }

    /** enqueue가 ai_task_runs에 남긴 명령(outbox payload와 같다). */
    private JsonNode command(String meetingId, int version) throws Exception {
        return objectMapper.readTree(jdbc.queryForObject("SELECT command::text FROM ai_task_runs WHERE id = ?",
                String.class, runId(meetingId, version)));
    }

    private void succeed(String meetingId, int version) throws Exception {
        succeed(meetingId, version, DRAFT);
    }

    private void succeed(String meetingId, int version, String draft) throws Exception {
        consumer.consume("{\"event_id\":\"e-" + UUID.randomUUID() + "\",\"kind\":\"meeting_notes\",\"run_id\":\""
                + runId(meetingId, version) + "\",\"status\":\"succeeded\",\"payload\":" + draft + "}");
    }

    private void fail(String meetingId, int version, String error) throws Exception {
        consumer.consume("{\"event_id\":\"e-" + UUID.randomUUID() + "\",\"kind\":\"meeting_notes\",\"run_id\":\""
                + runId(meetingId, version) + "\",\"status\":\"failed\",\"error\":\"" + error + "\"}");
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

    private JsonNode listedDocument(String documentId) throws Exception {
        for (JsonNode document : json(mockMvc.perform(get("/api/workspaces/" + workspaceId + "/documents")
                .header("Authorization", bearer())).andExpect(status().isOk())).path("documents")) {
            if (documentId.equals(document.path("id").asText())) {
                return document;
            }
        }
        throw new AssertionError("문서 목록에 없다: " + documentId);
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

    /**
     * 취소를 요청했는지는 run 상태로 확인한다. CI에서는 캐시된 여러 Spring context의 resume() 스케줄러가 같은 DB를
     * 공유해, 다른 context가 먼저 run을 집어 자기 pipeline mock을 부를 수 있다. 실제 진행은
     * cancelRequestedRun_advancesToCancelledAndQueuesUsageCollection이 직접 검증한다.
     */
    private void assertCancellationRequested(String runId) {
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task_runs WHERE id = ?", String.class, runId))
                .isIn("cancel_requested", "rolling_back", "cancelled");
    }

}
