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
    boolean existsByStorageKey(String storageKey);

    /** 같은 결정적 ID의 동시 재전송이 기존 객체 참조를 덮어쓰지 못하게 한다. */
    @Modifying
    @Query(value = """
            INSERT INTO document_assets
              (id, workspace_id, uploaded_by, original_filename, content_type, byte_size,
               width, height, content_hash, storage_key, created_at)
            VALUES (:#{#asset.id}, :#{#asset.workspaceId}, :#{#asset.uploadedBy},
                    :#{#asset.originalFilename}, :#{#asset.contentType}, :#{#asset.byteSize},
                    :#{#asset.width}, :#{#asset.height}, :#{#asset.contentHash},
                    :#{#asset.storageKey}, :#{#asset.createdAt})
            ON CONFLICT (id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("asset") DocumentAsset asset);

    /**
     * 후보 조회 이후 다시 참조된 asset을 지우지 않도록, 삭제 시점에 미참조 조건을 한 번 더 확인한다.
     * 지운 행 수가 0이면 그 사이 참조가 살아난 것이므로 object도 지우면 안 된다.
     */
    @Modifying
    @Query("DELETE FROM DocumentAsset a WHERE a.id = :id "
            + "AND a.unreferencedSince IS NOT NULL AND a.unreferencedSince <= :threshold")
    int deleteIfStillUnreferenced(@Param("id") UUID id, @Param("threshold") Instant threshold);
}
