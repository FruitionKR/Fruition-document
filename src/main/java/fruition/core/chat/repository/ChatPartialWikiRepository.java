package fruition.core.chat.repository;

import fruition.core.chat.domain.ChatPartialWiki;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatPartialWikiRepository extends JpaRepository<ChatPartialWiki, String> {

    /** 부분 실패 후에도 export 문서의 pair별 멤버십을 이어서 기록할 수 있는지 확인한다. */
    boolean existsByDocumentIdAndPairId(String documentId, String pairId);

    /** 방향1(세션→문답): 세션에서 partial 위키에 편입된 문답 멤버십. */
    List<ChatPartialWiki> findAllBySessionId(String sessionId);

    /** 방향2(위키→문답): 특정 partial 위키 페이지의 원본 문답. (조회 엔드포인트는 후속) */
    List<ChatPartialWiki> findAllByWikiPageId(String wikiPageId);
}
