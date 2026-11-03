package fruition.shared.scheduling;

import fruition.TestcontainersConfiguration;
import fruition.core.DocumentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 같은 회차를 여러 Pod가 동시에 점유하려 해도 한 Pod만 맡고, 점유가 끝나면 다시 맡을 수 있다. */
@SpringBootTest(classes = DocumentApplication.class)
@Import(TestcontainersConfiguration.class)
class SchedulerLeaseIntegrationTest {

    @Autowired SchedulerLease lease;

    @Test
    void secondAcquireWithinHoldIsRefusedAndExpiredLeaseIsTakenAgain() {
        String name = "test-" + UUID.randomUUID();
        assertThat(lease.tryAcquire(name, Duration.ofMinutes(1))).isTrue();
        assertThat(lease.tryAcquire(name, Duration.ofMinutes(1))).isFalse();

        String expiring = "test-" + UUID.randomUUID();
        assertThat(lease.tryAcquire(expiring, Duration.ZERO)).isTrue();
        assertThat(lease.tryAcquire(expiring, Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void concurrentAcquiresGiveOneWinner() throws Exception {
        String name = "test-" + UUID.randomUUID();
        int pods = 8;
        var barrier = new CyclicBarrier(pods);
        var pool = Executors.newFixedThreadPool(pods);
        try {
            var results = IntStream.range(0, pods).mapToObj(i -> pool.submit(() -> {
                barrier.await();
                return lease.tryAcquire(name, Duration.ofMinutes(1));
            })).toList();
            long winners = 0;
            for (Future<Boolean> result : results) if (result.get()) winners++;
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }
}
