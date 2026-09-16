package fruition.core.document.service;

import fruition.core.document.domain.DocumentAsset;
import fruition.core.document.domain.DocumentAssetOrphan;
import fruition.core.document.exception.DocumentAssetStorageException;
import fruition.core.document.repository.DocumentAssetOrphanRepository;
import fruition.core.document.repository.DocumentAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentAssetCleanupWorkerTest {

    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
    private static final Instant THRESHOLD = NOW.minus(Duration.ofDays(7));

    @Mock DocumentAssetRepository assetRepository;
    @Mock DocumentAssetOrphanRepository orphanRepository;
    @Mock DocumentAssetObjectStorage objectStorage;
    @Mock DocumentAssetOrphanRegistry orphanRegistry;

    @Test
    void cleanup_deletesAssetRowBeforeObject() {
        DocumentAsset asset = asset("asset-key");
        stubCandidates(List.of(asset), List.of());
        when(assetRepository.deleteIfStillUnreferenced(asset.getId(), THRESHOLD)).thenReturn(1);

        worker().cleanup(NOW);

        var order = org.mockito.Mockito.inOrder(assetRepository, objectStorage);
        order.verify(assetRepository).deleteIfStillUnreferenced(asset.getId(), THRESHOLD);
        order.verify(objectStorage).delete("asset-key");
    }

    @Test
    void cleanup_keepsObjectWhenAssetWasReferencedAgain() {
        DocumentAsset asset = asset("asset-key");
        stubCandidates(List.of(asset), List.of());
        // 후보 조회 이후 다시 참조돼 조건부 삭제가 0행을 지운 상황
        when(assetRepository.deleteIfStillUnreferenced(asset.getId(), THRESHOLD)).thenReturn(0);

        worker().cleanup(NOW);

        verify(objectStorage, never()).delete("asset-key");
    }

    @Test
    void cleanup_storageFailureAfterRowDeleteRecordsOrphan() {
        DocumentAsset asset = asset("asset-key");
        stubCandidates(List.of(asset), List.of());
        when(assetRepository.deleteIfStillUnreferenced(asset.getId(), THRESHOLD)).thenReturn(1);
        doThrow(new DocumentAssetStorageException("실패", new IllegalStateException()))
                .when(objectStorage).delete("asset-key");

        worker().cleanup(NOW);

        verify(orphanRegistry).record(eq(asset.getId()), eq("asset-key"), any());
    }

    @Test
    void cleanup_processesAtMostBatchSizePerRun() {
        // 상한을 넘는 후보가 있어도 한 실행에서는 조회된 만큼만 처리하고 나머지는 다음 실행이 가져간다.
        List<DocumentAsset> candidates = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> asset("asset-key-" + index))
                .toList();
        stubCandidates(candidates, List.of());
        candidates.forEach(asset ->
                when(assetRepository.deleteIfStillUnreferenced(asset.getId(), THRESHOLD)).thenReturn(1));

        worker().cleanup(NOW);

        verify(objectStorage, org.mockito.Mockito.times(100)).delete(org.mockito.ArgumentMatchers.startsWith("asset-key-"));
    }

    @Test
    void cleanup_retriesOrphanAndRecordsFailure() {
        DocumentAssetOrphan orphan = orphan();
        stubCandidates(List.of(), List.of(orphan));
        doThrow(new DocumentAssetStorageException("재시도 실패", new IllegalStateException()))
                .when(objectStorage).delete("orphan-key");
        when(orphanRepository.findById(orphan.getId())).thenReturn(Optional.of(orphan));

        worker().cleanup(NOW);

        assertThat(orphan.getRetryCount()).isEqualTo(1);
        assertThat(orphan.getFailedAt()).isEqualTo(NOW);
        verify(orphanRepository, never()).deleteById(orphan.getId());
    }

    @Test
    void cleanup_deletesOrphanRowWhenStorageDeleteSucceeds() {
        DocumentAssetOrphan orphan = orphan();
        stubCandidates(List.of(), List.of(orphan));

        worker().cleanup(NOW);

        verify(objectStorage).delete("orphan-key");
        verify(orphanRepository).deleteById(orphan.getId());
    }

    private void stubCandidates(List<DocumentAsset> assets, List<DocumentAssetOrphan> orphans) {
        when(assetRepository.findTop100ByUnreferencedSinceLessThanEqualOrderByUnreferencedSinceAsc(THRESHOLD))
                .thenReturn(assets);
        when(orphanRepository.findTop100ByFailedAtLessThanEqualOrderByFailedAtAsc(THRESHOLD))
                .thenReturn(orphans);
    }

    private DocumentAssetCleanupWorker worker() {
        return new DocumentAssetCleanupWorker(
                assetRepository, orphanRepository, objectStorage, orphanRegistry,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    private DocumentAsset asset(String storageKey) {
        return new DocumentAsset(UUID.randomUUID(), "ws_1", "user_1", "image.png", "image/png",
                3, 1, 1, "a".repeat(64), storageKey, Instant.now());
    }

    private DocumentAssetOrphan orphan() {
        return new DocumentAssetOrphan(
                UUID.randomUUID(), "orphan-key", NOW.minusSeconds(60), "첫 실패");
    }
}
