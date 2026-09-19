package fruition.core.chat.service;

import fruition.TestcontainersConfiguration;
import fruition.core.aitask.service.AiTaskResultApplier;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.repository.ChatSessionRepository;
import fruition.core.chat.repository.ChatMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChatProgressIntegrationTest {
    @Autowired ChatSessionRepository sessions;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatTurnRecorder recorder;
    @Autowired AiTaskResultApplier applier;
    @Autowired ObjectMapper mapper;

    @Test
    void progressSurvivesReloadAndDeduplicatesDelivery() throws Exception {
        String sessionId = "session_progress_test";
        sessions.save(new ChatSession(sessionId, "ws_progress", "user_progress", null));
        try {
            recorder.createPendingPair(sessionId, "pair_progress", "user_progress_message",
                    "assistant_progress_message", "질문", Instant.now());
            recorder.assignRun("assistant_progress_message", "query_progress_test");
            var event = mapper.readTree("""
                    {"event_id":"progress_1","run_id":"query_progress_test",
                     "payload":{"stage":"query_evaluating","message":"답변을 검토하고 있어요."}}
                    """);
            applier.recordProgress(event);
            applier.recordProgress(event);
            var reloaded = messages.findById("assistant_progress_message").orElseThrow();
            assertThat(reloaded.getProgress()).hasSize(1);
            assertThat(reloaded.getProgress().getFirst().path("message").asText()).isEqualTo("답변을 검토하고 있어요.");
            reloaded.complete("답변");
            messages.save(reloaded);
            applier.recordProgress(mapper.readTree(event.toString().replace("progress_1", "progress_2")));
            assertThat(messages.findById(reloaded.getId()).orElseThrow().getProgress()).hasSize(1);
            var titleEvent = mapper.readTree("""
                    {"run_id":"query_progress_test","workspace_id":"ws_progress","user_id":"user_progress",
                     "payload":{"title":"첫 문답 제목"}}
                    """);
            applier.applySessionTitle(titleEvent);
            assertThat(sessions.findById(sessionId).orElseThrow().getTitle()).isEqualTo("첫 문답 제목");
            applier.applySessionTitle(mapper.readTree(titleEvent.toString().replace("첫 문답 제목", "다른 제목")));
            assertThat(sessions.findById(sessionId).orElseThrow().getTitle()).isEqualTo("첫 문답 제목");

        } finally {
            sessions.deleteById(sessionId);
        }
    }
}
