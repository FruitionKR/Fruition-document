package fruition.core.wiki.repository;

import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.domain.WikiPageContributionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface WikiPageContributionRepository
        extends JpaRepository<WikiPageContribution, WikiPageContributionId> {

    /**
     * 복구 대상 페이지 수집. 제외할 작업들이 건드린 페이지와, 그 작업이 대체해 끈 기여(재편입이 끈 같은 문서의
     * 이전 편입 기여)가 있는 페이지가 후보가 된다.
     */
    @Query("""
            SELECT DISTINCT c.id.pageId FROM WikiPageContribution c
            WHERE (c.id.ingestOperationId IN :operationIds AND c.active = true)
               OR (c.active = false AND c.deactivatedBy IN :operationIds)
            """)
    List<String> findRestorePageIdsByOperationIds(
            @Param("operationIds") Collection<String> operationIds);

    /**
     * 여러 페이지의 <b>전체</b> 기여를 한 번에. 판정 단계는 본문을 읽지 않고 이것만으로 계산한다.
     *
     * <p>비활성 기여도 함께 반환한다. 복원 목적지가 유효한지 보려면 그 revision이 담고 있던 기여를
     * 알아야 하는데, 이전 복구로 꺼진 기여가 그 안에 들어 있을 수 있다.
     */
    @Query("""
            SELECT c FROM WikiPageContribution c
            WHERE c.id.pageId IN :pageIds
            ORDER BY c.id.pageId, c.sequenceRevision
            """)
    List<WikiPageContribution> findByPageIds(@Param("pageIds") Collection<String> pageIds);

    @Query(value = """
            SELECT contribution.*
            FROM wiki_page_contributions contribution
            JOIN ai_operation_logs operation
              ON operation.operation_id = contribution.ingest_operation_id
            WHERE contribution.page_id IN (:pageIds)
              AND operation.workspace_id = :workspaceId
            ORDER BY contribution.page_id, contribution.sequence_revision
            """, nativeQuery = true)
    List<WikiPageContribution> findByPageIdsAndWorkspaceId(
            @Param("pageIds") Collection<String> pageIds,
            @Param("workspaceId") String workspaceId);

    @Query(value = """
            SELECT DISTINCT contribution.page_id
            FROM wiki_page_contributions contribution
            JOIN ai_operation_logs operation
              ON operation.operation_id = contribution.ingest_operation_id
            WHERE contribution.page_id IN (:pageIds)
              AND operation.workspace_id <> :workspaceId
            """, nativeQuery = true)
    List<String> findPageIdsOutsideWorkspace(
            @Param("pageIds") Collection<String> pageIds,
            @Param("workspaceId") String workspaceId);

    long countByIdPageIdAndActiveTrue(String pageId);

    /**
     * 휴지통으로 옮긴 문서의 기여를 끈다. 복구 작업이 아니므로 deactivated_by는 비워 둔다.
     *
     * <p>재편입이 대체해 끈 행의 deactivated_by도 비운다. 남겨 두면 그 재편입을 되돌릴 때 휴지통 문서의
     * 기여가 다시 켜진다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE wiki_page_contributions
            SET active = false, deactivated_by = NULL
            WHERE source_document_id IN (:documentIds)
              AND (active = true OR deactivated_by IS NOT NULL)
            """, nativeQuery = true)
    int deactivateBySourceDocumentIds(@Param("documentIds") Collection<String> documentIds);

    /**
     * 같은 문서를 다시 편입한 결과를 기록할 때, 그 문서의 이전 편입 기여(lint가 다른 페이지에 이은 사본 포함)를
     * 이 작업이 대체한 것으로 끈다. 새 편입이 더는 다루지 않는 페이지의 행도 끈다. 이 편입을 되돌리면
     * deactivated_by로 정확히 이 행들을 다시 켠다. 결과가 늦게 와도 뒤 편입의 행은 끄지 않도록 먼저 시작한
     * 작업의 행만 대상으로 한다. 결과 반영 트랜잭션이 이어서 다른 엔티티를 쓰므로 영속성 컨텍스트는 비우지 않는다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE wiki_page_contributions contribution
            SET active = false, deactivated_by = :operationId
            WHERE contribution.source_document_id = :documentId
              AND contribution.active = true
              AND contribution.ingest_operation_id <> :operationId
              AND contribution.ingest_operation_id IN (
                  SELECT earlier.operation_id FROM ai_operation_logs earlier
                  WHERE earlier.created_at < (SELECT current.created_at FROM ai_operation_logs current
                                              WHERE current.operation_id = :operationId))
            """, nativeQuery = true)
    int supersedeEarlierIngests(@Param("documentId") String documentId,
                                @Param("operationId") String operationId);
}
