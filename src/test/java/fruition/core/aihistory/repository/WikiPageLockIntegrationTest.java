package fruition.core.aihistory.repository;

import fruition.TestcontainersConfiguration;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WikiPageLockIntegrationTest {

    @Autowired WikiPageVersionRepository repository;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void advisoryLockSerializesSamePage() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondAcquired = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transactionTemplate.execute(status -> {
                repository.lockPage("page_1");
                locked.countDown();
                await(release);
                return null;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            var second = executor.submit(() -> transactionTemplate.execute(status -> {
                repository.lockPage("page_1");
                secondAcquired.countDown();
                return null;
            }));
            assertThat(secondAcquired.await(200, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertThat(secondAcquired.getCount()).isZero();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
