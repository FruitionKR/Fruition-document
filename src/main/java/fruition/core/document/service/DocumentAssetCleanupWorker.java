package fruition.core.document.service;

import fruition.core.document.exception.DocumentAssetStorageException;
import fruition.core.document.repository.DocumentAssetOrphanRepository;
import fruition.core.document.repository.DocumentAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentAssetCleanupWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentAssetCleanupWorker.class);
    private static final Duration RETENTION = Duration.ofDays(7);
    /** 한 실행에서 처리하는 최대 건수. 저장소 조회 메서드 이름(findTop100…)과 맞춰 둔다. */
    private static final int BATCH_SIZE = 100;

    private final DocumentAssetRepository assetRepository;
    private final DocumentAssetOrphanRepository orphanRepository;
    private final DocumentAssetObjectStorage objectStorage;
    private final DocumentAssetOrphanRegistry orphanRegistry;
    private final TransactionTemplate transactionTemplate;

    public DocumentAssetCleanupWorker(
            DocumentAssetRepository assetRepository,
            DocumentAssetOrphanRepository orphanRepository,
            DocumentAssetObjectStorage objectStorage,
            DocumentAssetOrphanRegistry orphanRegistry,
            TransactionTemplate transactionTemplate
    ) {
        this.assetRepository = assetRepository;
        this.orphanRepository = orphanRepository;
        this.objectStorage = objectStorage;
        this.orphanRegistry = orphanRegistry;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${app.document-assets.cleanup-delay-ms:86400000}")
    public void runScheduled() {
        cleanup(Instant.now());
    }

    /**
     * object storage 삭제를 트랜잭션 밖에서 수행한다. storage가 느리거나 멈춰도 DB 트랜잭션과
     * 커넥션을 붙잡지 않는다. object 삭제와 row 삭제 모두 멱등이라 다음 실행에서 다시 시도해도 안전하다.
     *
     * <p>row를 먼저 지우고 object를 나중에 지운다. 후보 조회와 삭제 사이에 asset이 다시 참조되면
     * 조건부 삭제가 0행을 지워 object는 그대로 남는다. 반대 순서면 파일만 사라져 복구할 수 없다.
     *
     * <p>한 실행에서 각각 최대 {@value #BATCH_SIZE}건만 처리한다. 상한에 걸렸다는 사실을 로그로
     * 남겨야 적체가 보인다. 남은 분량은 다음 실행이 이어서 가져간다.
     */
    public void cleanup(Instant now) {
        Instant threshold = now.minus(RETENTION);

        List<Candidate> unreferenced = loadUnreferencedAssets(threshold);
        List<Candidate> orphans = loadRetryableOrphans(threshold);
        if (unreferenced.size() >= BATCH_SIZE || orphans.size() >= BATCH_SIZE) {
            log.info("[이미지 asset 정리 상한 도달] unreferenced={} orphans={} batchSize={}",
                    unreferenced.size(), orphans.size(), BATCH_SIZE);
        }

        for (Candidate candidate : unreferenced) {
            Integer deleted = transactionTemplate.execute(status ->
                    assetRepository.deleteIfStillUnreferenced(candidate.id(), threshold));
            if (deleted == null || deleted == 0) {
                continue; // 다시 참조된 asset. object를 지우면 안 된다.
            }
            try {
                objectStorage.delete(candidate.storageKey());
            } catch (DocumentAssetStorageException exception) {
                // row는 이미 사라졌으므로 orphan으로 넘겨 다음 실행에서 object만 다시 지운다.
                orphanRegistry.record(candidate.id(), candidate.storageKey(), exception.getMessage());
            }
        }

        for (Candidate candidate : orphans) {
            try {
                objectStorage.delete(candidate.storageKey());
            } catch (DocumentAssetStorageException exception) {
                recordOrphanRetryFailure(candidate.id(), now, exception.getMessage());
                continue;
            }
            inTransaction(() -> orphanRepository.deleteById(candidate.id()));
        }
    }

    private List<Candidate> loadUnreferencedAssets(Instant threshold) {
        return transactionTemplate.execute(status ->
                assetRepository
                        .findTop100ByUnreferencedSinceLessThanEqualOrderByUnreferencedSinceAsc(threshold)
                        .stream()
                        .map(asset -> new Candidate(asset.getId(), asset.getStorageKey()))
                        .toList());
    }

    private List<Candidate> loadRetryableOrphans(Instant threshold) {
        return transactionTemplate.execute(status ->
                orphanRepository
                        .findTop100ByFailedAtLessThanEqualOrderByFailedAtAsc(threshold)
                        .stream()
                        .map(orphan -> new Candidate(orphan.getId(), orphan.getStorageKey()))
                        .toList());
    }

    private void recordOrphanRetryFailure(UUID orphanId, Instant now, String message) {
        inTransaction(() -> orphanRepository.findById(orphanId)
                .ifPresent(orphan -> orphan.recordRetryFailure(now, message)));
    }

    private void inTransaction(Runnable work) {
        transactionTemplate.executeWithoutResult(status -> work.run());
    }

    /** storage 삭제는 트랜잭션 밖에서 하므로 entity 대신 식별자와 object key만 들고 나온다. */
    private record Candidate(UUID id, String storageKey) {}
}
