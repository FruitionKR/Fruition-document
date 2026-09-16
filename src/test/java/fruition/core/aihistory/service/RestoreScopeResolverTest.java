package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.repository.OperationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 복구 범위 결정. 사용자가 고르지 않고 지목한 작업 하나로 정해진다.
 *
 * <p>"이 작업 되돌리기"라서 지목한 작업 자신도 취소 대상이다. lint와 같은 규칙이다.
 */
@ExtendWith(MockitoExtension.class)
class RestoreScopeResolverTest {

    private static final String WORKSPACE = "ws_1";
    private static final String USER = "user_1";
    private static final Instant T2 = Instant.parse("2026-07-28T10:00:00Z");

    @Mock OperationLogRepository operationLogRepository;

    private RestoreScopeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new RestoreScopeResolver(operationLogRepository);
    }

    @Test
    @DisplayName("지목한 작업과 그 이후 같은 문서의 ingest를 전부 취소한다")
    void cancelsTargetAndLaterOperationsOfSameDocument() {
        OperationLog target = ingest("op_a2", "doc_A", T2);
        when(operationLogRepository.findByTargetDocumentAfter(
                        "doc_A", T2, "op_a2", OperationType.ingest))
                .thenReturn(List.of(
                        ingest("op_a3", "doc_A", T2.plusSeconds(3600)),
                        ingest("op_a4", "doc_A", T2.plusSeconds(7200))));

        assertThat(resolver.resolve(target)).containsExactly("op_a2", "op_a3", "op_a4");
    }

    @Test
    @DisplayName("같은 시각 작업이 조회에 섞여 들어와도 중복되지 않는다")
    void doesNotDuplicateTarget() {
        OperationLog target = ingest("op_a2", "doc_A", T2);
        when(operationLogRepository.findByTargetDocumentAfter(any(), any(), any(), any()))
                .thenReturn(List.of(target, ingest("op_a3", "doc_A", T2.plusSeconds(3600))));

        assertThat(resolver.resolve(target)).containsExactly("op_a2", "op_a3");
    }

    @Test
    @DisplayName("마지막 작업을 지목하면 그것만 취소한다")
    void cancelsOnlyTargetWhenNothingFollows() {
        OperationLog target = ingest("op_a4", "doc_A", T2);
        when(operationLogRepository.findByTargetDocumentAfter(any(), any(), any(), any()))
                .thenReturn(List.of(target));

        assertThat(resolver.resolve(target)).containsExactly("op_a4");
    }

    @Test
    @DisplayName("같은 시각에 만들어진 이후 작업도 놓치지 않도록 자신의 operationId를 커서로 넘긴다")
    void passesOwnOperationIdAsTieBreakCursor() {
        // createdAt만으로는 같은 밀리초에 생긴 다음 작업이 커서 비교(>)에서 빠질 수 있다.
        // (createdAt, operationId) 복합 커서로 결정적으로 가르려면 자신의 operationId를
        // 커서 파라미터로 넘겨야 한다. 실제 동시각 포함 여부는 리포지토리 쿼리 몫이라
        // 여기서는 이 메서드가 그 값을 정확히 전달하는지만 확인한다.
        OperationLog target = ingest("op_a2", "doc_A", T2);
        when(operationLogRepository.findByTargetDocumentAfter(any(), any(), any(), any()))
                .thenReturn(List.of());

        resolver.resolve(target);

        verify(operationLogRepository).findByTargetDocumentAfter(
                "doc_A", T2, "op_a2", OperationType.ingest);
    }

    @Test
    @DisplayName("lint는 원문 문서가 없어 그 작업 하나만 취소한다")
    void lintCancelsItselfOnly() {
        OperationLog lint = OperationLog.processing(
                "op_lint", WORKSPACE, USER, OperationType.lint, null, T2);

        assertThat(resolver.resolve(lint)).isEqualTo(Set.of("op_lint"));
        // 문서 범위 조회를 시도하지 않는다.
        verify(operationLogRepository, never())
                .findByTargetDocumentAfter(any(), any(), any(), eq(OperationType.ingest));
    }

    private OperationLog ingest(String id, String documentId, Instant at) {
        return OperationLog.processing(id, WORKSPACE, USER, OperationType.ingest, documentId, at);
    }
}
