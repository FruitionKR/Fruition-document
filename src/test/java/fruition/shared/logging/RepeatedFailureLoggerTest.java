package fruition.shared.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;

class RepeatedFailureLoggerTest {
    @Test
    void retainsFirstStackSummarizesRepeatsAndReportsRecovery() {
        Logger logger = (Logger) LoggerFactory.getLogger("repeat-test");
        ListAppender<ILoggingEvent> output = new ListAppender<>(); output.start(); logger.addAppender(output);
        try {
            AtomicLong now = new AtomicLong();
            RepeatedFailureLogger failures = new RepeatedFailureLogger(logger, now::get, Duration.ofMinutes(1), 10);
            var error = new IllegalStateException("temporary failure");
            failures.error("run-1", "operation failed", error);
            for (int i = 1; i <= 59; i++) { now.set(i * 1000L); failures.error("run-1", "operation failed", error); }
            assertThat(output.list).hasSize(1);
            assertThat(output.list.getFirst().getThrowableProxy()).isNotNull();
            now.set(60_000); failures.error("run-1", "operation failed", error);
            assertThat(output.list).hasSize(2);
            assertThat(output.list.get(1).getFormattedMessage()).contains("totalCount=61", "suppressedCount=60");
            assertThat(output.list.get(1).getLevel()).isEqualTo(Level.ERROR);
            assertThat(output.list.get(1).getThrowableProxy()).isNull();
            failures.error("run-1", "operation failed", error);
            failures.recovered("run-1");
            assertThat(output.list.get(2).getFormattedMessage()).contains("[반복 오류 복구]", "totalCount=62", "suppressedCount=1");
            failures.recovered("run-1"); assertThat(output.list).hasSize(3);
            failures.error("run-1", "new incident", error); assertThat(output.list).hasSize(4);
        } finally { logger.detachAppender(output); output.stop(); }
    }
    @Test
    void distinguishesTargetsTypesAndSeverityAndBoundsRetainedKeys() {
        Logger logger = (Logger) LoggerFactory.getLogger("repeat-capacity-test");
        ListAppender<ILoggingEvent> output = new ListAppender<>(); output.start(); logger.addAppender(output);
        try {
            AtomicLong now = new AtomicLong();
            RepeatedFailureLogger failures = new RepeatedFailureLogger(logger, now::get, Duration.ofMinutes(1), 2);
            failures.warn("one", "first", new IllegalStateException());
            failures.warn("two", "other target", new IllegalStateException());
            failures.warn("two", "other type", new IllegalArgumentException());
            failures.error("two", "higher severity", new IllegalArgumentException());
            assertThat(output.list).hasSize(4);
            failures.warn("three", "capacity", null);
            failures.warn("one", "evicted is visible again", null);
            assertThat(output.list).hasSize(6);
            now.set(Duration.ofHours(2).toMillis());
            failures.warn("one", "expired is visible again", null);
            assertThat(output.list).hasSize(7);
        } finally { logger.detachAppender(output); output.stop(); }
    }
}
