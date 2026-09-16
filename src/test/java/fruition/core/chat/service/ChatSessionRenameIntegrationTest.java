package fruition.core.chat.service;

import fruition.TestcontainersConfiguration;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.dto.ChatSessionRenameRequest;
import fruition.core.chat.repository.ChatSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rename은 save()를 부르지 않고 JPA dirty checking으로 제목을 반영한다.
 * Mockito 단위 테스트는 리포지토리를 mock해 반환 DTO만 확인할 뿐 실제 UPDATE가 나갔는지는 확인하지 못한다.
 * 이 테스트는 테스트 쪽 트랜잭션 없이 호출해, 서비스 자신의 트랜잭션이 commit되며
 * Testcontainers Postgres의 행이 실제로 갱신되는지 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChatSessionRenameIntegrationTest {

    private static final String SESSION_ID = "session_rename_test";
    private static final String WORKSPACE_ID = "ws_rename_test";
    private static final String USER_ID = "user_rename_test";

    @Autowired ChatSessionService chatSessionService;
    @Autowired ChatSessionRepository chatSessionRepository;

    // 인가는 이 테스트의 관심사가 아니다. 실제 guard는 Redis projection miss 시 access-svc로 폴백한다.
    @MockBean WorkspaceAccessGuard workspaceAccessGuard;

    @AfterEach
    void cleanUp() {
        // 테스트 트랜잭션이 없어 롤백되지 않는다. 남긴 행을 직접 지운다.
        chatSessionRepository.deleteById(SESSION_ID);
    }

    @Test
    void rename_commitsUpdatedTitleToDatabase() {
        chatSessionRepository.save(new ChatSession(SESSION_ID, WORKSPACE_ID, USER_ID, "이전 제목"));

        chatSessionService.rename(WORKSPACE_ID, USER_ID, SESSION_ID, new ChatSessionRenameRequest("새 제목"));

        // 새 트랜잭션·새 영속성 컨텍스트에서 다시 읽는다. rename이 commit하지 않았다면 "이전 제목"이 남는다.
        ChatSession reloaded = chatSessionRepository.findById(SESSION_ID).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("새 제목");
    }
}
