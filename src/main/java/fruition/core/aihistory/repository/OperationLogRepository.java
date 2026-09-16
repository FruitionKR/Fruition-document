package fruition.core.aihistory.repository;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OperationLogRepository extends JpaRepository<OperationLog, String> {

    /** 같은 복구 미리보기 토큰은 하나의 복구 작업만 원자적으로 선점한다. */
    @Modifying
    @Query(value = """
            INSERT INTO ai_operation_logs(
                operation_id, workspace_id, user_id, operation_type, target_document_id,
                status, changed_resource_count, restored_from, restore_manifest,
                restore_token_hash, created_at
            ) VALUES (
                :operationId, :workspaceId, :userId, 'restore', :targetDocumentId,
                'applying', 0, :restoredFrom, CAST(:restoreManifest AS jsonb),
                :restoreTokenHash, :now
            )
            ON CONFLICT (restored_from, restore_token_hash)
                WHERE operation_type = 'restore' AND restore_token_hash IS NOT NULL
            DO NOTHING
            """, nativeQuery = true)
    int insertRestoreIfAbsent(
            @Param("operationId") String operationId,
            @Param("workspaceId") String workspaceId,
            @Param("userId") String userId,
            @Param("targetDocumentId") String targetDocumentId,
            @Param("restoredFrom") String restoredFrom,
            @Param("restoreManifest") String restoreManifest,
            @Param("restoreTokenHash") String restoreTokenHash,
            @Param("now") Instant now
    );

    /** 같은 문서 편집 conflict 재시도는 기존 감사 행을 그대로 사용한다. */
    @Modifying
    @Query(value = """
            INSERT INTO ai_operation_logs(
                operation_id, workspace_id, user_id, operation_type, target_document_id,
                status, summary, changed_resource_count, created_at, completed_at
            ) VALUES (
                :operationId, :workspaceId, :userId, 'document_edit', :documentId,
                'conflict', :summary, 0, :now, :now
            )
            ON CONFLICT (operation_id) DO NOTHING
            """, nativeQuery = true)
    int insertConflictIfAbsent(
            @Param("operationId") String operationId,
            @Param("workspaceId") String workspaceId,
            @Param("userId") String userId,
            @Param("documentId") String documentId,
            @Param("summary") String summary,
            @Param("now") Instant now
    );

    /**
     * 아직 끝나지 않은 복구만 실패로 확정하고 미리보기 토큰 선점을 푼다.
     *
     * <p>상태 확인과 갱신을 한 UPDATE로 묶는다. 조회로 확인한 뒤 갱신하면 그사이 성공이
     * 확정됐을 때 반영이 끝난 복구를 실패로 덮고 선점까지 풀어, 같은 미리보기로 두 번
     * 반영될 수 있다.
     *
     * <p>{@code changed_resource_count}는 건드리지 않는다. 실패한 복구는 아무것도 반영하지
     * 못해 이미 0이고, 굳이 덮으면 그 불변식이 깨진 뒤에도 조용히 가려진다.
     *
     * @return 갱신한 행 수. 0이면 이미 끝난 복구라 아무것도 하지 않았다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE OperationLog l
               SET l.status = :failedStatus,
                   l.summary = :summary,
                   l.payloadHash = null,
                   l.restoreTokenHash = null,
                   l.completedAt = :now
             WHERE l.operationId = :operationId
               AND l.status NOT IN :terminalStatuses
            """)
    int failRestoreIfNotTerminal(
            @Param("operationId") String operationId,
            @Param("failedStatus") OperationStatus failedStatus,
            @Param("summary") String summary,
            @Param("now") Instant now,
            @Param("terminalStatuses") Collection<OperationStatus> terminalStatuses
    );

    Optional<OperationLog> findByOperationIdAndWorkspaceId(String operationId, String workspaceId);

    boolean existsByOperationTypeAndRestoredFromAndRestoreTokenHash(
            OperationType operationType, String restoredFrom, String restoreTokenHash);

    /**
     * 목록 조회. 최신순이며 커서보다 오래된 것만 가져온다.
     *
     * <p>변경이 0건인 성공은 사용자가 목록에서 할 수 있는 일이 없어 걷어낸다.
     * {@code successOnlyType}은 결과가 나기 전에 감사 행을 먼저 커밋하는 유형이라, 끝난 성공만
     * 남긴다. 이 둘은 명시 조회에서도 걷어낸다.
     *
     * <p>{@code hiddenDefaultStatuses}(진행 중·반영 실패)는 {@code status}를 생략했을 때만
     * 걷어낸다. 명시 조회는 그대로 통과시킨다. {@code status=processing}은 활성 작업 탐지에,
     * {@code status=failed}·{@code status=conflict}는 실패 알림 감지에 쓴다.
     *
     * <p>{@code createdAt}만으로는 같은 시각에 만들어진 작업이 {@code <} 비교에서 통째로
     * 빠진다. {@code (createdAt, operationId)} 복합 커서로 동시각 작업까지 결정적으로 가른다.
     * 정렬도 같은 두 키를 써야 커서가 페이지 경계와 어긋나지 않는다.
     *
     * <p>{@code cursor}에 null을 넘기지 않는다. Postgres는 {@code ? IS NULL} 형태에서 timestamp
     * 파라미터의 타입을 추론하지 못해 실행 자체가 실패한다. 첫 페이지는 먼 미래 값을 넘긴다.
     */
    @Query("""
            SELECT l FROM OperationLog l
            WHERE l.workspaceId = :workspaceId
              AND (:type IS NULL OR l.operationType = :type)
              AND (:status IS NULL OR l.status = :status)
              AND (:status IS NOT NULL OR l.status NOT IN :hiddenDefaultStatuses)
              AND (l.status <> :successStatus OR l.changedResourceCount > 0)
              AND (l.operationType <> :successOnlyType OR l.status = :successStatus)
              AND (l.createdAt < :cursor
                   OR (l.createdAt = :cursor AND l.operationId < :cursorOperationId))
            ORDER BY l.createdAt DESC, l.operationId DESC
            """)
    List<OperationLog> findPage(
            @Param("workspaceId") String workspaceId,
            @Param("type") OperationType type,
            @Param("status") OperationStatus status,
            @Param("cursor") Instant cursor,
            @Param("cursorOperationId") String cursorOperationId,
            @Param("successStatus") OperationStatus successStatus,
            @Param("successOnlyType") OperationType successOnlyType,
            @Param("hiddenDefaultStatuses") Collection<OperationStatus> hiddenDefaultStatuses,
            Pageable pageable
    );

    /**
     * 복구 대상 작업을 고른다. 기준 작업 이후 같은 문서의 작업을 전부 얻는다.
     *
     * <p>{@code createdAt}만으로는 같은 밀리초에 만들어진 작업이 {@code >} 비교에서 통째로
     * 빠질 수 있다. {@code (createdAt, operationId)} 복합 커서로 동시각 작업까지 결정적으로
     * 가른다.
     */
    @Query("""
            SELECT l FROM OperationLog l
            WHERE l.targetDocumentId = :targetDocumentId
              AND l.operationType = :operationType
              AND (l.createdAt > :after
                   OR (l.createdAt = :after AND l.operationId > :afterOperationId))
            ORDER BY l.createdAt, l.operationId
            """)
    List<OperationLog> findByTargetDocumentAfter(
            @Param("targetDocumentId") String targetDocumentId,
            @Param("after") Instant after,
            @Param("afterOperationId") String afterOperationId,
            @Param("operationType") OperationType operationType
    );

}
