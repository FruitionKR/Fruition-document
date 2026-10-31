package fruition.core.document.service;

import fruition.core.document.repository.DocumentAssetRepository;
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
    private final DocumentAssetRepository assetRepository;

    public DocumentAssetStorageCoordinator(
            DocumentAssetObjectStorage objectStorage,
            DocumentAssetOrphanRegistry orphanRegistry,
            DocumentAssetRepository assetRepository
    ) {
        this.objectStorage = objectStorage;
        this.orphanRegistry = orphanRegistry;
        this.assetRepository = assetRepository;
    }

    /**
     * asset ID를 (문서, base revision, placeholder)에서 결정적으로 만든다.
     * 같은 요청을 재전송하면 asset ID·본문이 그대로 나와,
     * 저장 계층의 revision_write_id 중복 판정이 첫 결과를 그대로 돌려줄 수 있다.
     * 무작위 ID였을 때는 재전송마다 본문이 달라져 재시도를 알아볼 수 없었다.
     *
     * <p>object key는 시도마다 다르다. 본문은 asset ID만 담고 객체 위치는 asset 행의 storage_key로 찾으므로
     * 재시도 판정에 영향이 없다. key까지 결정적이면 같은 asset ID로 동시에 저장한 두 요청 중 진 쪽이
     * 이긴 쪽 객체를 덮어쓰거나 보상 삭제로 지운다(#39).
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
                // 이미 커밋된 asset은 복제본 등 다른 문서가 참조할 수 있어 행·객체를 그대로 쓴다. 이 asset ID로
                // 성공할 수 있는 저장은 같은 내용의 재전송뿐이고 나머지는 base revision 충돌로 거절된다.
                var committed = assetRepository.findById(assetId);
                if (committed.isPresent()) {
                    stored.put(attachmentId, new StoredAsset(assetId, committed.get().getStorageKey(), asset, true));
                    return;
                }
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
            // 이 시도가 새로 쓴 객체만 지운다. key가 시도마다 달라 이 key를 가리키는 행은 이 시도가 커밋한 것뿐이다.
            if (storedAsset.preexisting() || assetRepository.existsByStorageKey(storedAsset.objectKey())) continue;
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
        return "assets/" + workspaceId + "/" + assetId + "/" + UUID.randomUUID() + "/content";
    }

    /** preexisting이면 이 요청 전에 커밋된 asset이라 행·객체 모두 이 요청이 소유하지 않는다. */
    public record StoredAsset(
            UUID assetId,
            String objectKey,
            DocumentAssetValidator.ValidatedAsset validated,
            boolean preexisting
    ) {
        public StoredAsset(UUID assetId, String objectKey, DocumentAssetValidator.ValidatedAsset validated) {
            this(assetId, objectKey, validated, false);
        }
    }
}
