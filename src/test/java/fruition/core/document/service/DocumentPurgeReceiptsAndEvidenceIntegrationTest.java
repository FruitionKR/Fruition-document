package fruition.core.document.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fruition.TestcontainersConfiguration;
import fruition.core.agent.repository.AgentRunCommandRepository;
import fruition.core.aitask.service.AiTaskResultApplier;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영구 삭제 뒤 receipt, 질의 projection, 그 변경 기록에 남던 복사본을 실제 Postgres로 확인한다.
 * 문서 id가 없는 질의 행은 근거의 source_document_id로 찾고, 늦게 온 결과는 반영할 때 거른다.
 */
@SpringBootTest(properties = "app.document-trash.purge-delay-ms=3600000")
@Import(TestcontainersConfiguration.class)
class DocumentPurgeReceiptsAndEvidenceIntegrationTest {

    static final String BODY = "비밀 본문";
    static final String OTHER = "다른 문서 근거";
    static final String ANSWER = "채팅 답변";
    static final long LOCK_WAIT_MILLIS = 500;

    @Autowired DocumentTrashPurgeWorker worker;
    @Autowired DocumentCopyScrubber scrubber;
    @Autowired AiTaskResultApplier applier;
    @Autowired AgentRunCommandRepository projections;
    @Autowired ChatSessionRepository sessions;
    @Autowired ChatTurnRecorder recorder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TransactionTemplate transaction;

    final String workspace = "ws_" + UUID.randomUUID();
    final String user = "user_" + UUID.randomUUID();
    final Instant now = Instant.now().plus(Duration.ofDays(365));

    @Test
    void ownAgentReceiptKeepsItsKeyAndLosesTheBody() throws Exception {
        String purged = insertDocument(now.minus(Duration.ofDays(31)));
        String kept = insertDocument(null);
        String own = insertRun("agent", "{\"document_id\":\"" + purged + "\"}");
        String other = insertRun("agent", "{\"document_id\":\"" + kept + "\"}");
        insertReceipt(own, "agent", "{\"request\":{\"editor_snapshot\":{\"markdown\":\"" + BODY + "\"}}}");
        insertReceipt(other, "agent", "{\"request\":{\"editor_snapshot\":{\"markdown\":\"" + OTHER + "\"}}}");

        worker.purge(now);

        assertThat(receiptPayload(own)).isNull();
        assertThat(receiptPayload(other)).contains(OTHER);
    }

