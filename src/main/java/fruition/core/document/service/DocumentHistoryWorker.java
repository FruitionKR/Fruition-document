package fruition.core.document.service;

import fruition.core.document.repository.DocumentContentVersionRepository;
import fruition.core.document.repository.PostgresDocumentEditStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

@Component
public class DocumentHistoryWorker {
    private static final Logger log = LoggerFactory.getLogger(DocumentHistoryWorker.class);
    private final DocumentContentVersionRepository versions;
    private final PostgresDocumentEditStore store;
    private final TransactionTemplate transaction;
    private final JdbcTemplate jdbc;
    private final DocumentHistoryPolicy policy;

    public DocumentHistoryWorker(DocumentContentVersionRepository versions, PostgresDocumentEditStore store,
                                 TransactionTemplate transaction, JdbcTemplate jdbc) {
        this.versions = versions;
        this.store = store;
        this.transaction = transaction;
        this.jdbc = jdbc;
        this.policy = new DocumentHistoryPolicy(versions);
    }

    @Scheduled(fixedDelayString = "${app.document-history.poll-interval-ms:10000}")
    public void runScheduled() {
        recordDue(Instant.now());
    }

    public void recordDue(Instant now) {
        for (String id : versions.findDueDocumentIds(now.minus(DocumentHistoryPolicy.INTERVAL))) {
            try {
                transaction.executeWithoutResult(status -> {
                    // Same lock as document saves; skip a busy document and retry next poll.
                    var actors = jdbc.query("SELECT user_id FROM documents WHERE id = ? AND deleted_at IS NULL FOR UPDATE SKIP LOCKED",
                            (rs, row) -> rs.getString(1), id);
                    if (actors.isEmpty()) return;
                    // Recheck eligibility after acquiring the lock (conversion/deletion may have started).
                    if (!versions.isHistoryEligible(id)) return;
                    store.findState(id).ifPresent(state -> policy.recordIfDue(id, state.getRevision(),
                            state.getMarkdown(), state.getContentHash(), actors.getFirst(), now));
                });
            } catch (RuntimeException exception) {
                log.warn("문서 이력 기록 실패: documentId={}", id, exception);
            }
        }
    }
}
