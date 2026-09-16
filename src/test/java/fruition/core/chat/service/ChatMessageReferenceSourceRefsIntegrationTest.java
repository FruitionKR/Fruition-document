package fruition.core.chat.service;

import fruition.TestcontainersConfiguration;
import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatMessageReference;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.domain.SourceRef;
import fruition.core.chat.repository.ChatMessageReferenceRepository;
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
 * Mockito 단위 테스트(QueryServiceTest)는 referenceRepository를 mock해 저장 직전 도메인만 확인할 뿐,
 * source_refs가 SourceRefListJsonConverter로 실제 DB 컬럼에 저장·조회되는 왕복은 검증하지 못한다.
 * 이 테스트는 Testcontainers Postgres에서 non-empty source_refs의 저장→재조회 왕복과
 * 응답 DTO가 의존하는 snake_case 키 저장을 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ChatMessageReferenceSourceRefsIntegrationTest {

    @Autowired ChatSessionRepository chatSessionRepository;
    @Autowired ChatMessageRepository chatMessageRepository;
    @Autowired ChatMessageReferenceRepository referenceRepository;
    @Autowired EntityManager entityManager;

    @Test
    void sourceRefs_roundTripsThroughConverterAndStoresSnakeCaseKeys() {
        // users/workspaces는 access_db 소유 — core_db에는 FK가 없어 ID만 쓰면 된다 (MSA DB 분리).
        ChatSession session = new ChatSession("session_srcrefs_test", "ws_srcrefs_test", "user_srcrefs_test", null);
        chatSessionRepository.save(session);
        ChatMessage assistantMessage = new ChatMessage("chat_srcrefs_assistant", session, "pair_srcrefs",
                "assistant", "답변", "completed", Instant.now(), null);
        chatMessageRepository.save(assistantMessage);

        // source_refs는 JSON 컬럼이라 FK 대상이 아니다. 상위 document_id는 FK(documents)라 null로 둔다.
        List<SourceRef> sourceRefs = List.of(
                new SourceRef("doc_a", "B0001"),
                new SourceRef("doc_b", "B0008"));
        ChatMessageReference reference = referenceRepository.save(new ChatMessageReference(
                assistantMessage, "source_block", null, 1, List.of("B0001"), "인용문", sourceRefs));

        entityManager.flush();
        entityManager.clear();

        // 재조회 시 converter가 JSON → List<SourceRef>로 역직렬화한다.
        ChatMessageReference reloaded = referenceRepository.findById(reference.getId()).orElseThrow();
        assertThat(reloaded.getSourceRefs()).containsExactly(
                new SourceRef("doc_a", "B0001"),
                new SourceRef("doc_b", "B0008"));

        // 저장된 JSON은 응답 DTO가 노출하는 snake_case 키를 그대로 사용해야 한다.
        String storedJson = (String) entityManager
                .createNativeQuery("SELECT source_refs FROM chat_message_references WHERE id = :id")
                .setParameter("id", reference.getId())
                .getSingleResult();
        assertThat(storedJson)
                .contains("source_document_id")
                .contains("source_block_id")
                .contains("doc_b");
    }
}
