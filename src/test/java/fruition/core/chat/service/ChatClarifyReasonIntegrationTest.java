package fruition.core.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.aitask.service.AiTaskResultApplier;
import fruition.core.chat.controller.ChatSessionController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** AI turn 결과가 실제 DB를 거쳐 채팅 메시지 조회 JSON까지 오는 경로. V97 컬럼과 엔티티 매핑도 여기서 확인한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChatClarifyReasonIntegrationTest {

    private static final String WORKSPACE = "ws_clarify_reason";
    private static final String USER = "user_clarify_reason";
    private static final String CODE = "markdown_document_required";

    @Autowired JdbcTemplate jdbc;
    @Autowired ChatTurnRecorder recorder;
    @Autowired AiTaskResultApplier applier;
    @Autowired ChatSessionController controller;
    @Autowired ObjectMapper mapper;
    @Autowired StringRedisTemplate redisTemplate;

    @BeforeEach
    void grantMembership() {
        redisTemplate.opsForValue().set("authz:role:v2:" + WORKSPACE + ":" + USER, "{\"role\":\"MEMBER\",\"permissions\":[]}");
    }

    @Test
    void clarifyReasonSurvivesReload() throws Exception {
        Turn turn = pendingTurn();

        assertThat(applier.applyAgent(result(turn, "e1", "\"clarify_reason\":\"" + CODE + "\",")).applied()).isTrue();

        JsonNode message = assistantMessage(turn);
        assertThat(message.path("action").asText()).isEqualTo("clarify");
        assertThat(message.path("content").asText()).isEqualTo("다시 요청해 주세요.");
        assertThat(message.path("clarify_reason").asText()).isEqualTo(CODE);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "ko, 설정 → AI 설정 → 웹 검색 기본으로 켜기",
        "en, Settings → AI settings → Turn on web search by default"
    })
    void productHelpSurvivesReload(String language, String answer) throws Exception {
        Turn turn = pendingTurn();
        var event = (com.fasterxml.jackson.databind.node.ObjectNode) result(turn, "help", "");
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) event.path("payload");
        payload.put("action", "product_help");
        payload.put("message", answer);
        ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("request")).put("response_language", language);

        assertThat(applier.applyAgent(event).error()).isNull();
        assertThat(applier.applyAgent(event).applied()).isFalse();

        JsonNode message = assistantMessage(turn);
        assertThat(message.path("status").asText()).isEqualTo("completed");
        assertThat(message.path("action").asText()).isEqualTo("product_help");
        assertThat(message.path("content").asText()).isEqualTo(answer);
        assertThat(jdbc.queryForObject("SELECT status FROM agent_apply_projections WHERE run_id = ?",
                String.class, turn.runId())).isEqualTo("ready");
    }

    /** 재전송은 첫 결과만 반영한다. 나중 이벤트가 값을 지우거나 바꾸면 화면 문구가 흔들린다. */
    @Test
    void redeliveryDoesNotChangeStoredReason() throws Exception {
        Turn turn = pendingTurn();
        applier.applyAgent(result(turn, "e1", "\"clarify_reason\":\"" + CODE + "\","));

        assertThat(applier.applyAgent(result(turn, "e1", "\"clarify_reason\":\"" + CODE + "\",")).applied()).isFalse();
        assertThat(applier.applyAgent(result(turn, "e2", "")).applied()).isFalse();
        assertThat(applier.applyAgent(result(turn, "e3", "\"clarify_reason\":\"other_reason\",")).applied()).isFalse();

        assertThat(assistantMessage(turn).path("clarify_reason").asText()).isEqualTo(CODE);
    }

    @Test
    void oldAiResponseAndMalformedValueBecomeNull() throws Exception {
        Turn old = pendingTurn();
        Turn malformed = pendingTurn();

        assertThat(applier.applyAgent(result(old, "e1", "")).error()).isNull();
        assertThat(applier.applyAgent(result(malformed, "e1", "\"clarify_reason\":{\"code\":1},")).error()).isNull();

        for (Turn turn : new Turn[] {old, malformed}) {
            JsonNode message = assistantMessage(turn);
            assertThat(message.path("status").asText()).isEqualTo("completed");
            assertThat(message.path("content").asText()).isEqualTo("다시 요청해 주세요.");
            assertThat(message.has("clarify_reason")).isTrue();
            assertThat(message.path("clarify_reason").isNull()).isTrue();
        }
    }

    /** V97 이전에 쌓인 메시지와 구 코드가 쓰는 INSERT는 컬럼을 모른다. */
    @Test
    void rowWrittenWithoutTheColumnReadsAsNull() throws Exception {
        String session = "session_" + UUID.randomUUID();
        String assistant = "assistant_" + UUID.randomUUID();
        jdbc.update("INSERT INTO chat_sessions(id, workspace_id, user_id, created_at) VALUES (?, ?, ?, now())",
                session, WORKSPACE, USER);
        jdbc.update("INSERT INTO chat_messages(id, content, created_at, pair_id, role, status, session_id, action) "
                + "VALUES (?, '예전 되묻기', now(), 'pair', 'assistant', 'completed', ?, 'clarify')", assistant, session);

        JsonNode message = messages(session).get(0);

        assertThat(message.path("id").asText()).isEqualTo(assistant);
        assertThat(message.path("action").asText()).isEqualTo("clarify");
        assertThat(message.path("clarify_reason").isNull()).isTrue();
    }

    /** 실패한 turn에는 이유 코드가 없다. payload에 붙어 와도 담기지 않는다. */
    @Test
    void failedTurnHasNoReason() throws Exception {
        Turn turn = pendingTurn();
        JsonNode failed = mapper.readTree("""
                {"event_id":"%s","run_id":"%s","kind":"agent","status":"failed","error":"provider_unavailable",
                 "request":{"message_context":{"assistant_message_id":"%s"}},
                 "payload":{"clarify_reason":"%s"}}
                """.formatted(turn.runId() + ":e1", turn.runId(), turn.assistantId(), CODE));

        applier.applyAgent(failed);

        JsonNode message = assistantMessage(turn);
        assertThat(message.path("status").asText()).isEqualTo("failed");
        assertThat(message.path("clarify_reason").isNull()).isTrue();
    }

    private Turn pendingTurn() {
        String id = UUID.randomUUID().toString();
        Turn turn = new Turn("session_" + id, "agent_" + id, "assistant_" + id);
        jdbc.update("INSERT INTO chat_sessions(id, workspace_id, user_id, created_at) VALUES (?, ?, ?, now())",
                turn.sessionId(), WORKSPACE, USER);
        recorder.createPendingAgentPair(turn.sessionId(), "pair_" + id, "user_" + id, turn.assistantId(), "고쳐 줘",
                Instant.now(), "openai", "gpt-6-luna", turn.runId(), null);
        jdbc.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind) VALUES (?, ?, ?, 'agent')",
                turn.runId(), WORKSPACE, USER);
        jdbc.update("INSERT INTO agent_apply_projections(run_id, workspace_id, user_id) VALUES (?, ?, ?)",
                turn.runId(), WORKSPACE, USER);
        return turn;
    }

    private JsonNode result(Turn turn, String eventSuffix, String reasonField) throws Exception {
        return mapper.readTree("""
                {"event_id":"%s","run_id":"%s","kind":"agent","status":"succeeded",
                 "request":{"workspace_id":"%s","user_id":"%s",
                 "message_context":{"assistant_message_id":"%s"}},
                 "payload":{%s"action":"clarify","message":"다시 요청해 주세요."}}
                """.formatted(turn.runId() + ":" + eventSuffix, turn.runId(), WORKSPACE, USER,
                turn.assistantId(), reasonField));
    }

    private JsonNode assistantMessage(Turn turn) throws Exception {
        for (JsonNode message : messages(turn.sessionId())) {
            if (turn.assistantId().equals(message.path("id").asText())) {
                return message;
            }
        }
        throw new AssertionError("assistant 메시지가 응답에 없습니다: " + turn.assistantId());
    }

    /** 프론트가 쓰는 조회 경로의 직렬화 결과를 그대로 읽는다. */
    private JsonNode messages(String sessionId) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(
                controller.getMessages(WORKSPACE, USER, sessionId).getBody())).path("messages");
    }

    private record Turn(String sessionId, String runId, String assistantId) {}
}
