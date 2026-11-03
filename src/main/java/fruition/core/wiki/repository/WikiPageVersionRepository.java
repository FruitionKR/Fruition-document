package fruition.core.wiki.repository;

import fruition.core.wiki.domain.WikiPageVersion;
import fruition.core.wiki.domain.WikiPageVersionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WikiPageVersionRepository extends JpaRepository<WikiPageVersion, WikiPageVersionId> {

    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:pageId, 0))", nativeQuery = true)
    void lockPage(@Param("pageId") String pageId);

    /**
     * 다음 revision·기여 순서 채번 기준. {@code wiki_pages}에 현재 버전 컬럼을 두지 않고 여기서 얻는다.
     * 버전 없이 순서만 차지한 기여 행(lint·재편입이 지운 페이지)도 세야 뒤 기여가 같은 순서를 받지 않는다.
     * 같은 순서면 재생 순서가 정해지지 않아, 앞선 삭제 기록이 뒤 기여의 근거까지 지울 수 있다.
     */
    @Query(value = """
            SELECT GREATEST(
                COALESCE((SELECT MAX(revision) FROM wiki_page_versions WHERE page_id = :pageId), 0),
                COALESCE((SELECT MAX(sequence_revision) FROM wiki_page_contributions WHERE page_id = :pageId), 0))
            """, nativeQuery = true)
    long findMaxRevision(@Param("pageId") String pageId);

    Optional<WikiPageVersion> findTopByIdPageIdOrderByIdRevisionDesc(String pageId);

    /** 사람 수정의 재전송 판정. 같은 write ID는 같은 작업 ID가 되므로 그 작업이 만든 버전을 찾는다. */
    Optional<WikiPageVersion> findFirstByIdPageIdAndOperationId(String pageId, String operationId);

}
