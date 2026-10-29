package fruition.core.analytics;

import com.fasterxml.jackson.databind.*;
import fruition.TestcontainersConfiguration;
import fruition.core.aihistory.domain.*;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.aihistory.service.RestoreOperationLifecycle;
import fruition.core.document.domain.Document;
import fruition.core.document.repository.DocumentRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import okhttp3.mockwebserver.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"ANALYTICS_ENABLED=true", "POSTHOG_PROJECT_TOKEN=", "app.analytics.relay-delay-ms=3600000"})
@Import(TestcontainersConfiguration.class)
class AnalyticsIntegrationTest {
    @Autowired AnalyticsRecorder recorder;
    @Autowired DocumentAnalytics analytics;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper mapper;
    @Autowired DocumentRepository documents;
    @Autowired OperationLogRepository operations;
    @Autowired RestoreOperationLifecycle restores;
    @Autowired ChatExportLinks exports;
    @Autowired fruition.core.aitask.service.AiTaskResultApplier applier;
    @Autowired fruition.core.document.service.DocumentService documentService;
    @Autowired fruition.core.meeting.MeetingNotesService meetingNotes;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired fruition.core.usage.service.UsageChargeService usageCharges;
    static final Instant AT = Instant.parse("2026-10-20T12:00:00Z");
    String suffix;
    @BeforeEach void isolate() { suffix = UUID.randomUUID().toString(); }
    String id(String prefix) { return prefix + suffix; }
    Map<String,Object> uploadProps() { return DocumentAnalytics.props("document_id",id("doc"),"file_type","pdf","size_bucket","lt_1mb","origin","user_upload"); }
    void upload(String key) { recorder.record("document_uploaded",key,"actor","target-workspace",AT,uploadProps()); }
    JsonNode event(String name,String key) throws Exception {
        return mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM analytics_outbox WHERE event_id=?",String.class,AnalyticsRecorder.eventId(name,key)));
    }
    int count(String name,String key) { return jdbc.queryForObject("SELECT count(*) FROM analytics_outbox WHERE event_id=?",Integer.class,AnalyticsRecorder.eventId(name,key)); }

