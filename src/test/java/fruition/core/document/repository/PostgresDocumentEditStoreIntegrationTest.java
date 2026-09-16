package fruition.core.document.repository;

import fruition.TestcontainersConfiguration;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.shared.idempotency.IdempotencyConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PostgresDocumentEditStoreIntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PostgresDocumentEditStore store;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void save_writesStateReceiptAndOutbox() {
        Fixture fixture = fixture("old-hash", "기존");

        PostgresDocumentEditSaveResult result = store.save(
                fixture.workspaceId(), fixture.documentId(), "변경", "new-hash", 1,
                "write-1", fixture.userId(), null);

        assertThat(result.changed()).isTrue();
        assertThat(result.replayed()).isFalse();
        assertThat(result.baseMarkdown()).isEqualTo("기존");
        assertThat(result.revision()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revision FROM document_edit_states WHERE document_id = ?", Long.class,
                fixture.documentId())).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_writes WHERE document_id = ?", Integer.class,
                fixture.documentId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_outbox WHERE document_id = ?", Integer.class,
                fixture.documentId())).isEqualTo(1);
    }

    @Test
    void save_noOpKeepsRevisionAndDoesNotPublishEvent() {
        Fixture fixture = fixture("same-hash", "같음");
        Instant before = stateUpdatedAt(fixture.documentId());

        PostgresDocumentEditSaveResult result = store.save(
                fixture.workspaceId(), fixture.documentId(), "같음", "same-hash", 1,
                "write-noop", fixture.userId(), null);

        assertThat(result.changed()).isFalse();
        assertThat(result.revision()).isEqualTo(1);
        assertThat(stateUpdatedAt(fixture.documentId())).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_writes WHERE document_id = ?", Integer.class,
                fixture.documentId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_outbox WHERE document_id = ?", Integer.class,
                fixture.documentId())).isZero();
    }

    @Test
    void save_replaysSameWriteAndRejectsDifferentPayload() {
        Fixture fixture = fixture("old-hash", "기존");
        PostgresDocumentEditSaveResult first = store.save(
                fixture.workspaceId(), fixture.documentId(), "변경", "new-hash", 1,
                "write-replay", fixture.userId(), null);

        PostgresDocumentEditSaveResult replay = store.save(
                fixture.workspaceId(), fixture.documentId(), "변경", "new-hash", 1,
                "write-replay", fixture.userId(), null);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.revision()).isEqualTo(first.revision());
        assertThatThrownBy(() -> store.save(
                fixture.workspaceId(), fixture.documentId(), "다른", "other-hash", 1,
                "write-replay", fixture.userId(), null))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_outbox WHERE document_id = ?", Integer.class,
                fixture.documentId())).isEqualTo(1);
    }

    @Test
    void save_rejectsStaleRevisionWithoutReceiptOrOutbox() {
        Fixture fixture = fixture("old-hash", "기존");
        store.save(fixture.workspaceId(), fixture.documentId(), "변경", "new-hash", 1,
                "write-first", fixture.userId(), null);

        assertThatThrownBy(() -> store.save(
                fixture.workspaceId(), fixture.documentId(), "다음", "next-hash", 1,
                "write-stale", fixture.userId(), null))
                .isInstanceOf(DocumentVersionConflictException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_writes WHERE revision_write_id = ?", Integer.class,
                "write-stale")).isZero();
    }

    @Test
    void save_concurrentCasAllowsExactlyOneWriter() throws Exception {
        Fixture fixture = fixture("old-hash", "기존");
        CountDownLatch bothReadBaseRevision = new CountDownLatch(2);
        PostgresDocumentEditStore racingStore = racingStore(changed -> {
            bothReadBaseRevision.countDown();
            await(bothReadBaseRevision);
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SaveAttempt> first = executor.submit(() -> saveAttempt(racingStore, fixture,
                    "첫", "first-hash", "write-a"));
            Future<SaveAttempt> second = executor.submit(() -> saveAttempt(racingStore, fixture,
                    "둘", "second-hash", "write-b"));

            SaveAttempt firstAttempt = first.get();
            SaveAttempt secondAttempt = second.get();
            assertThat(List.of(firstAttempt, secondAttempt))
                    .extracting(SaveAttempt::conflict)
                    .containsExactlyInAnyOrder(false, true);
            SaveAttempt winner = firstAttempt.conflict() ? secondAttempt : firstAttempt;
            SaveAttempt loser = firstAttempt.conflict() ? firstAttempt : secondAttempt;
            String winnerWriteId = firstAttempt.conflict() ? "write-b" : "write-a";
            String winnerHash = firstAttempt.conflict() ? "second-hash" : "first-hash";
            assertSuccess(winner, true, false, 2, winnerHash);
            assertConflict(loser);
            assertPersistedChange(fixture, firstAttempt.conflict() ? "둘" : "첫",
                    winnerHash, winnerWriteId);
        }
    }

    @Test
    void save_concurrentNoOpAndChangeGetsConflictAfterChangeFirst() throws Exception {
        Fixture fixture = fixture("old-hash", "기존");
        CountDownLatch bothReadBaseRevision = new CountDownLatch(2);
        CountDownLatch changeCommitted = new CountDownLatch(1);
        PostgresDocumentEditStore racingStore = racingStore(changed -> {
            bothReadBaseRevision.countDown();
            await(bothReadBaseRevision);
            if (!changed) {
                await(changeCommitted);
            }
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SaveAttempt> changed = executor.submit(() -> {
                try {
                    return saveAttempt(racingStore, fixture, "변경", "new-hash", "write-change");
                } finally {
                    changeCommitted.countDown();
                }
            });
            Future<SaveAttempt> noOp = executor.submit(() -> saveAttempt(racingStore, fixture,
                    "기존", "old-hash", "write-noop-race"));

            SaveAttempt changedAttempt = changed.get();
            SaveAttempt noOpAttempt = noOp.get();
            assertSuccess(changedAttempt, true, false, 2, "new-hash");
            assertConflict(noOpAttempt);
            assertPersistedChange(fixture, "변경", "new-hash", "write-change");
        }
    }

    @Test
    void save_concurrentSameWriteReplaysAfterUniqueRace() throws Exception {
        Fixture fixture = fixture("old-hash", "기존");
        CountDownLatch bothReadBaseRevision = new CountDownLatch(2);
        PostgresDocumentEditStore racingStore = racingStore(changed -> {
            bothReadBaseRevision.countDown();
            await(bothReadBaseRevision);
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SaveAttempt> first = executor.submit(() -> saveAttempt(racingStore, fixture,
                    "변경", "new-hash", "write-same"));
            Future<SaveAttempt> second = executor.submit(() -> saveAttempt(racingStore, fixture,
                    "변경", "new-hash", "write-same"));

            List<SaveAttempt> attempts = List.of(first.get(), second.get());
            assertThat(attempts).extracting(SaveAttempt::conflict).containsExactly(false, false);
            assertThat(attempts).allSatisfy(attempt ->
                    assertSuccess(attempt, true, attempt.result().replayed(), 2, "new-hash"));
            assertThat(attempts)
                    .extracting(attempt -> attempt.result().replayed())
                    .containsExactlyInAnyOrder(false, true);
            assertPersistedChange(fixture, "변경", "new-hash", "write-same");
        }
    }

    @Test
    void save_framesEventIdInputsWithoutChangingItsLength() {
        Fixture first = fixture("old-hash", "기존", "a");
        Fixture second = fixture("old-hash", "기존", "a:b");

        store.save(first.workspaceId(), first.documentId(), "변경", "new-hash", 1,
                "b:c", first.userId(), null);
        store.save(second.workspaceId(), second.documentId(), "변경", "new-hash", 1,
                "c", second.userId(), null);

        String firstEventId = eventId(first.documentId());
        String secondEventId = eventId(second.documentId());
        assertThat(firstEventId).hasSize(64);
        assertThat(secondEventId).hasSize(64);
        assertThat(firstEventId).isNotEqualTo(secondEventId);
    }

    @Test
    void save_acceptsMaximumWriteIdAndKeepsEventIdBounded() {
        Fixture fixture = fixture("old-hash", "기존");
        String writeId = "w".repeat(255);

        store.save(fixture.workspaceId(), fixture.documentId(), "변경", "new-hash", 1,
                writeId, fixture.userId(), null);

        Integer eventIdLength = jdbcTemplate.queryForObject(
                "SELECT char_length(event_id) FROM document_edit_outbox WHERE document_id = ?",
                Integer.class, fixture.documentId());
        assertThat(eventIdLength).isEqualTo(64);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT event_id FROM document_edit_outbox WHERE document_id = ?",
                String.class, fixture.documentId()))
                .isEqualTo(sha256(fixture.documentId().getBytes(StandardCharsets.UTF_8).length + ":"
                        + fixture.documentId() + writeId));
    }

    @Test
    void save_rollsBackStateAndReceiptWhenOutboxInsertFails() {
        Fixture fixture = fixture("old-hash", "기존");
        jdbcTemplate.update("""
                INSERT INTO document_edit_outbox(
                    event_id, document_id, workspace_id, revision, content_hash,
                    event_type, schema_version, created_at, published
                ) VALUES (?, ?, ?, 99, 'occupied', 'document.edit.saved.v1', 1, now(), false)
                """, sha256(fixture.documentId().getBytes(StandardCharsets.UTF_8).length + ":"
                        + fixture.documentId() + "write-rollback"), fixture.documentId(), fixture.workspaceId());

        assertThatThrownBy(() -> store.save(
                fixture.workspaceId(), fixture.documentId(), "변경", "new-hash", 1,
                "write-rollback", fixture.userId(), null)).isInstanceOf(RuntimeException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revision FROM document_edit_states WHERE document_id = ?", Long.class,
                fixture.documentId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_edit_writes WHERE revision_write_id = ?", Integer.class,
                "write-rollback")).isZero();
    }

    private SaveAttempt saveAttempt(PostgresDocumentEditStore racingStore, Fixture fixture, String markdown,
                                    String hash, String writeId) {
        try {
            return new SaveAttempt(racingStore.save(fixture.workspaceId(), fixture.documentId(), markdown,
                    hash, 1, writeId, fixture.userId(), null), false);
        } catch (DocumentVersionConflictException exception) {
            return new SaveAttempt(null, true);
        }
    }

    private void assertSuccess(SaveAttempt attempt, boolean changed, boolean replayed,
                               long revision, String contentHash) {
        assertThat(attempt.conflict()).isFalse();
        assertThat(attempt.result()).isNotNull();
        assertThat(attempt.result().changed()).isEqualTo(changed);
        assertThat(attempt.result().replayed()).isEqualTo(replayed);
        assertThat(attempt.result().revision()).isEqualTo(revision);
        assertThat(attempt.result().contentHash()).isEqualTo(contentHash);
    }

    private void assertConflict(SaveAttempt attempt) {
        assertThat(attempt.conflict()).isTrue();
        assertThat(attempt.result()).isNull();
    }

    private void assertPersistedChange(Fixture fixture, String markdown, String contentHash,
                                       String revisionWriteId) {
        assertThat(jdbcTemplate.queryForList(
                "SELECT markdown, content_hash, revision FROM document_edit_states WHERE document_id = ?",
                fixture.documentId()))
                .containsExactly(Map.of("markdown", markdown, "content_hash", contentHash, "revision", 2L));
        assertThat(jdbcTemplate.queryForList("""
                SELECT revision_write_id, changed, result_revision, result_content_hash
                FROM document_edit_writes
                WHERE document_id = ?
                """, fixture.documentId()))
                .containsExactly(Map.of("revision_write_id", revisionWriteId, "changed", true,
                        "result_revision", 2L, "result_content_hash", contentHash));
        assertThat(jdbcTemplate.queryForList("""
                SELECT revision, content_hash
                FROM document_edit_outbox
                WHERE document_id = ?
                """, fixture.documentId()))
                .containsExactly(Map.of("revision", 2L, "content_hash", contentHash));
    }

    private Fixture fixture(String hash, String markdown) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return fixture(hash, markdown, "doc_" + suffix, "ws_" + suffix, "user_" + suffix);
    }

    private Fixture fixture(String hash, String markdown, String documentId) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return fixture(hash, markdown, documentId, "ws_" + suffix, "user_" + suffix);
    }

    private Fixture fixture(String hash, String markdown, String documentId,
                            String workspaceId, String userId) {
        Fixture fixture = new Fixture(workspaceId, userId, documentId);
        jdbcTemplate.update("""
                INSERT INTO documents(
                    id, byte_size, content_hash, filename, display_name, normalized_filename,
                    mime_type, source_uri, status, uploaded_at, updated_at, user_id, workspace_id,
                    current_content_hash, current_version, document_role, sort_order
                ) VALUES (?, 1, ?, 'doc.md', 'doc', 'doc.md', 'text/markdown', NULL,
                          'completed', now(), now(), ?, ?, ?, 1, 'EDITABLE', 0)
                """, fixture.documentId(), hash, fixture.userId(), fixture.workspaceId(), hash);
        jdbcTemplate.update("""
                INSERT INTO document_edit_states(
                    document_id, markdown, content_hash, revision, created_at, updated_at
                ) VALUES (?, ?, ?, 1, now(), now())
                """, fixture.documentId(), markdown, hash);
        return fixture;
    }

    private PostgresDocumentEditStore racingStore(PostgresDocumentEditStore.BeforeStateWrite beforeStateWrite) {
        return new PostgresDocumentEditStore(jdbcTemplate, transactionManager, beforeStateWrite);
    }

    private String eventId(String documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT event_id FROM document_edit_outbox WHERE document_id = ?", String.class, documentId);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트 대기가 중단되었습니다.", exception);
        }
    }

    private Instant stateUpdatedAt(String documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM document_edit_states WHERE document_id = ?",
                Instant.class, documentId);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record SaveAttempt(PostgresDocumentEditSaveResult result, boolean conflict) {}

    private record Fixture(String workspaceId, String userId, String documentId) {}
}
