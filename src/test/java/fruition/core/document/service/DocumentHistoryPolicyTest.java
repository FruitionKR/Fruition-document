package fruition.core.document.service;

import fruition.core.document.domain.DocumentContentVersion;
import fruition.core.document.repository.DocumentContentVersionRepository;
import fruition.core.document.repository.PostgresDocumentEditSaveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DocumentHistoryPolicyTest {
    final Instant start = Instant.parse("2026-10-07T00:00:00Z");
    DocumentHistoryPolicy policy;
    List<DocumentContentVersion> rows;

    @BeforeEach void setUp() {
        var repository = mock(DocumentContentVersionRepository.class);
        rows = HistoryRepositoryFixture.install(repository);
        policy = new DocumentHistoryPolicy(repository);
        rows.add(new DocumentContentVersion("doc", 1, 1, "old", "old", "user", start, "initial"));
    }

    @Test void whitespaceAndSixMinuteEditsDoNotCreateHistoryUntilTenMinutes() {
        policy.recordSave("doc", "old ", save(1, "old", 2, "old "), "manual", null, start.plusSeconds(1));
        policy.recordSave("doc", "new", save(2, "old ", 3, "new"), "manual", null, start.plusSeconds(360));
        assertThat(rows).hasSize(1);
        assertThat(policy.recordIfDue("doc", 3, "new", "new", "user", start.plusSeconds(599))).isZero();
        assertThat(policy.recordIfDue("doc", 3, "new", "new", "user", start.plusSeconds(600))).isEqualTo(2);
        assertThat(rows.getLast().getRevision()).isEqualTo(3);
        assertThat(policy.recordIfDue("doc", 3, "new", "new", "user", start.plusSeconds(1200))).isZero();
    }

    @Test void aiPreservesUnsnapshottedBeforeAndAfterWithIndependentNumbers() {
        assertThat(policy.recordSave("doc", "ai", save(27, "manual", 28, "ai"),
                "ai", null, start.plusSeconds(60))).isEqualTo(3);
        assertThat(rows).extracting(DocumentContentVersion::getVersion).containsExactly(1L, 2L, 3L);
        assertThat(rows).extracting(DocumentContentVersion::getRevision).containsExactly(1L, 27L, 28L);
        assertThat(rows.get(1).getMarkdown()).isEqualTo("manual");
        assertThat(rows.getLast().getMarkdown()).isEqualTo("ai");
    }

    @Test void sameBodyAiStillCreatesHistoryWithoutInventingContentChange() {
        var noChange = new PostgresDocumentEditSaveResult(1, "old", "old", 1, "old", start, "user", false, false);
        assertThat(policy.recordSave("doc", "old", noChange, "ai", null, start.plusSeconds(20))).isEqualTo(2);
        assertThat(rows.getLast().getRevision()).isEqualTo(1);
        assertThat(rows.getLast().getRecordType()).isEqualTo("ai");
        var replay = new PostgresDocumentEditSaveResult(1, null, null, 1, "old", start, "user", false, true);
        assertThat(policy.recordSave("doc", "old", replay, "ai", null, start.plusSeconds(30))).isZero();
        assertThat(rows).hasSize(2);
    }

    @Test void aiResetsManualDeadlineAndRevertedContentDoesNotCreateHistory() {
        policy.recordSave("doc", "ai", save(1, "old", 2, "ai"), "ai", null, start.plusSeconds(540));
        assertThat(policy.recordIfDue("doc", 3, "edit", "edit", "user", start.plusSeconds(600))).isZero();
        assertThat(policy.recordIfDue("doc", 4, "ai", "ai", "user", start.plusSeconds(1140))).isZero();
        assertThat(policy.recordIfDue("doc", 5, "edit", "edit", "user", start.plusSeconds(1140))).isEqualTo(3);
    }

    private PostgresDocumentEditSaveResult save(long before, String old, long after, String content) {
        return new PostgresDocumentEditSaveResult(before, old, old, after, content, start, "user", true, false);
    }
}
