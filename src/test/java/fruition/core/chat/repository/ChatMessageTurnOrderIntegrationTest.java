package fruition.core.chat.repository;

import fruition.TestcontainersConfiguration;
import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatSession;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한 문답의 질문과 답변은 같은 시각으로 저장된다. created_at만으로 정렬하면 동점이라 순서가
 * 정해지지 않고, 실제로 화면에 답변이 질문 위로 뜬 적이 있다. Mockito로는 이 순서를 확인할 수
 * 없어 실제 Postgres에서 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ChatMessageTurnOrderIntegrationTest {

    @Autowired ChatSessionRepository chatSessionRepository;
    @Autowired ChatMessageRepository chatMessageRepository;
    @Autowired EntityManager entityManager;

    /** 답변을 먼저 저장해 삽입 순서가 정렬을 대신 결정하지 못하게 한다. */
    private ChatSession givenThreeTurns(String sessionId) {
        ChatSession session = new ChatSession(sessionId, "ws_turn_order", "user_turn_order", null);
        chatSessionRepository.save(session);

        Instant first = Instant.parse("2026-08-16T00:00:00Z");
        chatMessageRepository.saveAll(List.of(
                new ChatMessage(sessionId + "_assistant_p3", session, "pair_3", "assistant", "셋째 답변",
                        "completed", first.plusSeconds(120), null),
                new ChatMessage(sessionId + "_user_p3", session, "pair_3", "user", "셋째 질문",
                        "completed", first.plusSeconds(120), null),
                new ChatMessage(sessionId + "_assistant_p2", session, "pair_2", "assistant", "둘째 답변",
                        "completed", first.plusSeconds(60), null),
                new ChatMessage(sessionId + "_user_p2", session, "pair_2", "user", "둘째 질문",
                        "completed", first.plusSeconds(60), null),
                new ChatMessage(sessionId + "_assistant_p1", session, "pair_1", "assistant", "첫 답변",
                        "completed", first, null),
                new ChatMessage(sessionId + "_user_p1", session, "pair_1", "user", "첫 질문",
                        "completed", first, null)));
        entityManager.flush();
        entityManager.clear();
        return session;
    }

    @Test
    void sameInstantPairKeepsQuestionBeforeAnswer() {
        givenThreeTurns("session_turn_order");

        List<ChatMessage> messages = chatMessageRepository.findAllBySessionIdInTurnOrder("session_turn_order");

        assertThat(messages).extracting(ChatMessage::getContent)
                .containsExactly("첫 질문", "첫 답변", "둘째 질문", "둘째 답변", "셋째 질문", "셋째 답변");
    }

    /**
     * AI 맥락은 이 조회로 만든다. 역순으로 읽어 호출부가 되집는 구조라, 타이브레이커 방향이
     * 하나만 어긋나도 넘어가는 문답 순서가 조용히 뒤바뀐다.
     */
    @Test
    void recentQueryReturnsNewestFirstAndReversesIntoTurnOrder() {
        givenThreeTurns("session_recent");

        List<ChatMessage> reversed = chatMessageRepository.findRecentBySessionId(
                "session_recent", PageRequest.of(0, 4));

        // 상한만큼 최신부터 온다. 가장 오래된 문답(pair_1)은 잘려 나간다.
        assertThat(reversed).extracting(ChatMessage::getContent)
                .containsExactly("셋째 답변", "셋째 질문", "둘째 답변", "둘째 질문");

        List<ChatMessage> ordered = new ArrayList<>(reversed);
        Collections.reverse(ordered);
        assertThat(ordered).extracting(ChatMessage::getContent)
                .containsExactly("둘째 질문", "둘째 답변", "셋째 질문", "셋째 답변");
    }

    @Test
    void selectedPairQueryReturnsOnlyChosenPairsInTurnOrder() {
        givenThreeTurns("session_selected");

        List<ChatMessage> messages = chatMessageRepository.findByPairIdsInTurnOrder(
                "session_selected", List.of("pair_3", "pair_1"));

        // 고른 순서가 아니라 대화 순서로 온다.
        assertThat(messages).extracting(ChatMessage::getContent)
                .containsExactly("첫 질문", "첫 답변", "셋째 질문", "셋째 답변");
    }

    /** 세션으로 범위를 좁히므로 다른 세션의 pair ID는 조회되지 않는다. */
    @Test
    void selectedPairQueryIgnoresForeignSession() {
        givenThreeTurns("session_owner");
        givenThreeTurns("session_other");

        List<ChatMessage> messages = chatMessageRepository.findByPairIdsInTurnOrder(
                "session_owner", List.of("pair_1"));

        assertThat(messages).extracting(ChatMessage::getSessionId)
                .containsOnly("session_owner");
    }
}
