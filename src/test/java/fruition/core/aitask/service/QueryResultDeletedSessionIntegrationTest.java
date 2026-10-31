package fruition.core.aitask.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.repository.ChatSessionRepository;
import fruition.core.chat.service.ChatTurnRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 세션 삭제 뒤 도착한 질의 결과(#10). 예전에는 반영이 예외로 rollback되어 receipt가 남지 않았고,
 * consumer가 같은 레코드를 무한 재시도해 같은 파티션의 다른 사용자 결과가 전달되지 않았다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class QueryResultDeletedSessionIntegrationTest {

    @Autowired AiTaskResultApplier applier;
    @Autowired ChatSessionRepository sessions;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatTurnRecorder recorder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @ParameterizedTest
    @ValueSource(strings = {"succeeded", "failed"})
    void resultForDeletedSessionCommitsTerminalReceiptAndRedeliveryDoesNotThrow(String status) throws Exception {
        String runId = "query-deleted-" + UUID.randomUUID();
        String sessionId = startQuery(runId);
        sessions.deleteById(sessionId);

        var projection = applier.applyQuery(event(runId, sessionId, status));

        assertThat(projection.error()).isEqualTo("cancelled");
        assertThat(receiptCount(runId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task_runs WHERE id = ?", String.class, runId))
                .isEqualTo("completed");

        // 재전달돼도 예외 없이 같은 종료로 끝나 offset이 넘어간다.
        var redelivered = applier.applyQuery(event(runId, sessionId, status));

        assertThat(redelivered.error()).isEqualTo("cancelled");
        assertThat(receiptCount(runId)).isEqualTo(1);
    }

    /** 대조군: 세션이 살아 있으면 assistant 메시지가 답변으로 완료된다. */
    @Test
    void resultForLiveSessionCompletesAssistantMessage() throws Exception {
        String runId = "query-live-" + UUID.randomUUID();
        String sessionId = startQuery(runId);
        try {
            var projection = applier.applyQuery(event(runId, sessionId, "succeeded"));

            assertThat(projection.error()).isNull();
            assertThat(projection.response().assistantMessage().content()).isEqualTo("답변");
            var assistant = messages.findById("assistant-" + runId).orElseThrow();
            assertThat(assistant.getStatus()).isEqualTo("completed");
            assertThat(receiptCount(runId)).isEqualTo(1);
        } finally {
            sessions.deleteById(sessionId);
        }
    }

    private String startQuery(String runId) {
        String sessionId = "session-" + runId;
        jdbc.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind) VALUES (?, 'ws', 'user', 'query')",
                runId);
        sessions.save(new ChatSession(sessionId, "ws", "user", null));
        recorder.createPendingPair(sessionId, "pair-" + runId, "user-" + runId, "assistant-" + runId,
                "질문", Instant.now());
        recorder.assignRun("assistant-" + runId, runId);
        return sessionId;
    }

    private Integer receiptCount(String runId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ai_task_result_receipts WHERE run_id = ? AND task_kind = 'query'",
                Integer.class, runId);
    }

    private JsonNode event(String runId, String sessionId, String status) {
        var root = mapper.createObjectNode();
        root.put("event_id", runId + ":" + status);
        root.put("run_id", runId);
        root.put("kind", "query");
        root.put("status", status);
        var request = root.putObject("request");
        request.put("session_id", sessionId);
        request.put("question", "질문");
        var context = request.putObject("message_context");
        context.put("pairId", "pair-" + runId);
        context.put("userMessageId", "user-" + runId);
        context.put("assistantMessageId", "assistant-" + runId);
        context.put("createdAt", "2026-10-31T00:00:00Z");
        if ("succeeded".equals(status)) {
            var payload = root.putObject("payload");
            payload.put("answer", "답변");
            payload.putArray("related_pages");
            payload.putArray("evidence_snippets");
            var graph = payload.putObject("graph_context");
            graph.putArray("nodes");
            graph.putArray("edges");
            payload.putArray("traversal_paths");
        } else {
            root.put("error", "provider_unavailable");
        }
        return root;
    }
}
