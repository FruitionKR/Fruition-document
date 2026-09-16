package fruition.core.chat.service;

import fruition.TestcontainersConfiguration;
import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatMessageReference;
import fruition.core.chat.domain.ChatMessageRelatedPage;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.repository.ChatMessageReferenceRepository;
import fruition.core.chat.repository.ChatMessageRelatedPageRepository;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.repository.ChatSessionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mockito 단위 테스트는 리포지토리 호출만 검증할 뿐, 실제 DB의 FK ON DELETE CASCADE 동작은 확인하지 못한다.
 * 이 테스트는 Testcontainers Postgres에 실제로 CASCADE 제약이 걸려 동작하는지 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ChatSessionCascadeDeleteIntegrationTest {

    @Autowired ChatSessionRepository chatSessionRepository;
    @Autowired ChatMessageRepository chatMessageRepository;
    @Autowired ChatMessageReferenceRepository referenceRepository;
    @Autowired ChatMessageRelatedPageRepository relatedPageRepository;
    @Autowired EntityManager entityManager;

    @Test
    void deletingSession_cascadesToMessagesReferencesAndRelatedPages() {
        // chat_sessions.workspace_id/user_id FK(CASCADE)를 위해 부모 행을 먼저 만든다.
        // users/workspaces는 access_db 소유 — core_db에는 FK가 없어 ID만 쓰면 된다 (MSA DB 분리).
        ChatSession session = new ChatSession("session_cascade_test", "ws_cascade_test", "user_cascade_test", null);
        chatSessionRepository.save(session);

        ChatMessage userMessage = new ChatMessage("chat_user_cascade", session, "pair_cascade",
                "user", "질문", "completed", Instant.now(), null);
        ChatMessage assistantMessage = new ChatMessage("chat_assistant_cascade", session, "pair_cascade",
                "assistant", "답변", "completed", Instant.now(), null);
        chatMessageRepository.saveAll(List.of(userMessage, assistantMessage));

        // document_id/wiki_page_id는 이 테스트의 관심사(세션 CASCADE)가 아니므로,
        // FK(SET NULL) 대상 부모를 만들지 않고 null로 둔다.
        referenceRepository.save(new ChatMessageReference(
                assistantMessage, "source_block", null, 1, List.of("B0001"), "인용문", List.of()));
        relatedPageRepository.save(new ChatMessageRelatedPage(
                assistantMessage, null, "source", "제목", "slug", 1.0, "seed_source", 0, 1));

        entityManager.flush();

        // 세션 삭제 → DB FK ON DELETE CASCADE로 메시지/참조/관련페이지까지 함께 삭제되어야 한다.
        chatSessionRepository.delete(session);
        entityManager.flush();
        entityManager.clear(); // 1차 캐시를 비워 DB에서 다시 조회하도록 강제

        assertThat(chatSessionRepository.findById("session_cascade_test")).isEmpty();
        assertThat(chatMessageRepository.findById("chat_user_cascade")).isEmpty();
        assertThat(chatMessageRepository.findById("chat_assistant_cascade")).isEmpty();
        assertThat(referenceRepository.findAllByChatMessage_IdIn(List.of("chat_assistant_cascade"))).isEmpty();
        assertThat(relatedPageRepository.findAllByChatMessage_IdIn(List.of("chat_assistant_cascade"))).isEmpty();
    }
}
