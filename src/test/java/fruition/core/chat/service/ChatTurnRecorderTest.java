package fruition.core.chat.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.repository.ChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatTurnRecorderTest {

    @Mock ChatMessageRepository chatMessageRepository;
    @Mock ChatSessionRepository chatSessionRepository;

    private ChatTurnRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new ChatTurnRecorder(chatMessageRepository, chatSessionRepository);
    }

    @Test
    void createPendingPair_savesCompletedUserAndPendingAssistant() {
        ChatSession session = new ChatSession("session_abc123", "ws_abc123", "user_abc123", null);
        when(chatSessionRepository.findById("session_abc123")).thenReturn(Optional.of(session));
        Instant createdAt = Instant.parse("2026-07-21T01:00:00Z");

        recorder.createPendingPair(
                "session_abc123", "pair_abc123", "chat_user_abc123", "chat_assistant_abc123", "질문", createdAt,
                "gemini", "gemini-3.6-flash");

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageRepository).saveAll(captor.capture());
        verify(chatSessionRepository).save(session);

        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).getRole()).isEqualTo("user");
        assertThat(captor.getValue().get(0).getStatus()).isEqualTo("completed");
        assertThat(captor.getValue().get(0).getContent()).isEqualTo("질문");
        assertThat(captor.getValue().get(0).getAiProvider()).isEqualTo("gemini");
        assertThat(captor.getValue().get(0).getAiModel()).isEqualTo("gemini-3.6-flash");
        assertThat(captor.getValue().get(1).getRole()).isEqualTo("assistant");
        assertThat(captor.getValue().get(1).getStatus()).isEqualTo("pending");
        assertThat(captor.getValue().get(1).getContent()).isEmpty();
        assertThat(session.getLastMessageAt()).isEqualTo(createdAt);
    }

    @Test
    void markFailed_updatesExistingAssistant() {
        ChatSession session = new ChatSession("session_abc123", "ws_abc123", "user_abc123", null);
        ChatMessage assistant = new ChatMessage(
                "chat_assistant_abc123", session, "pair_abc123", "assistant", "", "pending", Instant.now(), null);
        when(chatMessageRepository.findById("chat_assistant_abc123")).thenReturn(Optional.of(assistant));

        recorder.markFailed("chat_assistant_abc123", "pipeline 실패");

        assertThat(assistant.getStatus()).isEqualTo("failed");
        assertThat(assistant.getErrorMessage()).isEqualTo("pipeline 실패");
        verify(chatMessageRepository).save(assistant);
    }

    /** error_message는 varchar(255)다. 넘치면 커밋 시점에 터져 결과 반영 트랜잭션까지 되돌린다. */
    @Test
    void markFailed_truncatesErrorToColumnLength() {
        ChatSession session = new ChatSession("session_abc123", "ws_abc123", "user_abc123", null);
        ChatMessage assistant = new ChatMessage(
                "chat_assistant_abc123", session, "pair_abc123", "assistant", "", "pending", Instant.now(), null);
        when(chatMessageRepository.findById("chat_assistant_abc123")).thenReturn(Optional.of(assistant));

        recorder.markFailed("chat_assistant_abc123", "오".repeat(400));

        assertThat(assistant.getErrorMessage()).hasSize(255);
    }

    @Test
    void recordContextSummary_storesSummaryWithTimestamp() {
        ChatSession session = new ChatSession("session_abc123", "ws_abc123", "user_abc123", null);
        when(chatSessionRepository.findById("session_abc123")).thenReturn(Optional.of(session));

        recorder.recordContextSummary("session_abc123", "지금까지 인덱싱을 다뤘다.");

        assertThat(session.getContextSummary()).isEqualTo("지금까지 인덱싱을 다뤘다.");
        assertThat(session.getContextSummaryUpdatedAt()).isNotNull();
        verify(chatSessionRepository).save(session);
    }

    /** 대화가 짧으면 pipeline이 요약을 만들지 않는다. 그때 기존 요약을 지우면 맥락이 뒤로 물러난다. */
    @Test
    void recordContextSummary_keepsPreviousSummaryWhenNothingArrives() {
        recorder.recordContextSummary("session_abc123", null);
        recorder.recordContextSummary("session_abc123", "  ");

        verify(chatSessionRepository, never()).save(any());
    }

    @Test
    void methods_joinCommandTransaction() throws NoSuchMethodException {
        Transactional createTransaction = ChatTurnRecorder.class
                .getMethod("createPendingPair", String.class, String.class, String.class, String.class,
                        String.class, Instant.class)
                .getAnnotation(Transactional.class);
        Transactional failTransaction = ChatTurnRecorder.class
                .getMethod("markFailed", String.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(createTransaction.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(failTransaction.propagation()).isEqualTo(Propagation.REQUIRED);
    }
}
