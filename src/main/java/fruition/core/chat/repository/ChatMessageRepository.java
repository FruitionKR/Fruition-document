package fruition.core.chat.repository;

import fruition.core.chat.domain.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {

    /**
     * 세션의 메시지를 대화 순서대로 읽는다.
     *
     * <p>한 문답의 질문과 답변은 같은 시각으로 저장된다. created_at만으로 정렬하면 동점이라
     * 순서가 정해지지 않아 답변이 질문 위에 뜬다. 문답 안에서는 질문이 먼저라고 못박는다.
     */
    @Query("""
            SELECT m FROM ChatMessage m
            WHERE m.session.id = :sessionId
            ORDER BY m.createdAt ASC, m.pairId ASC,
                     CASE WHEN m.role = 'user' THEN 0 ELSE 1 END ASC
            """)
    List<ChatMessage> findAllBySessionIdInTurnOrder(@Param("sessionId") String sessionId);

    /**
     * 최근 메시지만 뒤에서부터 읽는다. AI 맥락은 마지막 몇 문답이면 충분한데 세션 안의 메시지
     * 수에는 상한이 없어, 전체를 읽으면 대화가 길어질수록 턴마다 읽는 양이 계속 커진다.
     *
     * <p>정렬이 뒤집혀 있으므로 호출부가 되집어야 대화 순서가 된다.
     */
    @Query("""
            SELECT m FROM ChatMessage m
            WHERE m.session.id = :sessionId
            ORDER BY m.createdAt DESC, m.pairId DESC,
                     CASE WHEN m.role = 'user' THEN 0 ELSE 1 END DESC
            """)
    List<ChatMessage> findRecentBySessionId(@Param("sessionId") String sessionId, Pageable pageable);

    /** 사용자가 고른 문답만 대화 순서로 읽는다. 세션으로 범위를 좁혀 남의 pair는 조회되지 않는다. */
    @Query("""
            SELECT m FROM ChatMessage m
            WHERE m.session.id = :sessionId AND m.pairId IN :pairIds
            ORDER BY m.createdAt ASC, m.pairId ASC,
                     CASE WHEN m.role = 'user' THEN 0 ELSE 1 END ASC
            """)
    List<ChatMessage> findByPairIdsInTurnOrder(@Param("sessionId") String sessionId,
                                               @Param("pairIds") Collection<String> pairIds);
}
