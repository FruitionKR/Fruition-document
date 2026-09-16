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
     * 다음 revision 채번 기준. {@code wiki_pages}에 현재 버전 컬럼을 두지 않고 여기서 얻는다.
     * {@code (page_id, revision DESC)} 인덱스의 첫 항목 한 행만 읽는다.
     */
    @Query("SELECT COALESCE(MAX(v.id.revision), 0) FROM WikiPageVersion v WHERE v.id.pageId = :pageId")
    long findMaxRevision(@Param("pageId") String pageId);

    Optional<WikiPageVersion> findTopByIdPageIdOrderByIdRevisionDesc(String pageId);

}
