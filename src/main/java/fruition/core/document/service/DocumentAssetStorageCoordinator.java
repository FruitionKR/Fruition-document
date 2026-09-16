package fruition.core.document.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentAssetStorageCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DocumentAssetStorageCoordinator.class);
    private final DocumentAssetObjectStorage objectStorage;
    private final DocumentAssetOrphanRegistry orphanRegistry;

    public DocumentAssetStorageCoordinator(
            DocumentAssetObjectStorage objectStorage,
            DocumentAssetOrphanRegistry orphanRegistry
    ) {
        this.objectStorage = objectStorage;
        this.orphanRegistry = orphanRegistry;
    }

    /**
     * asset ID를 (문서, base revision, placeholder)에서 결정적으로 만든다.
     * 같은 요청을 재전송하면 asset ID·object key·본문이 그대로 나와,
     * 저장 계층의 revision_write_id 중복 판정이 첫 결과를 그대로 돌려줄 수 있다.
     * 무작위 ID였을 때는 재전송마다 본문이 달라져 재시도를 알아볼 수 없었다.
     */
    public Map<UUID, StoredAsset> storeAll(
            String workspaceId,
            String documentId,
            long baseVersion,
            Map<UUID, DocumentAssetValidator.ValidatedAsset> attachments
    ) {
        Map<UUID, StoredAsset> stored = new LinkedHashMap<>();
        try {
            attachments.forEach((attachmentId, asset) -> {
                UUID assetId = assetId(workspaceId, documentId, baseVersion, attachmentId);
                String objectKey = objectKey(workspaceId, assetId);
                objectStorage.put(objectKey, asset.contentType(), asset.bytes());
                stored.put(attachmentId, new StoredAsset(assetId, objectKey, asset));
            });
            return Map.copyOf(stored);
        } catch (RuntimeException exception) {
            compensate(stored.values());
            throw exception;
        }
    }

    private UUID assetId(String workspaceId, String documentId, long baseVersion, UUID attachmentId) {
        String seed = workspaceId + "\0" + documentId + "\0" + baseVersion + "\0" + attachmentId;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    public void compensate(Collection<StoredAsset> storedAssets) {
        List<String> failedAssetIds = new ArrayList<>();
        for (StoredAsset storedAsset : storedAssets) {
            try {
                objectStorage.delete(storedAsset.objectKey());
            } catch (RuntimeException exception) {
                failedAssetIds.add(storedAsset.assetId().toString());
                try {
                    orphanRegistry.record(storedAsset.assetId(), storedAsset.objectKey(), exception.getMessage());
                } catch (RuntimeException registryException) {
                    log.error("[이미지 asset orphan 기록 실패] assetId={}", storedAsset.assetId());
                }
            }
        }
        if (!failedAssetIds.isEmpty()) {
            log.error("[이미지 asset 보상 삭제 실패] assetIds={}", failedAssetIds);
        }
    }

    private String objectKey(String workspaceId, UUID assetId) {
        return "assets/" + workspaceId + "/" + assetId + "/content";
    }

    public record StoredAsset(
            UUID assetId,
            String objectKey,
            DocumentAssetValidator.ValidatedAsset validated
    ) {
    }
}
