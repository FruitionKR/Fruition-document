package fruition.core.chat.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.repository.ChatMessageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatConversationReaderTest {

    private final ChatMessageRepository repository = mock(ChatMessageRepository.class);
    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final ChatConversationReader reader = new ChatConversationReader(repository, chatSessionService);
    private final ChatSession session = mock(ChatSession.class);

    private ChatMessage message(String pairId, String role, String content, String status, int secondsOffset) {
        return new ChatMessage(role + "_" + pairId, session, pairId, role, content, status,
                Instant.parse("2026-08-16T00:00:00Z").plusSeconds(secondsOffset), null);
    }

    /**
     * 리포지터리는 대화 순서로 돌려준다. 최근 구간은 역순 조회라 뒤에서 잘라 뒤집은 결과를,
     * 선택 조회는 고른 pair만 순서대로 돌려주는 것을 흉내낸다.
     */
    private void given(ChatMessage... messages) {
        List<ChatMessage> ordered = List.of(messages);
        when(repository.findRecentBySessionId(anyString(), any())).thenAnswer(invocation -> {
            int limit = invocation.getArgument(1, Pageable.class).getPageSize();
            List<ChatMessage> recent = ordered.subList(Math.max(0, ordered.size() - limit), ordered.size());
            List<ChatMessage> reversed = new ArrayList<>(recent);
            Collections.reverse(reversed);
            return reversed;
        });
        lenient().when(repository.findByPairIdsInTurnOrder(anyString(), any())).thenAnswer(invocation -> {
            Collection<String> pairIds = invocation.getArgument(1);
            return ordered.stream().filter(message -> pairIds.contains(message.getPairId())).toList();
        });
    }

    @Test
    @DisplayName("선택이 비면 세션의 최근 완결 문답을 쓴다")
    void emptySelectionUsesRecentCompletedPairs() {
        given(message("p1", "user", "첫 질문", "completed", 0),
                message("p1", "assistant", "첫 답변", "completed", 1));

        var conversation = reader.read("session_1", List.of());

        assertThat(conversation.recentMessages()).extracting(ChatConversationReader.Message::content)
                .containsExactly("첫 질문", "첫 답변");
    }

    @Test
    @DisplayName("assistant action을 다음 Agent 턴의 참고 정보로 보존한다")
    void keepsAssistantActionAsConversationHint() {
        ChatMessage assistant = message("p1", "assistant", "날씨를 알려주세요.", "completed", 1);
        assistant.assignAgentRun("agent_1");
        assistant.completeAgentTurn("conversation_reply", "날씨를 알려주세요.");
        given(message("p1", "user", "제목을 써줘", "completed", 0), assistant);

        var conversation = reader.read("session_1", List.of());

        assertThat(conversation.recentMessages()).extracting(ChatConversationReader.Message::action)
                .containsExactly(null, "conversation_reply");
        assertThat(conversation.recentMessages()).extracting(ChatConversationReader.Message::runId)
                .containsExactly(null, "agent_1");
    }

    /** 요약은 서버가 원문을 이어붙여 만들지 않는다. pipeline이 갱신해 둔 세션의 누적 요약을 쓴다. */
    @Test
    @DisplayName("요약은 세션에 쌓인 누적 요약을 그대로 넘긴다")
    void summaryComesFromSession() {
        given(message("p1", "user", "첫 질문", "completed", 0),
                message("p1", "assistant", "첫 답변", "completed", 1));
        when(chatSessionService.contextSummary("session_1")).thenReturn("지금까지 검색 인덱싱을 다뤘다.");

        var conversation = reader.read("session_1", List.of());

        assertThat(conversation.summary()).isEqualTo("지금까지 검색 인덱싱을 다뤘다.");
    }

    @Test
    @DisplayName("누적 요약이 아직 없으면 요약을 넘기지 않는다")
    void summaryIsNullBeforePipelineBuildsOne() {
        given(message("p1", "user", "첫 질문", "completed", 0),
                message("p1", "assistant", "첫 답변", "completed", 1));

        var conversation = reader.read("session_1", List.of());

        assertThat(conversation.summary()).isNull();
    }

    @Test
    @DisplayName("선택한 문답만 담는다")
    void selectionNarrowsToChosenPairs() {
        given(message("p1", "user", "첫 질문", "completed", 0),
                message("p1", "assistant", "첫 답변", "completed", 1),
                message("p2", "user", "둘째 질문", "completed", 2),
                message("p2", "assistant", "둘째 답변", "completed", 3));

        var conversation = reader.read("session_1", List.of("p2"));

        assertThat(conversation.recentMessages()).extracting(ChatConversationReader.Message::content)
                .containsExactly("둘째 질문", "둘째 답변");
    }

    /** ID를 받는 순간 생기는 위험이다. 세션에서 읽은 목록과 교집합만 남겨 구조적으로 막는다. */
    @Test
    @DisplayName("이 세션에 없는 pair ID는 무시한다")
    void foreignPairIdIsIgnored() {
        given(message("p1", "user", "첫 질문", "completed", 0),
                message("p1", "assistant", "첫 답변", "completed", 1));

        var conversation = reader.read("session_1", List.of("남의_pair"));

        assertThat(conversation.recentMessages()).isEmpty();
    }

    @Test
    @DisplayName("완결되지 않은 문답은 맥락에서 뺀다")
    void pendingPairIsExcluded() {
        given(message("p1", "user", "첫 질문", "completed", 0),
                message("p1", "assistant", "", "pending", 1),
                message("p2", "user", "둘째 질문", "completed", 2),
                message("p2", "assistant", "둘째 답변", "completed", 3));

        var conversation = reader.read("session_1", List.of());

        assertThat(conversation.recentMessages()).extracting(ChatConversationReader.Message::content)
                .containsExactly("둘째 질문", "둘째 답변");
    }

    /** pipeline은 메시지 내용이 1자 이상이라야 받는다. 빈 내용이 섞이면 요청 전체가 거부된다. */
    @Test
    @DisplayName("내용이 빈 메시지가 있는 문답은 맥락에서 뺀다")
    void pairWithEmptyContentIsExcluded() {
        given(message("p1", "user", "첫 질문", "completed", 0),
                message("p1", "assistant", "", "completed", 1),
                message("p2", "user", "둘째 질문", "completed", 2),
                message("p2", "assistant", "둘째 답변", "completed", 3));

        var conversation = reader.read("session_1", List.of());

        assertThat(conversation.recentMessages()).extracting(ChatConversationReader.Message::content)
                .containsExactly("둘째 질문", "둘째 답변");
    }

    @Test
    @DisplayName("구조화 메시지는 pipeline 상한인 6개까지만 보낸다")
    void recentMessagesAreCappedAtSix() {
        ChatMessage[] messages = new ChatMessage[8];
        for (int i = 0; i < 4; i++) {
            messages[i * 2] = message("p" + i, "user", "질문" + i, "completed", i * 2);
            messages[i * 2 + 1] = message("p" + i, "assistant", "답변" + i, "completed", i * 2 + 1);
        }
        given(messages);

        var conversation = reader.read("session_1", List.of());

        assertThat(conversation.recentMessages()).hasSize(6);
        assertThat(conversation.recentMessages().get(0).content()).isEqualTo("질문1");
    }
}
