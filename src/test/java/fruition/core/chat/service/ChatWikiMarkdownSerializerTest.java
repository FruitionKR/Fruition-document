package fruition.core.chat.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatWikiMarkdownSerializerTest {

    private final ChatWikiMarkdownSerializer serializer = new ChatWikiMarkdownSerializer();

    private static final Instant T1 = Instant.parse("2026-07-07T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-07-07T10:00:01Z");
    private static final Instant T3 = Instant.parse("2026-07-07T10:01:00Z");
    private static final Instant T4 = Instant.parse("2026-07-07T10:01:01Z");

    private ChatMessage message(ChatSession session, String id, String pairId, String role,
                                String content, String status, Instant createdAt) {
        return new ChatMessage(id, session, pairId, role, content, status, createdAt, null);
    }

    @Test
    @DisplayName("문답을 Q/A 단위로 직렬화하고 provenance는 본문이 아닌 block에만 담는다")
    void serializesPairInContractFormat() {
        ChatSession session = new ChatSession("session_1", "ws_1", "user_1", "제목");
        List<ChatMessage> messages = List.of(
                message(session, "m1", "pair_1", "user", "LangSmith 연결은 어디서 봐?", "completed", T1),
                message(session, "m2", "pair_1", "assistant", "traces 화면에서 확인합니다.", "completed", T2)
        );

        ChatWikiMarkdownSerializer.ChatWikiSource source = serializer.serialize(session, messages);

        assertThat(source.markdown()).startsWith("Q : LangSmith 연결은 어디서 봐?");
        assertThat(source.markdown()).contains("Q : LangSmith 연결은 어디서 봐?");
        assertThat(source.markdown()).contains("A : traces 화면에서 확인합니다.");
        assertThat(source.markdown()).doesNotContain("session_1").doesNotContain("pair_1");
        // 렌더링에서 Q/A가 두 줄로 보이도록 질문 줄 끝에 hard break(공백 2칸)를 남긴다.
        assertThat(source.markdown()).contains("어디서 봐?  \nA : traces");
        assertThat(source.blocks()).singleElement().satisfies(block -> {
            assertThat(block.blockId()).isEqualTo("session_1:pair_1");
            assertThat(block.text()).isEqualTo("Q : LangSmith 연결은 어디서 봐?  \nA : traces 화면에서 확인합니다.");
        });
    }

    @Test
    @DisplayName("여러 문답을 대화 순서대로 직렬화하고 문답 사이는 빈 줄로 구분한다")
    void serializesMultiplePairsInOrder() {
        ChatSession session = new ChatSession("session_1", "ws_1", "user_1", "제목");
        List<ChatMessage> messages = List.of(
                message(session, "m1", "pair_1", "user", "질문1", "completed", T1),
                message(session, "m2", "pair_1", "assistant", "답변1", "completed", T2),
                message(session, "m3", "pair_2", "user", "질문2", "completed", T3),
                message(session, "m4", "pair_2", "assistant", "답변2", "completed", T4)
        );

        ChatWikiMarkdownSerializer.ChatWikiSource source = serializer.serialize(session, messages);

        assertThat(source.blocks()).extracting(ChatWikiMarkdownSerializer.ChatSourceBlock::blockId)
                .containsExactly("session_1:pair_1", "session_1:pair_2");
        // 문답 사이 빈 줄 구분
        assertThat(source.markdown()).contains("A : 답변1\n\nQ : 질문2");
        assertThat(source.markdown()).doesNotContain("session_1");
    }

    @Test
    @DisplayName("user 또는 assistant가 완료되지 않은 불완전 문답은 제외한다")
    void skipsIncompletePairs() {
        ChatSession session = new ChatSession("session_1", "ws_1", "user_1", "제목");
        List<ChatMessage> messages = List.of(
                message(session, "m1", "pair_1", "user", "정상질문", "completed", T1),
                message(session, "m2", "pair_1", "assistant", "정상답변", "completed", T2),
                message(session, "m3", "pair_2", "user", "질문만있음", "completed", T3),
                message(session, "m4", "pair_2", "assistant", "실패답변", "failed", T4)
        );

        ChatWikiMarkdownSerializer.ChatWikiSource source = serializer.serialize(session, messages);

        assertThat(source.blocks()).extracting(ChatWikiMarkdownSerializer.ChatSourceBlock::blockId)
                .containsExactly("session_1:pair_1");
        assertThat(source.markdown()).contains("정상질문");
        assertThat(source.markdown()).doesNotContain("질문만있음").doesNotContain("실패답변");
    }

    @Test
    @DisplayName("문답 1쌍 안의 빈 줄은 단일 개행으로 접는다")
    void collapsesBlankLinesWithinPair() {
        ChatSession session = new ChatSession("session_1", "ws_1", "user_1", "제목");
        List<ChatMessage> messages = List.of(
                message(session, "m1", "pair_1", "user", "질문", "completed", T1),
                message(session, "m2", "pair_1", "assistant", "줄1\n\n줄2", "completed", T2)
        );

        ChatWikiMarkdownSerializer.ChatWikiSource source = serializer.serialize(session, messages);

        assertThat(source.markdown()).contains("A : 줄1\n줄2");
        assertThat(source.markdown()).doesNotContain("줄1\n\n줄2");
        assertThat(source.blocks().get(0).text()).contains("A : 줄1\n줄2");
    }
}
