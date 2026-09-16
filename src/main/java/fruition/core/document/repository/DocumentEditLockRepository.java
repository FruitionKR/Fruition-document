package fruition.core.document.repository;

import fruition.core.document.domain.DocumentEditLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface DocumentEditLockRepository extends JpaRepository<DocumentEditLock, String> {

    /**
     * 잠금 획득/갱신. 비어 있거나(신규), 기존 잠금이 만료됐거나, 보유자가 요청자 본인일 때만 성립한다.
     * 성립하면 1, 다른 사용자가 유효한 잠금을 보유 중이면 0을 반환한다. (원자적 조건부 upsert)
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO document_edit_locks(document_id, holder_user_id, acquired_at, last_heartbeat_at, expires_at)
            VALUES (:documentId, :userId, :now, :now, :expiresAt)
            ON CONFLICT (document_id) DO UPDATE
                SET holder_user_id = EXCLUDED.holder_user_id,
                    acquired_at = CASE WHEN document_edit_locks.holder_user_id = EXCLUDED.holder_user_id
                                       THEN document_edit_locks.acquired_at ELSE EXCLUDED.acquired_at END,
                    last_heartbeat_at = EXCLUDED.last_heartbeat_at,
                    expires_at = EXCLUDED.expires_at
                WHERE document_edit_locks.expires_at <= :now
                   OR document_edit_locks.holder_user_id = EXCLUDED.holder_user_id
            """, nativeQuery = true)
    int acquire(@Param("documentId") String documentId,
                @Param("userId") String userId,
                @Param("now") Instant now,
                @Param("expiresAt") Instant expiresAt);

    /** 보유자 본인의 아직 유효한 잠금만 갱신한다. 성립 1, 실패(보유자 아님/만료) 0. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE document_edit_locks
               SET last_heartbeat_at = :now, expires_at = :expiresAt
             WHERE document_id = :documentId
               AND holder_user_id = :userId
               AND expires_at > :now
            """, nativeQuery = true)
    int heartbeat(@Param("documentId") String documentId,
                  @Param("userId") String userId,
                  @Param("now") Instant now,
                  @Param("expiresAt") Instant expiresAt);

    /** 보유자 본인의 잠금만 해제한다. 멱등(보유자 아니면 0). */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            DELETE FROM document_edit_locks
             WHERE document_id = :documentId AND holder_user_id = :userId
            """, nativeQuery = true)
    int release(@Param("documentId") String documentId, @Param("userId") String userId);
}
