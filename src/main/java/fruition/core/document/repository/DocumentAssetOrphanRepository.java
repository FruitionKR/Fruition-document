package fruition.core.document.repository;

import fruition.core.document.domain.DocumentAssetOrphan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DocumentAssetOrphanRepository extends JpaRepository<DocumentAssetOrphan, UUID> {
    Optional<DocumentAssetOrphan> findByStorageKey(String storageKey);
    List<DocumentAssetOrphan> findTop100ByFailedAtLessThanEqualOrderByFailedAtAsc(Instant threshold);
}
