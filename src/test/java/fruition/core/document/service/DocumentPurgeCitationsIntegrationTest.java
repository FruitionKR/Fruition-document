package fruition.core.document.service;

import fruition.TestcontainersConfiguration;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.repository.ChatSessionRepository;
import fruition.core.chat.service.ChatTurnRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영구 삭제 뒤 채팅 인용(chat_message_references.quote)과 그 변경 기록에 남던 원문을 실제 Postgres로 확인한다.
 * 문서 FK가 ON DELETE SET NULL이라 삭제 뒤에는 document_id로 찾을 수 없다.
 */
@SpringBootTest(properties = "app.document-trash.purge-delay-ms=3600000")
@Import(TestcontainersConfiguration.class)
class DocumentPurgeCitationsIntegrationTest {

    static final String BODY = "비밀 본문";
    static final String OTHER = "다른 문서 근거";
    static final String ANSWER = "채팅 답변";

    @Autowired DocumentTrashPurgeWorker worker;
    @Autowired DocumentCopyScrubber scrubber;
    @Autowired ChatSessionRepository sessions;
    @Autowired ChatTurnRecorder recorder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;

    final String workspace = "ws_" + UUID.randomUUID();
    final String user = "user_" + UUID.randomUUID();
    final Instant now = Instant.now().plus(Duration.ofDays(365));