    @Test
    void redeliveryAfterPurgeDoesNotApplyTheAgentResultAgain() throws Exception {
        String purged = insertDocument(now.minus(Duration.ofDays(31)));
        String run = insertRun("agent", "{\"document_id\":\"" + purged + "\"}");
        projections.create(run, workspace, user, null, null, null);
        JsonNode event = agentEvent(run, evidence(purged, BODY));
        assertThat(applier.applyAgent(event).applied()).isTrue();

        worker.purge(now);

        assertThat(receiptPayload(run)).isNull();
        assertThat(applier.applyAgent(event).applied()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_result_receipts WHERE run_id = ?", Integer.class, run)).isOne();
    }

    @Test
    void evidenceInChatProjectionIsBlankedAndAnswerAndOtherEvidenceStay() throws Exception {
        String purged = insertDocument(now.minus(Duration.ofDays(31)));
        String kept = insertDocument(null);
        String chat = insertProjection(chatResult(purged, kept));
        String unrelated = insertProjection(chatResult(kept, kept));

        worker.purge(now);

        JsonNode result = projectionResult(chat);
        assertThat(result.path("chat").path("answer").asText()).isEqualTo(ANSWER);
        assertThat(snippetText(result.path("chat").path("evidence_snippets"), purged)).isEmpty();
        assertThat(snippetText(result.path("chat").path("evidence_snippets"), kept)).isEqualTo(OTHER);
        assertThat(projectionResult(unrelated).toString()).contains(BODY);
    }

    @Test
    void evidenceCitingThePurgedDocumentOnlyInSourceRefsIsBlankedAndOtherEvidenceStays() throws Exception {
        String purged = insertDocument(now.minus(Duration.ofDays(31)));
        String kept = insertDocument(null);
        String another = insertDocument(null);
        String snippets = multiDocumentEvidence(kept, purged, another);
        String chat = insertProjection("{\"action\":\"chat_answer\",\"chat\":{\"answer\":\"" + ANSWER + "\",\"evidence_snippets\":" + snippets + "}}");
        String unrelated = insertProjection("{\"action\":\"chat_answer\",\"chat\":{\"answer\":\"" + ANSWER + "\",\"evidence_snippets\":"
                + multiDocumentEvidence(kept, another, another) + "}}");
        jdbc.update("""
                INSERT INTO ai_task_changes(run_id, table_name, row_key, before_value, after_value)
                SELECT run_id, 'agent_apply_projections', jsonb_build_object('run_id', run_id),
                       to_jsonb(p) - 'result', to_jsonb(p) FROM agent_apply_projections p WHERE run_id = ?
                """, chat);
        String queryRun = insertRun("query", "{}");
        insertReceipt(queryRun, "query", "{\"payload\":{\"answer\":\"" + ANSWER + "\",\"evidence_snippets\":" + snippets + "}}");
        String agentRun = insertRun("agent", "{}");
        insertReceipt(agentRun, "agent", "{\"payload\":{\"chat\":{\"evidence_snippets\":" + snippets + "}}}");

        worker.purge(now);

        JsonNode evidence = projectionResult(chat).path("chat").path("evidence_snippets");
        assertThat(evidence.get(0).path("text").asText()).isEmpty();
        assertThat(evidence.get(0).path("source_refs").toString()).contains(purged);
        assertThat(evidence.get(1).path("text").asText()).isEqualTo(OTHER);
        assertThat(projectionResult(chat).path("chat").path("answer").asText()).isEqualTo(ANSWER);
        assertThat(projectionResult(unrelated).toString()).contains(BODY).contains(OTHER);
        assertThat(jdbc.queryForObject("""
                SELECT c.after_value = to_jsonb(p) FROM ai_task_changes c JOIN agent_apply_projections p ON p.run_id = c.run_id
                WHERE c.run_id = ?""", Boolean.class, chat)).isTrue();
        assertThat(receiptPayload(queryRun)).doesNotContain(BODY).contains(OTHER).contains(ANSWER);
        assertThat(receiptPayload(agentRun)).isNull();
    }

    @Test
    void chatProjectionJournalStaysEqualToTheRowSoCancelDoesNotConflict() throws Exception {
        String purged = insertDocument(now.minus(Duration.ofDays(31)));
        String kept = insertDocument(null);
        String chat = insertProjection(chatResult(purged, kept));
        jdbc.update("""
                INSERT INTO ai_task_changes(run_id, table_name, row_key, before_value, after_value)
                SELECT run_id, 'agent_apply_projections', jsonb_build_object('run_id', run_id),
                       to_jsonb(p) - 'result', to_jsonb(p) FROM agent_apply_projections p WHERE run_id = ?
                """, chat);

        worker.purge(now);

        assertThat(jdbc.queryForObject("""
                SELECT c.after_value = to_jsonb(p) FROM ai_task_changes c JOIN agent_apply_projections p ON p.run_id = c.run_id
                WHERE c.run_id = ?""", Boolean.class, chat)).isTrue();
        assertThat(jdbc.queryForObject("SELECT after_value::text FROM ai_task_changes WHERE run_id = ?", String.class, chat))
                .doesNotContain(BODY).contains(OTHER).contains(ANSWER);
    }

    @Test
    void chatAgentReceiptLosesThePayloadBecauseNothingReadsIt() throws Exception {
        String purged = insertDocument(now.minus(Duration.ofDays(31)));
        String kept = insertDocument(null);
        String chat = insertRun("agent", "{}");
        String unrelated = insertRun("agent", "{}");
        insertReceipt(chat, "agent", "{\"payload\":" + chatResult(purged, kept) + "}");
        insertReceipt(unrelated, "agent", "{\"payload\":" + chatResult(kept, kept) + "}");

        worker.purge(now);

        assertThat(receiptPayload(chat)).isNull();
        assertThat(receiptPayload(unrelated)).contains(BODY);
    }

    @Test
    void queryReceiptKeepsWhatRedeliveryReadsAndLosesOnlyThePurgedEvidence() throws Exception {
        String purged = insertDocument(now.minus(Duration.ofDays(31)));
        String kept = insertDocument(null);
        String run = insertRun("query", "{}");
        String session = startSession(run);
        JsonNode event = queryEvent(run, session, purged, kept);
        assertThat(applier.applyQuery(event).error()).isNull();

        worker.purge(now);

        JsonNode stored = mapper.readTree(receiptPayload(run));
        assertThat(stored.path("payload").path("answer").asText()).isEqualTo(ANSWER);
        assertThat(snippetText(stored.path("payload").path("evidence_snippets"), purged)).isEmpty();
        assertThat(snippetText(stored.path("payload").path("evidence_snippets"), kept)).isEqualTo(OTHER);

        var redelivered = applier.applyQuery(event);

        assertThat(redelivered.error()).isNull();
        assertThat(redelivered.response().assistantMessage().content()).isEqualTo(ANSWER);
        assertThat(receiptPayload(run)).doesNotContain(BODY);
        sessions.deleteById(session);
    }

    @Test
    void resultArrivingAfterPurgeDoesNotStoreTheEvidenceOfAMissingDocument() throws Exception {
        String missing = "doc_" + UUID.randomUUID();
        String kept = insertDocument(null);
        String run = insertRun("agent", "{}");
        projections.create(run, workspace, user, null, null, null);
        ObjectNode event = agentEvent(run, evidence(missing, BODY));
        ((com.fasterxml.jackson.databind.node.ArrayNode) event.path("payload").path("chat").path("evidence_snippets"))
                .add(evidence(kept, OTHER).get(0));

        assertThat(applier.applyAgent(event).applied()).isTrue();

        assertThat(projectionResult(run).toString()).doesNotContain(BODY).contains(OTHER).contains(ANSWER);
        assertThat(receiptPayload(run)).doesNotContain(BODY).contains(OTHER);
    }

    @Test
    void queryResultArrivingAfterPurgeDoesNotStoreTheEvidenceOfAMissingDocument() throws Exception {
        String missing = "doc_" + UUID.randomUUID();
        String kept = insertDocument(null);
        String run = insertRun("query", "{}");
        String session = startSession(run);

        assertThat(applier.applyQuery(queryEvent(run, session, missing, kept)).error()).isNull();

        assertThat(receiptPayload(run)).doesNotContain(BODY).contains(OTHER).contains(ANSWER);
        sessions.deleteById(session);
    }

    @Test
    void resultOverlappingTheDeletingTransactionWaitsAndSeesTheDocumentGone() throws Exception {
        String purged = insertDocument(now.minus(Duration.ofDays(31)));
        String run = insertRun("agent", "{}");
        projections.create(run, workspace, user, null, null, null);
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CompletableFuture<Void> deletion = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM documents WHERE id = ?", purged);
            deleted.countDown();
            await(commit);
        }));
        await(deleted);

        CompletableFuture<?> result = CompletableFuture.supplyAsync(() -> applier.applyAgent(agentEvent(run, evidenceOf(purged))));
        Thread.sleep(LOCK_WAIT_MILLIS);
        assertThat(result).isNotDone();
        commit.countDown();
        deletion.get(30, TimeUnit.SECONDS);
        result.get(30, TimeUnit.SECONDS);

        assertThat(projectionResult(run).toString()).doesNotContain(BODY).contains(ANSWER);
        assertThat(receiptPayload(run)).doesNotContain(BODY);
    }

    @Test
    void purgeSkipsADocumentAResultIsStillCitingAndCleansItOnTheNextRun() throws Exception {
        String purged = insertDocument(now.minus(Duration.ofDays(31)));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> applying = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM documents WHERE id = ? FOR KEY SHARE", String.class, purged);
            locked.countDown();
            await(release);
        }));
        await(locked);

        worker.purge(now);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE id = ?", Integer.class, purged)).isOne();
        String chat = insertProjection(chatResult(purged, purged));
        release.countDown();
        applying.get(30, TimeUnit.SECONDS);

        worker.purge(now);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE id = ?", Integer.class, purged)).isZero();
        assertThat(projectionResult(chat).toString()).doesNotContain(BODY).contains(ANSWER);
    }

    @Test
    void webEvidenceIsNotTreatedAsAMissingDocument() throws Exception {
        String run = insertRun("agent", "{}");
        projections.create(run, workspace, user, null, null, null);

        applier.applyAgent(agentEvent(run, evidence("web:https://example.com", OTHER)));

        assertThat(projectionResult(run).toString()).contains(OTHER);
    }

    @Test
    void lateFinishedRunOfAMissingDocumentIsSweptWithItsReceiptAndJournal() {
        String missing = "doc_" + UUID.randomUUID();
        String kept = insertDocument(null);
        String late = insertRun("agent", "{\"document_id\":\"" + missing + "\"}");
        String live = insertRun("agent", "{\"document_id\":\"" + kept + "\"}");
        insertReceipt(late, "agent", "{\"request\":{\"editor_snapshot\":{\"markdown\":\"" + BODY + "\"}}}");
        insertReceipt(live, "agent", "{\"request\":{\"editor_snapshot\":{\"markdown\":\"" + OTHER + "\"}}}");
        for (var pair : List.of(List.of(late, missing), List.of(live, kept))) {
            jdbc.update("INSERT INTO agent_apply_projections(run_id, workspace_id, user_id, document_id, base_version, "
                    + "apply_operation_id, status, result) VALUES (?, ?, ?, ?, 1, ?, 'failed', '{}')",
                    pair.get(0), workspace, user, pair.get(1), "apply_" + pair.get(0));
            jdbc.update("INSERT INTO ai_task_changes(run_id, table_name, row_key, after_value) "
                    + "VALUES (?, 'agent_apply_projections', jsonb_build_object('run_id', CAST(? AS text)), "
                    + "jsonb_build_object('result', CAST(? AS text)))", pair.get(0), pair.get(0), BODY);
        }

        transaction.executeWithoutResult(status -> {
            while (scrubber.scrubOrphans(1000) >= 1000) {
                continue;
            }
        });

        assertThat(receiptPayload(late)).isNull();
        assertThat(receiptPayload(live)).contains(OTHER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_changes WHERE run_id = ?", Integer.class, late)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_changes WHERE run_id = ?", Integer.class, live)).isOne();
    }

    @Test
    void evidenceLookupsUseTheirIndexes() {
        assertThat(plan("""
                SELECT run_id FROM agent_apply_projections
                WHERE result #> '{chat,evidence_snippets}' @> jsonb_build_array(jsonb_build_object('source_document_id', 'd'))
                """)).contains("idx_agent_apply_projections_evidence");
        assertThat(plan("""
                SELECT run_id FROM ai_task_result_receipts
                WHERE event_payload IS NOT NULL
                  AND COALESCE(event_payload #> '{payload,chat,evidence_snippets}', event_payload #> '{payload,evidence_snippets}')
                      @> jsonb_build_array(jsonb_build_object('source_document_id', 'd'))
                """)).contains("idx_ai_task_result_receipts_evidence");
    }

    @Test
    void lookupsThatAlsoReadSourceRefsUseTheSameEvidenceIndexes() {
        String item = "jsonb_build_object('source_document_id', 'd')";
        String refs = "jsonb_build_object('source_refs', jsonb_build_array(" + item + "))";
        String projection = "result #> '{chat,evidence_snippets}'";
        assertThat(plan("SELECT run_id FROM agent_apply_projections WHERE " + projection + " @> jsonb_build_array(" + item + ") OR "
                + projection + " @> jsonb_build_array(" + refs + ")")).contains("idx_agent_apply_projections_evidence");
        String receipt = "COALESCE(event_payload #> '{payload,chat,evidence_snippets}', event_payload #> '{payload,evidence_snippets}')";
        assertThat(plan("SELECT run_id FROM ai_task_result_receipts WHERE event_payload IS NOT NULL AND (" + receipt + " @> jsonb_build_array(" + item + ") OR "
                + receipt + " @> jsonb_build_array(" + refs + "))")).contains("idx_ai_task_result_receipts_evidence");
    }

    private String plan(String query) {
        return transaction.execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            return String.join("\n", jdbc.queryForList("EXPLAIN " + query, String.class));
        });
    }

    private String chatResult(String firstDocument, String secondDocument) {
        return """
                {"action":"chat_answer","chat":{"answer":"%s","evidence_snippets":[
                  {"rank":1,"source_document_id":"%s","text":"%s"},
                  {"rank":2,"source_document_id":"%s","text":"%s"}]}}
                """.formatted(ANSWER, firstDocument, BODY, secondDocument, OTHER);
    }

    /** 첫 근거는 대표 문서와 다른 문서에 걸치고, 둘째 근거는 대표 문서와 또 다른 문서에 걸친다. */
    private static String multiDocumentEvidence(String representative, String secondRef, String otherRef) {
        return """
                [{"rank":1,"source_document_id":"%1$s","text":"%4$s",
                  "source_refs":[{"source_document_id":"%1$s","source_block_id":"B1"},{"source_document_id":"%2$s","source_block_id":"B2"}]},
                 {"rank":2,"source_document_id":"%1$s","text":"%5$s",
                  "source_refs":[{"source_document_id":"%1$s","source_block_id":"B3"},{"source_document_id":"%3$s","source_block_id":"B4"}]}]
                """.formatted(representative, secondRef, otherRef, BODY, OTHER);
    }

    private JsonNode evidenceOf(String documentId) {
        try {
            return evidence(documentId, BODY);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode evidence(String documentId, String text) throws Exception {
        return mapper.readTree("[{\"rank\":1,\"source_document_id\":\"" + documentId + "\",\"text\":\"" + text + "\"}]");
    }

    private ObjectNode agentEvent(String run, JsonNode snippets) {
        ObjectNode root = mapper.createObjectNode();
        root.put("event_id", run + ":done");
        root.put("run_id", run);
        root.put("kind", "agent");
        root.put("status", "succeeded");
        ObjectNode request = root.putObject("request");
        request.put("workspace_id", workspace);
        request.put("user_id", user);
        ObjectNode payload = root.putObject("payload");
        payload.put("action", "chat_answer");
        ObjectNode chat = payload.putObject("chat");
        chat.put("answer", ANSWER);
        chat.set("evidence_snippets", snippets.deepCopy());
        return root;
    }

    private JsonNode queryEvent(String run, String session, String purged, String kept) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("event_id", run + ":done");
        root.put("run_id", run);
        root.put("kind", "query");
        root.put("status", "succeeded");
        ObjectNode request = root.putObject("request");
        request.put("session_id", session);
        request.put("question", "질문");
        ObjectNode context = request.putObject("message_context");
        context.put("pairId", "pair-" + run);
        context.put("userMessageId", "user-" + run);
        context.put("assistantMessageId", "assistant-" + run);
        context.put("createdAt", "2026-10-31T00:00:00Z");
        ObjectNode payload = root.putObject("payload");
        payload.put("answer", ANSWER);
        payload.set("evidence_snippets", mapper.readTree(
                "[{\"rank\":1,\"source_document_id\":\"" + purged + "\",\"text\":\"" + BODY + "\"},"
                        + "{\"rank\":2,\"source_document_id\":\"" + kept + "\",\"text\":\"" + OTHER + "\"}]"));
        return root;
    }

    private String startSession(String run) {
        String session = "session-" + run;
        sessions.save(new ChatSession(session, workspace, user, null));
        recorder.createPendingPair(session, "pair-" + run, "user-" + run, "assistant-" + run, "질문", Instant.now());
        recorder.assignRun("assistant-" + run, run);
        return session;
    }

    private static String snippetText(JsonNode snippets, String documentId) {
        for (JsonNode snippet : snippets) {
            if (documentId.equals(snippet.path("source_document_id").asText())) return snippet.path("text").asText();
        }
        throw new AssertionError("근거가 없습니다.");
    }

    private JsonNode projectionResult(String run) throws Exception {
        return mapper.readTree(jdbc.queryForObject(
                "SELECT result::text FROM agent_apply_projections WHERE run_id = ?", String.class, run));
    }

    private String receiptPayload(String run) {
        return jdbc.queryForObject("SELECT event_payload::text FROM ai_task_result_receipts WHERE run_id = ?", String.class, run);
    }

    private void insertReceipt(String run, String kind, String payload) {
        jdbc.update("INSERT INTO ai_task_result_receipts(event_id, run_id, task_kind, event_payload) "
                + "VALUES (?, ?, ?, CAST(? AS jsonb))", run + ":done", run, kind, payload);
    }

    private String insertRun(String kind, String command) {
        String id = "run_" + UUID.randomUUID();
        jdbc.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind, command) VALUES (?, ?, ?, ?, CAST(? AS jsonb))",
                id, workspace, user, kind, command);
        return id;
    }

    private String insertProjection(String result) {
        String id = insertRun("agent", "{}");
        jdbc.update("INSERT INTO agent_apply_projections(run_id, workspace_id, user_id, status, result) "
                + "VALUES (?, ?, ?, 'ready', CAST(? AS jsonb))", id, workspace, user, result);
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
