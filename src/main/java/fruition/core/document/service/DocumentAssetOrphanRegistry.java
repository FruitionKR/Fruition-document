package fruition.core.document.service;

import java.util.UUID;

public interface DocumentAssetOrphanRegistry {
    void record(UUID assetId, String storageKey, String errorMessage);
}
