package fruition.core.document.service;

import fruition.core.document.domain.DocumentAsset;
import fruition.core.document.exception.DocumentAssetStorageException;
import fruition.core.document.repository.DocumentAssetRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentAssetStorageCoordinatorTest {

    @Test
    void storeAll_returnsAttachmentMappingWithoutExposingBytesToKey() {
        RecordingStorage storage = new RecordingStorage(-1);
        RecordingOrphanRegistry orphans = new RecordingOrphanRegistry();
        DocumentAssetStorageCoordinator coordinator = new DocumentAssetStorageCoordinator(storage, orphans, noCommittedAssets());
        UUID attachmentId = UUID.randomUUID();

        var stored = coordinator.storeAll("ws_1", "doc_1", 3L, Map.of(attachmentId, asset("first.png")));

        assertThat(stored).containsOnlyKeys(attachmentId);
        assertThat(stored.get(attachmentId).objectKey()).startsWith("assets/ws_1/").endsWith("/content");
        assertThat(storage.putKeys).containsExactly(stored.get(attachmentId).objectKey());
    }

    @Test
    void storeAll_whenLaterPutFails_deletesPreviouslyStoredObjects() {
        RecordingStorage storage = new RecordingStorage(2);
        RecordingOrphanRegistry orphans = new RecordingOrphanRegistry();
        DocumentAssetStorageCoordinator coordinator = new DocumentAssetStorageCoordinator(storage, orphans, noCommittedAssets());
        Map<UUID, DocumentAssetValidator.ValidatedAsset> assets = new LinkedHashMap<>();
        assets.put(UUID.randomUUID(), asset("first.png"));
        assets.put(UUID.randomUUID(), asset("second.png"));

        assertThatThrownBy(() -> coordinator.storeAll("ws_1", "doc_1", 3L, assets))
                .isInstanceOf(DocumentAssetStorageException.class);
        assertThat(storage.deletedKeys).containsExactly(storage.putKeys.getFirst());
    }

    @Test
    void storeAll_sameRequestProducesSameAssetIdWithFreshObjectKey() {
        UUID attachmentId = UUID.randomUUID();
        RecordingStorage storage = new RecordingStorage(-1);
        DocumentAssetStorageCoordinator coordinator =
                new DocumentAssetStorageCoordinator(storage, new RecordingOrphanRegistry(), noCommittedAssets());

        var first = coordinator.storeAll("ws_1", "doc_1", 3L, Map.of(attachmentId, asset("a.png")));
        var retried = coordinator.storeAll("ws_1", "doc_1", 3L, Map.of(attachmentId, asset("a.png")));

        assertThat(retried.get(attachmentId).assetId()).isEqualTo(first.get(attachmentId).assetId());
        // 같은 asset ID로 겹친 두 시도가 서로의 객체를 덮어쓰지 않도록 key는 시도마다 다르다(#39).
        assertThat(retried.get(attachmentId).objectKey()).isNotEqualTo(first.get(attachmentId).objectKey());
        // base revision이 다르면 다른 asset이다 (다른 저장이므로 겹쳐 쓰면 안 된다)
        var nextRevision = coordinator.storeAll("ws_1", "doc_1", 4L, Map.of(attachmentId, asset("a.png")));
        assertThat(nextRevision.get(attachmentId).assetId())
                .isNotEqualTo(first.get(attachmentId).assetId());
    }

    @Test
    void compensate_attemptsEveryDeleteWhenOneDeleteFails() {
        RecordingStorage storage = new RecordingStorage(-1);
        storage.failDeleteIndex = 1;
        RecordingOrphanRegistry orphans = new RecordingOrphanRegistry();
        DocumentAssetStorageCoordinator coordinator = new DocumentAssetStorageCoordinator(storage, orphans, noCommittedAssets());
        Map<UUID, DocumentAssetStorageCoordinator.StoredAsset> stored = coordinator.storeAll(
                "ws_1", "doc_1", 3L,
                Map.of(UUID.randomUUID(), asset("first.png"), UUID.randomUUID(), asset("second.png")));

        coordinator.compensate(stored.values());

        assertThat(storage.deleteAttempts).hasSize(2);
        assertThat(orphans.assetIds).hasSize(1);
    }

    // 충돌한 stale 저장이 같은 결정적 키를 덮어쓰고 보상으로 지워, 복제본이 참조하던 이미지가 깨졌다(#39).
    @Test
    void storeAll_reusesCommittedAssetWithoutOverwritingAndCompensateKeepsIt() {
        RecordingStorage storage = new RecordingStorage(-1);
        DocumentAssetRepository committed = mock(DocumentAssetRepository.class);
        String committedKey = "assets/ws_1/committed/content";
        when(committed.findById(any())).thenReturn(Optional.of(new DocumentAsset(
                UUID.randomUUID(), "ws_1", "user_1", "a.png", "image/png", 3, 1, 1, "a".repeat(64),
                committedKey, Instant.now())));
        DocumentAssetStorageCoordinator coordinator =
                new DocumentAssetStorageCoordinator(storage, new RecordingOrphanRegistry(), committed);
        UUID attachmentId = UUID.randomUUID();

        var stored = coordinator.storeAll("ws_1", "doc_1", 3L, Map.of(attachmentId, asset("stale.png")));
        coordinator.compensate(stored.values());

        assertThat(stored.get(attachmentId).preexisting()).isTrue();
        assertThat(stored.get(attachmentId).objectKey()).isEqualTo(committedKey);
        assertThat(storage.putKeys).isEmpty();
        assertThat(storage.deleteAttempts).isEmpty();
    }

    /**
     * 같은 asset ID로 겹친 두 시도: 둘 다 행이 없을 때 객체를 쓰고, 이긴 쪽이 커밋한 뒤 진 쪽이 보상한다.
     * 진 쪽은 자기 key만 지우고 이긴 쪽 key는 덮어쓰지도 지우지도 않는다.
     */
    @Test
    void compensate_ofLosingAttemptDeletesOnlyItsOwnObject() {
        RecordingStorage storage = new RecordingStorage(-1);
        DocumentAssetRepository repository = mock(DocumentAssetRepository.class);
        DocumentAssetStorageCoordinator coordinator =
                new DocumentAssetStorageCoordinator(storage, new RecordingOrphanRegistry(), repository);
        UUID attachmentId = UUID.randomUUID();
        var winner = coordinator.storeAll("ws_1", "doc_1", 3L, Map.of(attachmentId, asset("win.png")));
        var loser = coordinator.storeAll("ws_1", "doc_1", 3L, Map.of(attachmentId, asset("lose.png")));
        String winnerKey = winner.get(attachmentId).objectKey();
        String loserKey = loser.get(attachmentId).objectKey();
        when(repository.existsByStorageKey(winnerKey)).thenReturn(true);

        coordinator.compensate(loser.values());

        assertThat(storage.putKeys).containsExactly(winnerKey, loserKey);
        assertThat(storage.deleteAttempts).containsExactly(loserKey);
    }

    /** 이 시도의 저장이 커밋된 뒤 다른 이유로 보상이 불리면 커밋된 객체는 남긴다. */
    @Test
    void compensate_keepsObjectWhoseRowCommitted() {
        RecordingStorage storage = new RecordingStorage(-1);
        DocumentAssetRepository repository = mock(DocumentAssetRepository.class);
        DocumentAssetStorageCoordinator coordinator =
                new DocumentAssetStorageCoordinator(storage, new RecordingOrphanRegistry(), repository);
        var stored = coordinator.storeAll("ws_1", "doc_1", 3L, Map.of(UUID.randomUUID(), asset("a.png")));
        when(repository.existsByStorageKey(any())).thenReturn(true);

        coordinator.compensate(stored.values());

        assertThat(storage.deleteAttempts).isEmpty();
    }

    private static DocumentAssetRepository noCommittedAssets() {
        return mock(DocumentAssetRepository.class);
    }

    private static final class RecordingOrphanRegistry implements DocumentAssetOrphanRegistry {
        private final List<UUID> assetIds = new ArrayList<>();

        @Override
        public void record(UUID assetId, String storageKey, String errorMessage) {
            assetIds.add(assetId);
        }
    }

    private DocumentAssetValidator.ValidatedAsset asset(String filename) {
        byte[] bytes = new byte[]{1, 2, 3};
        return new DocumentAssetValidator.ValidatedAsset(
                filename, "image/png", bytes, 1, 1, "a".repeat(64));
    }

    private static final class RecordingStorage implements DocumentAssetObjectStorage {
        private final int failPutIndex;
        private int putCount;
        private int deleteCount;
        private int failDeleteIndex = -1;
        private final List<String> putKeys = new ArrayList<>();
        private final List<String> deleteAttempts = new ArrayList<>();
        private final List<String> deletedKeys = new ArrayList<>();

        private RecordingStorage(int failPutIndex) { this.failPutIndex = failPutIndex; }

        @Override
        public void put(String objectKey, String contentType, byte[] bytes) {
            putCount += 1;
            if (putCount == failPutIndex) {
                throw new DocumentAssetStorageException("저장 실패", new IllegalStateException());
            }
            putKeys.add(objectKey);
        }

        @Override
        public java.io.InputStream get(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
            deleteCount += 1;
            deleteAttempts.add(objectKey);
            if (deleteCount == failDeleteIndex) {
                throw new DocumentAssetStorageException("삭제 실패", new IllegalStateException());
            }
            deletedKeys.add(objectKey);
        }
    }
}
