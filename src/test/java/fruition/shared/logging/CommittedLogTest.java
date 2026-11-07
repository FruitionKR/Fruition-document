package fruition.shared.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class CommittedLogTest {
    private final org.slf4j.Logger log = LoggerFactory.getLogger(CommittedLogTest.class);
    private final TransactionTemplate tx = new TransactionTemplate(new LocalTransactions());
    @AfterEach void cleanup() { MDC.clear(); }
    @Test void commitsOnceWithOriginalContextAndRestoresCommitThreadContext() {
        try (var capture = new LogCapture(CommittedLogTest.class)) {
            tx.executeWithoutResult(s -> {
                MDC.put("requestId", "original-request");
                CommittedLog.warn(log, "[terminal failure] runId={} code={}", "run-1", "timeout");
                assertThat(capture.messages()).isEmpty();
                MDC.put("requestId", "commit-context");
            });
            assertThat(capture.messages()).containsExactly("[terminal failure] runId=run-1 code=timeout");
            assertThat(capture.events().getFirst().getMDCPropertyMap()).containsEntry("requestId", "original-request");
            assertThat(MDC.get("requestId")).isEqualTo("commit-context");
        }
    }
    @Test void rollbackDoesNotClaimTerminalStateAndNoTransactionStillLogs() {
        try (var capture = new LogCapture(CommittedLogTest.class)) {
            tx.executeWithoutResult(s -> { CommittedLog.warn(log, "rolled back"); s.setRollbackOnly(); });
            assertThat(capture.messages()).isEmpty();
            CommittedLog.warn(log, "already committed");
            assertThat(capture.messages()).containsExactly("already committed");
        }
    }
    @Test void rawProviderTextIsNotAnErrorCode() {
        assertThat(CommittedLog.code("MEETING_NOTES_TIMEOUT")).isEqualTo("MEETING_NOTES_TIMEOUT");
        assertThat(CommittedLog.code("provider failed: private text")).isEqualTo("unknown");
        assertThat(CommittedLog.code(null)).isEqualTo("unknown");
    }
    private static final class LocalTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object t, TransactionDefinition d) {}
        @Override protected void doCommit(DefaultTransactionStatus s) {}
        @Override protected void doRollback(DefaultTransactionStatus s) {}
    }
}