    @Test void committedProductAndOutboxSurviveButRolledBackProductAndOutboxDoNot() {
        String committed=id("commit"), rolled=id("rollback");
        tx.executeWithoutResult(s->{ jdbc.update("INSERT INTO ai_task_runs(id,workspace_id,user_id,kind) VALUES (?,'w','u','query')",committed);upload(committed); });
        tx.executeWithoutResult(s->{ jdbc.update("INSERT INTO ai_task_runs(id,workspace_id,user_id,kind) VALUES (?,'w','u','query')",rolled);upload(rolled);s.setRollbackOnly(); });
        assertThat(count("document_uploaded",committed)).isEqualTo(1);
        assertThat(count("document_uploaded",rolled)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_runs WHERE id=?",Integer.class,rolled)).isZero();
    }
    @Test void postgresInsertFailureRestoresSavepointAndProductJpaCommitStillSucceeds() {
        String key=id("failure");
        // Real PostgreSQL CHECK violation aborts the transaction unless JDBC SAVEPOINT is restored.
        tx.executeWithoutResult(s->{
            jdbc.execute("ALTER TABLE analytics_outbox ADD CONSTRAINT analytics_test_reject CHECK (false) NOT VALID");
            Document d = new Document(id("product"),"w","u","fixture.pdf","application/pdf",1,"test/key",null,"upload");
            d = documents.saveAndFlush(d);
            upload(key);
            d.rename("survived.pdf");
            jdbc.execute("ALTER TABLE analytics_outbox DROP CONSTRAINT analytics_test_reject");
        });
        assertThat(count("document_uploaded",key)).isZero();
        assertThat(documents.findById(id("product"))).get().extracting(Document::getFilename).isEqualTo("survived.pdf");
    }
    @Test void duplicateKeepsOriginalTimestampIdentityAndUuid() throws Exception {
        String key=id("duplicate");
        tx.executeWithoutResult(s->{upload(key);recorder.record("document_uploaded",key,"wrong","wrong",Instant.now(),uploadProps());});
        JsonNode e=event("document_uploaded",key);
        assertThat(e.path("timestamp").asText()).isEqualTo(AT.toString());
        assertThat(e.path("distinct_id").asText()).isEqualTo("actor");
        assertThat(e.path("properties").path("$groups").path("workspace").asText()).isEqualTo("target-workspace");
        assertThat(e.path("properties").path("workspace_id").asText()).isEqualTo("target-workspace");
        assertThat(e.path("uuid").asText()).isEqualTo(e.path("properties").path("event_id").asText());
        assertThat(count("document_uploaded",key)).isEqualTo(1);
    }
    @Test void relayRetriesIdenticalBatchAndOnlySendsCommittedRows() throws Exception {
        String key=id("relay");
        tx.executeWithoutResult(s->upload(key));
        try(MockWebServer server=new MockWebServer()) {
            server.start();server.enqueue(new MockResponse().setResponseCode(503));server.enqueue(new MockResponse().setResponseCode(200));
            var relay=new AnalyticsRelay(jdbc,tx,new AnalyticsSettings(true,"phc_testPublicToken",server.url("/").toString(),"local","test"),mapper);
            // Isolate this row from other test events.
            jdbc.update("UPDATE analytics_outbox SET next_attempt_at=now()+interval '1 day' WHERE event_id<>?",AnalyticsRecorder.eventId("document_uploaded",key));
            relay.relay();
            var first=server.takeRequest(5,TimeUnit.SECONDS);
            assertThat(first).isNotNull();
            assertThat(jdbc.queryForObject("SELECT sent_at IS NULL AND attempts=1 FROM analytics_outbox WHERE event_id=?",Boolean.class,AnalyticsRecorder.eventId("document_uploaded",key))).isTrue();
            jdbc.update("UPDATE analytics_outbox SET next_attempt_at=now() WHERE event_id=?",AnalyticsRecorder.eventId("document_uploaded",key));
            relay.relay();
            var second=server.takeRequest(5,TimeUnit.SECONDS);
            assertThat(second).isNotNull();
            assertThat(second.getBody().readUtf8()).isEqualTo(first.getBody().readUtf8());
            assertThat(jdbc.queryForObject("SELECT sent_at IS NOT NULL FROM analytics_outbox WHERE event_id=?",Boolean.class,AnalyticsRecorder.eventId("document_uploaded",key))).isTrue();
        }
    }
    JsonNode query(String run,boolean web) {
        var e=mapper.createObjectNode();e.put("run_id",run);e.put("status","succeeded");
        var r=e.putObject("request");r.put("user_id","spoofed");r.put("workspace_id","spoofed");r.put("session_id",id("session"));r.putObject("message_context").put("assistantMessageId",id("assistant"));
        var p=e.putObject("payload");p.put("web_search_executed",web);
        if(web)p.putArray("evidence_snippets").addObject().put("source_document_id","web:secret-url").put("text","private body");
        return e;
    }
    void seedQuery(String run) {
        jdbc.update("INSERT INTO ai_task_runs(id,workspace_id,user_id,kind,created_at) VALUES (?,'workspace','original-actor','query',?)",run,Timestamp.from(AT));
        jdbc.update("INSERT INTO chat_sessions(id,workspace_id,user_id,created_at) VALUES (?,'workspace','original-actor',?)",id("session"),Timestamp.from(AT));
        jdbc.update("INSERT INTO chat_messages(id,session_id,pair_id,role,content,status,created_at) VALUES (?,?,?,'user','private question','completed',?)",id("user"),id("session"),id("pair"),Timestamp.from(AT));
        jdbc.update("INSERT INTO chat_messages(id,session_id,pair_id,role,content,status,created_at) VALUES (?,?,?,'assistant','private answer','completed',?)",id("assistant"),id("session"),id("pair"),Timestamp.from(AT));
    }
    @Test void queryTerminalCollisionIsDurableIndependentOfReceiptsAndPreservesOriginalIdentity() throws Exception {
        String run=id("query");seedQuery(run);
        tx.executeWithoutResult(s->{analytics.queryCompleted(query(run,true),false,null);analytics.queryCompleted(query(run,false),false,"internal_error");});
        assertThat(count("query_answered",run)).isEqualTo(1);assertThat(count("query_failed",run)).isZero();
        JsonNode e=event("query_answered",run);JsonNode p=e.path("properties");
        assertThat(e.path("distinct_id").asText()).isEqualTo("original-actor");
        assertThat(p.path("$groups").path("workspace").asText()).isEqualTo("workspace");
        assertThat(p.path("has_citations").asBoolean()).isTrue();assertThat(p.path("internal_citation_count").asInt()).isZero();
        assertThat(p.path("web_citation_count").asInt()).isEqualTo(1);assertThat(p.path("query_requested_at").asText()).isEqualTo(AT.toString());
        assertThat(p.path("turn_index").asInt()).isEqualTo(1);assertThat(e.toString()).doesNotContain("private","secret-url","spoofed");
    }
    @Test void terminalGuardRacesAcrossTransactions() throws Exception {
        String run=id("race");seedQuery(run);CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();tx.executeWithoutResult(s->analytics.queryCompleted(query(run,false),false,null));return true;});
            var b=pool.submit(()->{start.await();tx.executeWithoutResult(s->analytics.queryCompleted(query(run,false),false,"internal_error"));return true;});
            start.countDown();a.get();b.get();
        }
        assertThat(count("query_answered",run)+count("query_failed",run)).isEqualTo(1);
    }
    @Test void queryInsertFailureAlsoRollsBackGuardSoRetryCanRecord() {
        String run=id("guard-failure");seedQuery(run);
        tx.executeWithoutResult(s->{jdbc.execute("ALTER TABLE analytics_outbox ADD CONSTRAINT analytics_test_reject CHECK (false) NOT VALID");analytics.queryCompleted(query(run,false),false,null);jdbc.execute("ALTER TABLE analytics_outbox DROP CONSTRAINT analytics_test_reject");});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analytics_query_terminals WHERE query_id=?",Integer.class,run)).isZero();
        tx.executeWithoutResult(s->analytics.queryCompleted(query(run,false),false,null));
        assertThat(count("query_answered",run)).isEqualTo(1);
    }
    @Test void cancelledQueryProducesNoTerminal() {
        String run=id("cancel");seedQuery(run);
        tx.executeWithoutResult(s->analytics.queryCompleted(query(run,false),false,"cancelled"));
        assertThat(count("query_failed",run)+count("query_answered",run)).isZero();
    }
    @Test void sessionHeaderIsPersistedAcrossResultRequestAndRetry() throws Exception {
        String run=id("session-run");seedQuery(run);String session=UUID.randomUUID().toString();
        var request=new MockHttpServletRequest();request.addHeader("X-PostHog-Session-Id",session);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try{tx.executeWithoutResult(s->analytics.queryStarted(run,"original-actor","workspace",id("session"),AT,"chat"));}finally{RequestContextHolder.resetRequestAttributes();}
        tx.executeWithoutResult(s->analytics.queryCompleted(query(run,false),false,null));
        assertThat(event("query_answered",run).path("properties").path("$session_id").asText()).isEqualTo(session);
    }
    @Test void failedRestoreThenSuccessAndRepeatedSuccessHaveSeparateKeysAndCorrectActor() throws Exception {
        OperationLog original=OperationLog.completed(id("op"),"workspace","original-uploader",OperationType.ingest,null,"private summary",2,AT);
        operations.saveAndFlush(original);
        OperationLog failed=restores.startQueued(original,"restorer","{}",id("token1"),AT.plusSeconds(10)).orElseThrow();
        restores.fail(failed.getOperationId(),"provider_unavailable",AT.plusSeconds(11));
        OperationLog success=restores.startQueued(original,"restorer",mapper.writeValueAsString(Map.of("excludedOperationIds", List.of(original.getOperationId(), "cascade"))),id("token2"),AT.plusSeconds(12)).orElseThrow();
        restores.finishDocument(success.getOperationId(),1,2,AT.plusSeconds(13));
        restores.finishDocument(success.getOperationId(),1,2,AT.plusSeconds(14));
        assertThat(count("ai_operation_rolled_back","failure:"+failed.getOperationId())).isEqualTo(1);
        JsonNode e=event("ai_operation_rolled_back","success:"+original.getOperationId());
        assertThat(e.path("distinct_id").asText()).isEqualTo("restorer");assertThat(e.path("properties").path("restored_operation_count").asInt()).isEqualTo(2);
        assertThat(e.path("properties").path("cascaded_operation_ids").toString()).isEqualTo("[\"cascade\"]");
        assertThat(e.path("timestamp").asText()).isEqualTo(AT.plusSeconds(13).toString());
    }
    Document document(String prefix,String workspace,String origin) {
        return documents.saveAndFlush(new Document(id(prefix),workspace,"teammate",prefix+suffix+".pdf","application/pdf",1,"test/key",null,origin));
    }
    @Test void oldTeamFirstIngestSurvivesReingestAndCitationsDeduplicateOriginalLineage() throws Exception {
        String run=id("citations");seedQuery(run);
        Document original=document("original","workspace","upload"),child=document("child","workspace","convert"),foreign=document("foreign","other-workspace","upload");
        jdbc.update("UPDATE documents SET source_document_id=? WHERE id=?",original.getId(),child.getId());
        operations.saveAndFlush(OperationLog.completed(id("old-ingest"),"workspace","teammate",OperationType.ingest,child.getId(),"private",1,AT.minusSeconds(172800)));
        operations.saveAndFlush(OperationLog.completed(id("new-ingest"),"workspace","original-actor",OperationType.ingest,child.getId(),"private",1,AT.minusSeconds(10)));
        jdbc.update("INSERT INTO chat_message_references(chat_message_id,reference_type,document_id,rank,source_refs) VALUES (?,'source_block',?,1,?)",id("assistant"),child.getId(),"[{\"source_document_id\":\""+original.getId()+"\"},{\"source_document_id\":\""+foreign.getId()+"\"}]");
        tx.executeWithoutResult(s->analytics.queryCompleted(query(run,true),false,null));
        JsonNode p=event("query_answered",run).path("properties");
        assertThat(p.path("cited_document_ids").size()).isEqualTo(1);assertThat(p.path("cited_document_ids").get(0).asText()).isEqualTo(original.getId());
        assertThat(p.path("cited_document_first_ingested_at").path(original.getId()).asText()).isEqualTo(AT.minusSeconds(172800).toString());
        assertThat(p.path("citation_count").asInt()).isEqualTo(2);
    }
    @Test void exportLinkPersistsAndIngestCarriesFullContextForOriginalRequester() throws Exception {
        Document d=document("chatdoc","workspace","chat_export");String run=id("ingest");UUID export=UUID.randomUUID();
        OperationLog op=OperationLog.completed(id("ingest-op"),"workspace","requester",OperationType.ingest,d.getId(),"private",3,AT.plusSeconds(30));
        tx.executeWithoutResult(s->{exports.save(export,d.getId(),"requester","conversation","all");analytics.ingestRequested(d,run,op.getOperationId(),"requester",AT,"auto");analytics.ingestCompleted(query(run,false),op);});
        JsonNode requested=event("wiki_ingest_requested",run),completed=event("wiki_ingest_completed",run);
        assertThat(requested.path("distinct_id").asText()).isEqualTo("requester");
        assertThat(completed.path("properties").path("export_id").asText()).isEqualTo(export.toString());
        assertThat(completed.path("properties").path("source").asText()).isEqualTo("chat");
        assertThat(completed.path("properties").path("trigger").asText()).isEqualTo("auto");
        assertThat(completed.path("properties").path("pages_changed").asInt()).isEqualTo(3);
        assertThat(completed.path("properties").path("doc_count").asInt()).isEqualTo(1);
    }
    @Test void disabledAnalyticsAndBlankTokenDoNotSend() {
        var disabled=new AnalyticsRecorder(jdbc,mapper,new AnalyticsSettings(false,"","http://127.0.0.1:1","local","test"));
        tx.executeWithoutResult(s->disabled.record("document_uploaded",id("disabled"),"u","w",AT,uploadProps()));
        assertThat(count("document_uploaded",id("disabled"))).isZero();
        assertThat(new AnalyticsSettings(true,"","http://127.0.0.1:1","local","test").relayEnabled()).isFalse();
    }
    @Test void conversionUsesOriginalPdfAndOnlyOneFinalEvent() throws Exception {
        Document source=document("pdf-source","workspace","upload");
        Document prior=document("prior-convert","workspace","convert");
        jdbc.update("UPDATE documents SET source_document_id=? WHERE id=?",source.getId(),prior.getId());
        Document placeholder=document("convert-result","workspace","convert");
        jdbc.update("UPDATE documents SET source_document_id=? WHERE id=?",source.getId(),placeholder.getId());
        String run="convert:"+placeholder.getId();
        jdbc.update("INSERT INTO ai_task_runs(id,workspace_id,user_id,kind,created_at) VALUES (?,'workspace','converter','convert',?)",run,Timestamp.from(AT));
        tx.executeWithoutResult(t->{analytics.converted(placeholder,source.getId(),25,null);analytics.converted(placeholder,source.getId(),0,"internal_error");});
        JsonNode e=event("pdf_convert_completed",run), p=e.path("properties");
        assertThat(e.path("distinct_id").asText()).isEqualTo("converter");
        assertThat(p.path("document_id").asText()).isEqualTo(source.getId());
        assertThat(p.path("page_count").asInt()).isEqualTo(25);
        assertThat(p.path("is_reconvert").asBoolean()).isTrue();
        assertThat(p.path("status").asText()).isEqualTo("succeeded");
        assertThat(p.path("duration_sec").asDouble()).isPositive();
    }
    @Test void proposedAcceptedAndExplicitSkillHaveCompleteSafeProperties() throws Exception {
        String run=id("edit-run"),operation=id("apply-op");
        var payload=mapper.createObjectNode().put("action","markdown_edit");
        var edit=payload.putObject("edit").put("operation","replace").put("changed",true).put("replacement_markdown","private body");
        edit.putObject("actual_target").put("type","whole_document");
        jdbc.update("INSERT INTO agent_apply_projections(run_id,workspace_id,user_id,document_id,base_version,apply_operation_id,result) VALUES (?,'workspace','actor','doc',1,?,CAST(? AS jsonb))",run,operation,payload.toString());
        var e=mapper.createObjectNode().put("run_id",run);e.set("payload",payload);
        tx.executeWithoutResult(t->{analytics.proposed(e);analytics.accepted(operation,"actor","workspace","doc",AT);analytics.skillRun(run,"skill","team","creator","actor","workspace",AT);});
        assertThat(event("ai_edit_proposed",run).path("properties").path("edit_type").asText()).isEqualTo("rewrite");
        JsonNode accepted=event("ai_edit_decided",run);
        assertThat(accepted.path("properties").path("decision").asText()).isEqualTo("accepted");
        assertThat(accepted.toString()).doesNotContain("private body");
        JsonNode skill=event("skill_run",run).path("properties");
        assertThat(skill.path("scope").asText()).isEqualTo("team");assertThat(skill.path("is_creator").asBoolean()).isFalse();
    }
    @Test void uploadOriginsAndBucketsAreExplicit() throws Exception {
        Document d=document("skill-doc","workspace","skill_reference");
        tx.executeWithoutResult(t->analytics.uploaded(d));
        JsonNode p=event("document_uploaded",d.getId()).path("properties");
        assertThat(p.path("origin").asText()).isEqualTo("skill_reference");
        assertThat(p.path("size_bucket").asText()).isEqualTo("lt_1mb");
        assertThat(p.path("file_type").asText()).isEqualTo("pdf");
    }
    @Test void failureBeforeSuccessKeepsFailureTerminal() throws Exception {
        String run=id("failed-query");seedQuery(run);
        tx.executeWithoutResult(t->analytics.queryCompleted(query(run,false),true,"deadline_exceeded"));
        tx.executeWithoutResult(t->analytics.queryCompleted(query(run,true),true,null));
        assertThat(count("query_answered",run)).isZero();
        assertThat(event("query_failed",run).path("properties").path("failure_reason").asText()).isEqualTo("timeout");
        assertThat(event("query_failed",run).path("properties").path("entry_point").asText()).isEqualTo("task");
    }

    @Test void ingestSourceFollowsOriginalLineageAndDoesNotChangeOnReingest() throws Exception {
        Document upload=document("source-upload","workspace","upload");
        Document converted=document("source-converted","workspace","convert");
        jdbc.update("UPDATE documents SET source_document_id=? WHERE id=?",upload.getId(),converted.getId());
        operations.saveAndFlush(OperationLog.completed(id("prior-ingest"),"workspace","teammate",OperationType.ingest,converted.getId(),"private",1,AT));
        Document direct=document("direct-note","workspace","direct");
        tx.executeWithoutResult(t->{
            analytics.ingestRequested(converted,id("reingest"),id("op-again"),"actor",AT.plusSeconds(60),"manual");
            analytics.ingestRequested(direct,id("first-note"),id("op-note"),"actor",AT.plusSeconds(60),"auto");
        });
        JsonNode reingest=event("wiki_ingest_requested",id("reingest")).path("properties");
        assertThat(reingest.path("source").asText()).isEqualTo("upload");
        assertThat(reingest.path("trigger").asText()).isEqualTo("manual");
        assertThat(reingest.path("document_id").asText()).isEqualTo(upload.getId());
        assertThat(event("wiki_ingest_requested",id("first-note")).path("properties").path("source").asText()).isEqualTo("existing_document");
    }

    // Exercise the real result application and JPA save path, without flushing before the JDBC assertions.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void resultApplicationSeesJpaCitationsBeforeCommit(boolean agent) throws Exception {
        String run = id("apply-citations");
        var input = applicationEvent(run, agent);
        Document original = document("apply-original", "workspace", "upload");
        Document child = document("apply-child", "workspace", "convert");
        Document additional = document("apply-additional", "workspace", "upload");
        Document foreign = document("apply-foreign", "other-workspace", "upload");
        Document deleted = document("apply-deleted", "workspace", "upload");
        jdbc.update("UPDATE documents SET source_document_id=? WHERE id=?", original.getId(), child.getId());
        jdbc.update("UPDATE documents SET deleted_at=now() WHERE id=?", deleted.getId());
        operations.saveAndFlush(OperationLog.completed(id("apply-old-ingest"), "workspace", "teammate",
                OperationType.ingest, child.getId(), "private summary", 1, AT.minusSeconds(172800)));
        operations.saveAndFlush(OperationLog.completed(id("apply-reingest"), "workspace", "original-actor",
                OperationType.ingest, child.getId(), "private summary", 1, AT.minusSeconds(10)));
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode)
                (agent ? input.path("payload").path("chat") : input.path("payload"));
        var snippets = payload.putArray("evidence_snippets");
        var snippet = snippets.addObject().put("rank", 0).put("source_document_id", child.getId()).put("text", "private quote");
        var refs = snippet.putArray("source_refs");
        for (String source : List.of(original.getId(), additional.getId(), foreign.getId(), deleted.getId(), id("missing")))
            refs.addObject().put("source_document_id", source).put("source_block_id", "private-block");
        snippets.addObject().put("rank", 1).put("source_document_id", original.getId()).put("text", "private second quote");
        for (String source : List.of(foreign.getId(), deleted.getId(), id("missing")))
            snippets.addObject().put("source_document_id", source).put("text", "private rejected quote");
        snippets.addObject().put("source_document_id", additional.getId()).put("text", "  ");
        snippets.addObject().put("source_document_id", "web:private-url").put("text", "private web quote");
        snippets.addObject().put("source_document_id", "web:empty-url").put("text", "  ");
        tx.executeWithoutResult(status -> {
            apply(input, agent);
            // IDENTITY references are SQL-visible already: the failure was the message-context field name.
            assertThat(referenceCount()).isEqualTo(2);
            JsonNode envelope = readAnswer(run), properties = envelope.path("properties");
            assertThat(properties.path("has_citations").asBoolean()).isTrue();
            assertThat(properties.path("internal_citation_count").asInt()).isEqualTo(2);
            assertThat(properties.path("web_citation_count").asInt()).isEqualTo(1);
            assertThat(properties.path("citation_count").asInt()).isEqualTo(3);
            assertThat(mapper.convertValue(properties.path("cited_document_ids"), List.class))
                    .containsExactlyElementsOf(new TreeSet<>(List.of(original.getId(), additional.getId())));
            assertThat(properties.path("cited_document_first_ingested_at").size()).isEqualTo(1);
            assertThat(properties.path("cited_document_first_ingested_at").path(original.getId()).asText())
                    .isEqualTo(AT.minusSeconds(172800).toString());
            assertThat(properties.path("entry_point").asText()).isEqualTo(agent ? "task" : "chat");
            assertThat(envelope.path("distinct_id").asText()).isEqualTo("original-actor");
            assertThat(properties.path("$groups").path("workspace").asText()).isEqualTo("workspace");
            assertThat(envelope.toString()).doesNotContain("private", foreign.getId(), deleted.getId(), child.getId());
            apply(input, agent);
            assertThat(readAnswer(run)).isEqualTo(envelope);
            assertThat(referenceCount()).isEqualTo(2);
            analytics.queryCompleted(input, agent, "internal_error");
            assertThat(count("query_failed", run)).isZero();
        });
        assertThat(count("query_answered", run)).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void resultApplicationRollbackRemovesReferencesReceiptTerminalAndOutbox(boolean agent) {
        String run = id("apply-rollback");
        var input = applicationWithOneCitation(run, agent);
        tx.executeWithoutResult(status -> {
            apply(input, agent);
            assertThat(referenceCount()).isEqualTo(1);
            assertThat(readAnswer(run).path("properties").path("internal_citation_count").asInt()).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(referenceCount()).isZero();
        assertThat(count("query_answered", run)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analytics_query_terminals WHERE query_id=?", Integer.class, run)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_result_receipts WHERE run_id=?", Integer.class, run)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM chat_messages WHERE id=?", String.class, id("assistant"))).isEqualTo("pending");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void resultApplicationCommitsJpaProductWhenAnalyticsInsertFails(boolean agent) {
        String run = id("apply-fail-open");
        var input = applicationWithOneCitation(run, agent);
        tx.executeWithoutResult(status -> {
            jdbc.execute("ALTER TABLE analytics_outbox ADD CONSTRAINT analytics_test_reject CHECK (false) NOT VALID");
            apply(input, agent);
            assertThat(referenceCount()).isEqualTo(1);
            assertThat(count("query_answered", run)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM analytics_query_terminals WHERE query_id=?", Integer.class, run)).isZero();
            jdbc.execute("ALTER TABLE analytics_outbox DROP CONSTRAINT analytics_test_reject");
        });
        assertThat(referenceCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content FROM chat_messages WHERE id=?", String.class, id("assistant")))
                .isEqualTo("private answer");
        assertThat(jdbc.queryForObject("SELECT status FROM chat_messages WHERE id=?", String.class, id("assistant")))
                .isEqualTo("completed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_result_receipts WHERE run_id=?", Integer.class, run)).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void relatedPagesAloneAreNotCitationsInResultApplication(boolean agent) {
        String run = id("apply-no-citation");
        var input = applicationEvent(run, agent);
        tx.executeWithoutResult(status -> {
            apply(input, agent);
            var properties = readAnswer(run).path("properties");
            assertThat(properties.path("has_citations").asBoolean()).isFalse();
            assertThat(properties.path("citation_count").asInt()).isZero();
            assertThat(properties.path("cited_document_ids").size()).isZero();
        });
    }

    private com.fasterxml.jackson.databind.node.ObjectNode applicationWithOneCitation(String run, boolean agent) {
        var input = applicationEvent(run, agent);
        var document = document("apply-one", "workspace", "upload");
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode)
                (agent ? input.path("payload").path("chat") : input.path("payload"));
        payload.putArray("evidence_snippets").addObject()
                .put("source_document_id", document.getId()).put("text", "private quote");
        return input;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode applicationEvent(String run, boolean agent) {
        seedQuery(run);
        jdbc.update("UPDATE chat_messages SET content='', status='pending' WHERE id=?", id("assistant"));
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) query(run, false);
        input.put("event_id", id("result")).put("kind", agent ? "agent" : "query");
        Object command;
        if (agent) {
            jdbc.update("UPDATE ai_task_runs SET kind='agent' WHERE id=?", run);
            jdbc.update("INSERT INTO agent_apply_projections(run_id,workspace_id,user_id) VALUES (?,'workspace','original-actor')", run);
            command = fruition.core.agent.service.AnalyticsAgentCommandFixture.command(
                    run, id("session"), id("pair"), id("user"), id("assistant"));
        } else {
            command = fruition.core.query.service.AnalyticsQueryCommandFixture.command(run, id("session"),
                    new fruition.core.query.service.QueryService.QueryMessageContext(id("pair"), id("user"), id("assistant"), AT));
        }
        // Serialize the full production command, including its actual nested context DTO.
        input.set("request", mapper.valueToTree(command));
        var payload = input.putObject("payload");
        if (agent) payload = payload.put("action", "chat_answer").putObject("chat");
        payload.put("answer", "private answer");
        payload.putArray("related_pages").addObject().put("id", "wiki-page")
                .put("title", "private title").put("page_type", "Concept").put("slug", "private-slug");
        payload.putArray("evidence_snippets");
        return input;
    }

    void seedAgent(String run, String document) {
        jdbc.update("INSERT INTO ai_task_runs(id,workspace_id,user_id,kind,created_at) VALUES (?,'workspace','actor','agent',?)",run,Timestamp.from(AT));
        jdbc.update("INSERT INTO agent_apply_projections(run_id,workspace_id,user_id,document_id,base_version,apply_operation_id) VALUES (?,'workspace','actor',?,?,?)",
                run,document,document == null ? null : 1L,document == null ? null : id("apply-op"));
    }
    com.fasterxml.jackson.databind.node.ObjectNode agentResult(String run, String status) {
        return mapper.createObjectNode().put("event_id",id("result")+run).put("run_id",run).put("status",status);
    }
    @Test void agentFailuresRecordOneTaskFailedWithStageAndSafeAction() throws Exception {
        String failed=id("agent-failed"),invalid=id("agent-invalid"),unsupported=id("agent-unsupported"),cancelled=id("agent-cancelled");
        for (String run : List.of(failed,invalid,unsupported,cancelled)) seedAgent(run,null);
        var invalidResult=agentResult(invalid,"succeeded");invalidResult.putObject("request");invalidResult.putObject("payload").put("action","markdown_create");
        var unsupportedResult=agentResult(unsupported,"succeeded");unsupportedResult.putObject("request");unsupportedResult.putObject("payload").put("action","private-action");
        tx.executeWithoutResult(t->{
            applier.applyAgent(agentResult(failed,"failed").put("error","provider_unavailable"));
            applier.applyAgent(agentResult(failed,"failed").put("error","provider_unavailable"));
            applier.applyAgent(invalidResult);
            applier.applyAgent(unsupportedResult);
            applier.applyAgent(agentResult(cancelled,"cancelled"));
        });
        JsonNode result=event("ai_task_failed","agent_turn:"+failed).path("properties");
        assertThat(count("ai_task_failed","agent_turn:"+failed)).isEqualTo(1);
        assertThat(result.path("task_kind").asText()).isEqualTo("agent_turn");
        assertThat(result.path("stage").asText()).isEqualTo("result");
        assertThat(result.path("failure_reason").asText()).isEqualTo("provider_error");
        assertThat(result.has("action")).isFalse();
        JsonNode validation=event("ai_task_failed","agent_turn:"+invalid).path("properties");
        assertThat(validation.path("stage").asText()).isEqualTo("validation");
        assertThat(validation.path("failure_reason").asText()).isEqualTo("validation");
        assertThat(validation.path("action").asText()).isEqualTo("markdown_create");
        assertThat(event("ai_task_failed","agent_turn:"+unsupported).path("properties").path("action").asText()).isEqualTo("unknown");
        assertThat(count("ai_task_failed","agent_turn:"+cancelled)).isZero();
    }
    @Test void applyConflictRecordsEditApplyFailureForProposal() throws Exception {
        String run=id("conflict-run");
        seedAgent(run,"doc");
        tx.executeWithoutResult(t->analytics.applyConflicted(id("apply-op")));
        JsonNode p=event("ai_task_failed","edit_apply:"+run).path("properties");
        assertThat(p.path("task_id").asText()).isEqualTo(run);
        assertThat(p.path("failure_reason").asText()).isEqualTo("conflict");
        assertThat(p.path("document_id").asText()).isEqualTo("doc");
    }
    @Test void agentDraftCreatesAiGeneratedDocumentOnlyForOwnMarkdownCreateRun() throws Exception {
        String workspace=id("ws"),actor=id("actor"),own=id("draft-own"),edit=id("draft-edit");
        redis.opsForValue().set("authz:role:v2:"+workspace+":"+actor,"{\"role\":\"MEMBER\",\"permissions\":[]}");
        for (String[] run : new String[][]{{own,"markdown_create"},{edit,"markdown_edit"}})
            jdbc.update("INSERT INTO agent_apply_projections(run_id,workspace_id,user_id,status,result) VALUES (?,?,?,'ready',CAST(? AS jsonb))",
                    run[0],workspace,actor,"{\"action\":\""+run[1]+"\"}");
        String created=documentService.createMarkdown(workspace,actor,UUID.randomUUID().toString(),
                new fruition.core.document.dto.MarkdownDocumentCreateRequest("초안","# private body",null,null,own)).id();
        String plain=documentService.createMarkdown(workspace,actor,UUID.randomUUID().toString(),
                new fruition.core.document.dto.MarkdownDocumentCreateRequest("편집 초안","# private body",null,null,edit)).id();
        assertThat(event("document_uploaded",created).path("properties").path("origin").asText()).isEqualTo("ai_generated");
        assertThat(event("ai_document_created",created).path("properties").path("proposal_id").asText()).isEqualTo(own);
        assertThat(event("document_uploaded",plain).path("properties").path("origin").asText()).isEqualTo("user_upload");
        assertThat(count("ai_document_created",plain)).isZero();
    }
    @Test void meetingNotesRecordReadyFailureTimeoutButNotCancelOrLateResult() throws Exception {
        String meeting=id("mtg"),ready=id("notes-ready"),failed=id("notes-failed"),late=id("notes-late");
        jdbc.update("INSERT INTO meetings(id,workspace_id,created_by,display_name,source,status,created_at,updated_at) VALUES (?,'workspace','actor','회의','upload','open',?,?)",
                meeting,Timestamp.from(AT),Timestamp.from(AT));
        int version=1;
        for (String run : List.of(ready,failed,late)) {
            jdbc.update("INSERT INTO ai_task_runs(id,workspace_id,user_id,kind,created_at) VALUES (?,'workspace','actor','meeting_notes',?)",run,Timestamp.from(AT));
            jdbc.update("INSERT INTO meeting_notes(meeting_id,version,generation_request_id,status,partial,segment_snapshot,ai_run_id,created_at,updated_at) VALUES (?,?,?,'generating',false,'[]',?,now(),now())",
                    meeting,version++,run,run);
        }
        var draft=mapper.createObjectNode();draft.putArray("summary");
        tx.executeWithoutResult(t->{
            meetingNotes.applyResult(ready,true,draft,null);
            meetingNotes.applyResult(failed,false,null,"MEETING_NOTES_UNAVAILABLE");
            jdbc.update("UPDATE meeting_notes SET status='failed', error_code='MEETING_NOTES_CANCELLED' WHERE ai_run_id=?",late);
            meetingNotes.applyResult(late,true,draft,null);
        });
        assertThat(event("meeting_notes_completed",ready).path("properties").path("status").asText()).isEqualTo("succeeded");
        JsonNode failure=event("meeting_notes_completed",failed).path("properties");
        assertThat(failure.path("status").asText()).isEqualTo("failed");
        assertThat(failure.path("failure_reason").asText()).isEqualTo("provider_error");
        assertThat(failure.path("meeting_id").asText()).isEqualTo(meeting);
        assertThat(count("meeting_notes_completed",late)).isZero();
    }
    @Test void syncCallFailureOutsideTransactionRecordsOnceAndUserStopDoesNot() throws Exception {
        String failed=id("speech_transcription:"),stopped=id("speech_synthesis:");
        for (String[] run : new String[][]{{failed,"speech_transcription"},{stopped,"speech_synthesis"}})
            jdbc.update("INSERT INTO ai_task_runs(id,workspace_id,user_id,kind,status,created_at) VALUES (?,'workspace','actor',?,'running',?)",run[0],run[1],Timestamp.from(AT));
        usageCharges.failRun(failed,"provider_unavailable");
        usageCharges.failRun(failed,"internal_error");
        usageCharges.failRun(stopped,null);
        JsonNode p=event("ai_task_failed","sync_call:"+failed).path("properties");
        assertThat(count("ai_task_failed","sync_call:"+failed)).isEqualTo(1);
        assertThat(p.path("action").asText()).isEqualTo("speech_transcription");
        assertThat(p.path("failure_reason").asText()).isEqualTo("provider_error");
        assertThat(count("ai_task_failed","sync_call:"+stopped)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task_runs WHERE id=?",String.class,stopped)).isEqualTo("failed");
        assertThat(fruition.core.usage.service.UsageChargeService.failureCode(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT))).isEqualTo("deadline_exceeded");
        assertThat(fruition.core.usage.service.UsageChargeService.failureCode(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY))).isEqualTo("provider_unavailable");
        assertThat(fruition.core.usage.service.UsageChargeService.failureCode(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY))).isEqualTo("input_rejected");
        assertThat(fruition.core.usage.service.UsageChargeService.failureCode(new IllegalStateException("private"))).isEqualTo("internal_error");
    }
    @Test void newEventSchemasRejectMissingForbiddenAndInvalidValues() {
        var valid=DocumentAnalytics.props("task_id","t","task_kind","agent_turn","stage","result","failure_reason","conflict","duration_sec",1.0);
        AnalyticsSchema.validate("ai_task_failed",valid);
        var missing=new LinkedHashMap<>(valid);missing.remove("stage");
        var forbidden=new LinkedHashMap<>(valid);forbidden.put("error_message","private");
        var invalid=new LinkedHashMap<>(valid);invalid.put("action","private-action");
        for (var props : List.of(missing,forbidden,invalid))
            assertThatThrownBy(()->AnalyticsSchema.validate("ai_task_failed",props)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AnalyticsSchema.validate("meeting_notes_completed",DocumentAnalytics.props("meeting_id","m","status","failed","duration_sec",1.0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void apply(JsonNode input, boolean agent) {
        if (agent) assertThat(applier.applyAgent(input).error()).isNull();
        else assertThat(applier.applyQuery(input).error()).isNull();
    }
    private int referenceCount() {
        return jdbc.queryForObject("SELECT count(*) FROM chat_message_references WHERE chat_message_id=?", Integer.class, id("assistant"));
    }
    private JsonNode readAnswer(String run) {
        try { return event("query_answered", run); }
        catch (Exception error) { throw new AssertionError(error); }
    }

}
