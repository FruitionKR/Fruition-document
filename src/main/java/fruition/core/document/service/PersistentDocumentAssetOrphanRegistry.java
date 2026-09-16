package fruition.core.document.service;

import fruition.core.document.domain.DocumentAssetOrphan;
import fruition.core.document.repository.DocumentAssetOrphanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class PersistentDocumentAssetOrphanRegistry implements DocumentAssetOrphanRegistry {

    private final DocumentAssetOrphanRepository repository;

    public PersistentDocumentAssetOrphanRegistry(DocumentAssetOrphanRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID assetId, String storageKey, String errorMessage) {
        Instant failedAt = Instant.now();
        repository.findByStorageKey(storageKey).ifPresentOrElse(
                orphan -> {
                    orphan.recordRetryFailure(failedAt, errorMessage);
                    repository.save(orphan);
                },
                () -> repository.save(new DocumentAssetOrphan(
                        assetId, storageKey, failedAt, errorMessage))
        );
    }
}
