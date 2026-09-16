package fruition.core.wiki.repository;

import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.domain.WikiPageContributionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface WikiPageContributionRepository
        extends JpaRepository<WikiPageContribution, WikiPageContributionId> {

    /** 복구 대상 페이지 수집. 제외할 작업들이 건드린 페이지만 후보가 된다. */
    @Query("""
            SELECT DISTINCT c.id.pageId FROM WikiPageContribution c
            WHERE c.id.ingestOperationId IN :operationIds AND c.active = true
            """)
    List<String> findActivePageIdsByOperationIds(
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
}
