package fruition.core.idempotency;

import fruition.TestcontainersConfiguration;
import fruition.shared.idempotency.IdempotencyConflictException;
import fruition.shared.idempotency.IdempotencyInProgressException;
import fruition.shared.idempotency.IdempotencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotencyServiceIntegrationTest {

    @Autowired
    IdempotencyService idempotencyService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void sameKeyConcurrentRequestsExecuteSideEffectOnceAndReplayResponse() throws Exception {
        String key = uniqueKey();
        AtomicInteger effects = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<TestResponse>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    while (true) {
                        try {
                            return execute(key, "hash-a", () ->
                                    new TestResponse("result-" + effects.incrementAndGet()));
                        } catch (IdempotencyInProgressException ignored) {
                            Thread.onSpinWait();
                        }
                    }
                }));
            }
            start.countDown();
            for (Future<TestResponse> future : futures) {
                assertThat(future.get()).isEqualTo(new TestResponse("result-1"));
            }
        }
        assertThat(effects).hasValue(1);
    }

    @Test
    void sameKeyWithDifferentPayloadThrowsConflict() {
        String key = uniqueKey();
        execute(key, "hash-a", () -> new TestResponse("saved"));

        assertThatThrownBy(() -> execute(key, "hash-b", () -> new TestResponse("other")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void requestInProgressIsReportedWithoutExecutingSecondAction() throws Exception {
        String key = uniqueKey();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<TestResponse> first = executor.submit(() -> execute(key, "hash-a", () -> {
                entered.countDown();
                await(release);
                return new TestResponse("saved");
            }));
            entered.await();

            assertThatThrownBy(() -> execute(key, "hash-a", () -> new TestResponse("duplicate")))
                    .isInstanceOf(IdempotencyInProgressException.class);

            release.countDown();
            assertThat(first.get()).isEqualTo(new TestResponse("saved"));
        }
    }

    @Test
    void failedExecutionReleasesClaimAndAllowsRetry() {
        String key = uniqueKey();
        assertThatThrownBy(() -> execute(key, "hash-a", () -> {
            throw new IllegalStateException("failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(execute(key, "hash-a", () -> new TestResponse("retried")))
                .isEqualTo(new TestResponse("retried"));
    }

    @Test
    void completedResponseIsReplayedWithoutExecutingAction() {
        String key = uniqueKey();
        execute(key, "hash-a", () -> new TestResponse("saved"));
        AtomicInteger effects = new AtomicInteger();

        TestResponse replay = execute(key, "hash-a", () -> {
            effects.incrementAndGet();
            return new TestResponse("new");
        });

        assertThat(replay).isEqualTo(new TestResponse("saved"));
        assertThat(effects).hasValue(0);
    }

    @Test
    void expiredRecordCanBeClaimedWithNewPayload() {
        String key = uniqueKey();
        execute(key, "hash-a", () -> new TestResponse("old"));
        jdbcTemplate.update(
                "UPDATE idempotency_records SET expires_at = ? WHERE idempotency_key = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), key);

        assertThat(execute(key, "hash-b", () -> new TestResponse("new")))
                .isEqualTo(new TestResponse("new"));
    }

    @Test
    void inProgressLeaseIsFifteenMinutesAndCompletionStartsNewTwentyFourHourTtl() {
        String key = uniqueKey();
        Instant before = Instant.now();
        execute(key, "hash-a", () -> {
            Instant leaseExpiresAt = jdbcTemplate.queryForObject(
                    "SELECT expires_at FROM idempotency_records WHERE idempotency_key = ?",
                    java.sql.Timestamp.class, key).toInstant();
            assertThat(leaseExpiresAt).isBetween(
                    before.plusSeconds(14 * 60), before.plusSeconds(16 * 60));
            return new TestResponse("saved");
        });

        Instant completedExpiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM idempotency_records WHERE idempotency_key = ?",
                java.sql.Timestamp.class, key).toInstant();
        assertThat(completedExpiresAt).isBetween(
                before.plusSeconds(23 * 60 * 60), before.plusSeconds(25 * 60 * 60));
    }

    @Test
    void expiredLeaseWithSameHashIsAtomicallyReclaimedOnce() throws Exception {
        String key = uniqueKey();
        UUID recordId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO idempotency_records (
                    id, user_id, endpoint_scope, idempotency_key, request_hash,
                    status, claim_token, created_at, expires_at
                ) VALUES (?, 'user-test', 'POST:/test', ?, 'old-hash',
                    'IN_PROGRESS', ?, ?, ?)
                """,
                recordId, key, UUID.randomUUID(),
                java.sql.Timestamp.from(Instant.now().minusSeconds(1000)),
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        AtomicInteger effects = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
            List<Future<TestResponse>> futures = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    while (true) {
                        try {
                            return execute(key, "old-hash", () ->
                                    new TestResponse("reclaimed-" + effects.incrementAndGet()));
                        } catch (IdempotencyInProgressException ignored) {
                            Thread.onSpinWait();
                        }
                    }
                }));
            }
            start.countDown();
            for (Future<TestResponse> future : futures) {
                assertThat(future.get()).isEqualTo(new TestResponse("reclaimed-1"));
            }
        }

        assertThat(effects).hasValue(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT id FROM idempotency_records WHERE idempotency_key = ?",
                UUID.class, key)).isEqualTo(recordId);
    }

    @Test
    void expiredLeaseWithDifferentHashStillThrowsConflict() {
        String key = uniqueKey();
        UUID recordId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO idempotency_records (
                    id, user_id, endpoint_scope, idempotency_key, request_hash,
                    status, claim_token, created_at, expires_at
                ) VALUES (?, 'user-test', 'POST:/test', ?, 'old-hash',
                    'IN_PROGRESS', ?, ?, ?)
                """,
                recordId, key, UUID.randomUUID(),
                java.sql.Timestamp.from(Instant.now().minusSeconds(1000)),
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)));

        assertThatThrownBy(() -> execute(
                key, "different-hash", () -> new TestResponse("must-not-run")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT request_hash FROM idempotency_records WHERE id = ?",
                String.class, recordId)).isEqualTo("old-hash");
    }

    @Test
    void nestedExecuteAndReplayRestorePreviousThreadLocalClaim() {
        String outerKey = uniqueKey();
        String innerKey = uniqueKey();

        TestResponse outer = execute(outerKey, "outer-hash", () -> {
            UUID outerExecution = idempotencyService.currentExecutionId().orElseThrow();
            TestResponse inner = idempotencyService.replay(
                            "user-test", "POST:/nested", innerKey, "inner-hash", TestResponse.class)
                    .orElseGet(() -> {
                        UUID innerExecution = idempotencyService.currentExecutionId().orElseThrow();
                        assertThat(innerExecution).isNotEqualTo(outerExecution);
                        TestResponse response = new TestResponse("inner");
                        idempotencyService.save(
                                "user-test", "POST:/nested", innerKey, "inner-hash",
                                201, response.id(), response);
                        return response;
                    });
            assertThat(inner).isEqualTo(new TestResponse("inner"));
            assertThat(idempotencyService.currentExecutionId()).contains(outerExecution);
            return new TestResponse("outer");
        });

        assertThat(outer).isEqualTo(new TestResponse("outer"));
        assertThat(idempotencyService.currentExecutionId()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE status = 'COMPLETED' AND idempotency_key IN (?, ?)",
                Integer.class, outerKey, innerKey)).isEqualTo(2);
    }

    @Test
    void callerTransactionRollbackDiscardsCompletionAndReleasesClaim() {
        String key = uniqueKey();
        TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> callerTransaction.executeWithoutResult(status -> {
            execute(key, "caller-tx-hash", () -> new TestResponse("first"));
            throw new IllegalStateException("호출자 트랜잭션 실패");
        })).isInstanceOf(IllegalStateException.class);

        // 완료 기록은 호출자 트랜잭션과 함께 롤백되고, 남은 선점도 해제된다.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?",
                Integer.class, key)).isZero();

        // 같은 키를 다시 쓰면 재생이 아니라 새 실행이 이뤄진다.
        assertThat(execute(key, "caller-tx-hash", () -> new TestResponse("second")))
                .isEqualTo(new TestResponse("second"));
    }

    private TestResponse execute(String key, String hash, java.util.function.Supplier<TestResponse> action) {
        return idempotencyService.execute(
                "user-test", "POST:/test", key, hash,
                TestResponse.class, 201, TestResponse::id, action);
    }

    private String uniqueKey() {
        return UUID.randomUUID().toString();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    record TestResponse(String id) {}
}
