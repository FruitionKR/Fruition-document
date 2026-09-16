package fruition.core.document.repository;

import fruition.core.document.domain.DocumentAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface DocumentAssetRepository extends JpaRepository<DocumentAsset, UUID> {
    Optional<DocumentAsset> findByIdAndWorkspaceId(UUID id, String workspaceId);
    List<DocumentAsset> findAllByIdInAndWorkspaceId(Collection<UUID> ids, String workspaceId);
    List<DocumentAsset> findTop100ByUnreferencedSinceLessThanEqualOrderByUnreferencedSinceAsc(Instant threshold);

    /**
     * 후보 조회 이후 다시 참조된 asset을 지우지 않도록, 삭제 시점에 미참조 조건을 한 번 더 확인한다.
     * 지운 행 수가 0이면 그 사이 참조가 살아난 것이므로 object도 지우면 안 된다.
     */
    @Modifying
    @Query("DELETE FROM DocumentAsset a WHERE a.id = :id "
            + "AND a.unreferencedSince IS NOT NULL AND a.unreferencedSince <= :threshold")
    int deleteIfStillUnreferenced(@Param("id") UUID id, @Param("threshold") Instant threshold);
}