    @Test
    void citationLosesOnlyTheQuoteAndOtherCitationAndAnswerStay() {
        String purged = insertDocument(now.minus(Duration.ofDays(31)));
        String kept = insertDocument(null);
        String message = insertAnswer();
        long citation = insertCitation(message, purged, BODY, sourceRefs(purged));
        long other = insertCitation(message, kept, OTHER, sourceRefs(kept));

        worker.purge(now);

        assertThat(quote(citation)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT document_id FROM chat_message_references WHERE id = ?", String.class, citation)).isNull();
        assertThat(jdbc.queryForObject("SELECT rank FROM chat_message_references WHERE id = ?", Integer.class, citation)).isOne();
        assertThat(jdbc.queryForObject("SELECT source_refs FROM chat_message_references WHERE id = ?", String.class, citation)).contains(purged);
        assertThat(quote(other)).isEqualTo(OTHER);
        assertThat(jdbc.queryForObject("SELECT content FROM chat_messages WHERE id = ?", String.class, message)).isEqualTo(ANSWER);
    }

    @Test
    void citationWithoutSourceRefsIsFoundByItsDocumentBeforeTheForeignKeyClearsIt() {
        String purged = insertDocument(now.minus(Duration.ofDays(31)));
        long citation = insertCitation(insertAnswer(), purged, BODY, null);

        worker.purge(now);

        assertThat(quote(citation)).isEmpty();
    }

    @Test
    void multiDocumentCitationLosesTheQuoteButKeepsItsLiveDocument() {
        String purged = insertDocument(now.minus(Duration.ofDays(31)));
        String kept = insertDocument(null);
        long citation = insertCitation(insertAnswer(), kept, BODY, sourceRefs(kept, purged));

        worker.purge(now);

        assertThat(quote(citation)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT document_id FROM chat_message_references WHERE id = ?", String.class, citation)).isEqualTo(kept);
    }

    @Test
    void cancellingARunStillUndoesItsCitationAfterThePurge() {
        String purged = insertDocument(now.minus(Duration.ofDays(31)));
        String kept = insertDocument(null);
        String run = insertRun();
        String message = insertAnswer();
        long citation = journaled(run, () -> insertCitation(message, purged, BODY, sourceRefs(purged)));
        long other = journaled(run, () -> insertCitation(message, kept, OTHER, sourceRefs(kept)));

        worker.purge(now);

        assertThat(journalEqualsRow(citation)).isTrue();
        assertThat(journalEqualsRow(other)).isTrue();
        assertThat(journalText(citation)).doesNotContain(BODY);
        assertThat(journalText(other)).contains(OTHER);

        jdbc.update("UPDATE ai_task_runs SET status = 'rolling_back' WHERE id = ?", run);
        jdbc.queryForList("SELECT id FROM ai_task_changes WHERE run_id = ? ORDER BY id DESC", Long.class, run)
                .forEach(change -> jdbc.queryForObject("SELECT undo_ai_task_change(?, ?)", Object.class, run, change));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message_references WHERE id IN (?, ?)", Integer.class, citation, other)).isZero();
    }

    @Test
    void orphanSweepBlanksCitationsOfDocumentsPurgedEarlierAndTheirJournal() {
        String purged = insertDocument(null);
        String kept = insertDocument(null);
        String run = insertRun();
        String message = insertAnswer();
        long citation = journaled(run, () -> insertCitation(message, purged, BODY, sourceRefs(purged)));
        long other = insertCitation(message, kept, OTHER, sourceRefs(kept));
        // 이 기능 전의 영구 삭제: FK만 document_id를 비우고 quote와 변경 기록은 그대로 남았다.
        jdbc.update("DELETE FROM documents WHERE id = ?", purged);

        assertThat(scrubber.scrubOrphans(100)).isPositive();

        assertThat(quote(citation)).isEmpty();
        assertThat(journalEqualsRow(citation)).isTrue();
        assertThat(quote(other)).isEqualTo(OTHER);
    }

    @Test
    void orphanSweepBlanksALiveDocumentsCitationWhenAnEarlierPurgedDocumentIsOnlyInSourceRefs() {
        String purged = insertDocument(null);
        String kept = insertDocument(null);
        String another = insertDocument(null);
        String trashed = insertDocument(now.minus(Duration.ofDays(1)));
        String run = insertRun();
        String message = insertAnswer();
        long mixed = journaled(run, () -> insertCitation(message, kept, BODY, sourceRefs(kept, purged)));
        long live = insertCitation(message, kept, OTHER, sourceRefs(kept, another));
        long inTrash = insertCitation(message, kept, OTHER, sourceRefs(kept, trashed));
        long web = insertCitation(message, kept, OTHER, sourceRefs(kept, "web:https://example.com"));
        // 이 기능 전의 영구 삭제: 인용은 대표 문서만 FK로 걸려 있어 그대로 남았다.
        jdbc.update("DELETE FROM documents WHERE id = ?", purged);

        // 같은 DB를 쓰는 다른 테스트 컨텍스트의 영구 삭제 스케줄러가 시작하면서 먼저 정리할 수 있다.
        // 반환 건수는 그 경쟁에 따라 0일 수 있으므로, 정리 결과는 아래 행 상태로 확인한다.
        scrubber.scrubOrphans(100);

        assertThat(quote(mixed)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT document_id FROM chat_message_references WHERE id = ?", String.class, mixed)).isEqualTo(kept);
        assertThat(journalEqualsRow(mixed)).isTrue();
        assertThat(journalText(mixed)).doesNotContain(BODY);
        assertThat(quote(live)).isEqualTo(OTHER);
        assertThat(quote(inTrash)).isEqualTo(OTHER);
        assertThat(quote(web)).isEqualTo(OTHER);
    }

    @Test
    void citationLookupsUseTheirIndexes() {
        assertThat(plan("""
                SELECT id FROM chat_message_references
                WHERE document_id = 'd' OR source_refs::jsonb @> jsonb_build_array(jsonb_build_object('source_document_id', 'd'))
                """)).contains("idx_cmr_document", "idx_cmr_source_refs");
        assertThat(plan("SELECT id FROM chat_message_references WHERE document_id IS NULL AND quote <> ''"))
                .contains("idx_cmr_orphan_quote");
        assertThat(plan("SELECT id FROM chat_message_references WHERE document_id IS NOT NULL AND quote <> '' AND jsonb_path_exists(source_refs::jsonb, '$[1]')"))
                .contains("idx_cmr_multi_source_quote");
        assertThat(plan("SELECT id FROM ai_task_changes WHERE table_name = 'chat_message_references' AND row_key->>'id' = '1'"))
                .contains("idx_ai_task_changes_citation");
    }

    private String plan(String query) {
        return transaction.execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            return String.join("\n", jdbc.queryForList("EXPLAIN " + query, String.class));
        });
    }

    private <T> T journaled(String run, java.util.function.Supplier<T> change) {
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.ai_task_run_id', ?, true)", String.class, run);
            return change.get();
        });
    }

    private boolean journalEqualsRow(long citation) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT c.after_value = to_jsonb(r) FROM ai_task_changes c
                JOIN chat_message_references r ON r.id = (c.row_key->>'id')::bigint
                WHERE c.table_name = 'chat_message_references' AND r.id = ?
                """, Boolean.class, citation));
    }

    private String journalText(long citation) {
        return jdbc.queryForObject("SELECT after_value::text FROM ai_task_changes "
                + "WHERE table_name = 'chat_message_references' AND row_key->>'id' = ?", String.class, String.valueOf(citation));
    }

    private String quote(long citation) {
        return jdbc.queryForObject("SELECT quote FROM chat_message_references WHERE id = ?", String.class, citation);
    }

    private static String sourceRefs(String... documents) {
        return java.util.Arrays.stream(documents)
                .map(document -> "{\"source_document_id\":\"" + document + "\",\"source_block_id\":\"B0001\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private long insertCitation(String message, String document, String quote, String sourceRefs) {
        return jdbc.queryForObject("""
                INSERT INTO chat_message_references(chat_message_id, reference_type, document_id, rank, source_block_ids, source_refs, quote)
                VALUES (?, 'source_block', ?, 1, '["B0001"]', ?, ?) RETURNING id
                """, Long.class, message, document, sourceRefs, quote);
    }

    private String insertAnswer() {
        String key = UUID.randomUUID().toString();
        String session = "session-" + key;
        sessions.save(new ChatSession(session, workspace, user, null));
        recorder.createPendingPair(session, "pair-" + key, "user-" + key, "assistant-" + key, "질문", Instant.now());
        jdbc.update("UPDATE chat_messages SET content = ? WHERE id = ?", ANSWER, "assistant-" + key);
        return "assistant-" + key;
    }

    private String insertRun() {
        String id = "run_" + UUID.randomUUID();
        jdbc.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind, command) VALUES (?, ?, ?, 'query', '{}'::jsonb)",
                id, workspace, user);
        return id;
    }

    private String insertDocument(Instant deletedAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO documents(
                    id, byte_size, content_hash, filename, display_name, normalized_filename,
                    mime_type, source_uri, status, uploaded_at, updated_at, user_id, workspace_id,
                    current_content_hash, current_version, document_role, sort_order, deleted_at, deleted_by
                ) VALUES (?, 1, 'hash', ?, 'a', ?, 'application/pdf', ?, 'completed', now(), now(), ?, ?,
                          'hash', 1, 'ORIGINAL', 0, ?, ?)
                """, id, id + ".pdf", id + ".pdf", null, user, workspace,
                deletedAt == null ? null : Timestamp.from(deletedAt), deletedAt == null ? null : user);
        return id;
    }
}
