package fruition.core.analytics;

import com.fasterxml.jackson.databind.*;
import fruition.core.document.domain.Document;
import fruition.core.aihistory.domain.OperationLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Product hooks pass canonical identities; all derived analytics reads/writes share one savepoint. */
@Component
public class DocumentAnalytics {
    @org.springframework.beans.factory.annotation.Autowired
    private AnalyticsSkillMetadataClient skillMetadata;

    private final AnalyticsRecorder recorder;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public DocumentAnalytics(AnalyticsRecorder recorder, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.recorder = recorder; this.jdbc = jdbc; this.mapper = mapper;
    }
    public void uploaded(Document d) {
        String origin = switch(d.getOrigin()) {
            case "chat_export" -> "chat_export";
            case "skill_reference" -> "skill_reference";
            default -> d.getAiOrigin() == null ? "user_upload" : "ai_generated";
        };
        long bytes = d.getByteSize();
        String bucket = bytes < 1048576 ? "lt_1mb" : bytes < 10485760 ? "1mb_to_lt_10mb" : bytes < 52428800 ? "10mb_to_lt_50mb" : "gte_50mb";
        String type = "application/pdf".equals(d.getMimeType()) ? "pdf" : "text/markdown".equals(d.getMimeType()) ? "markdown" : "other";
        recorder.record("document_uploaded", d.getId(), d.getUserId(), d.getWorkspaceId(), d.getUploadedAt(),
                props("document_id", d.getId(), "file_type", type, "size_bucket", bucket, "origin", origin));
    }
    public void queryStarted(String run, String actor, String workspace, String conversation, Instant at, String entry) {
        recorder.safely(() -> {
            var context = props("conversation_id", conversation, "entry_point", entry);
            var attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servlet) {
                String session = servlet.getRequest().getHeader("X-PostHog-Session-Id");
                // Session IDs are UUIDs, not arbitrary browser supplied strings.
                if (session != null) try { context.put("$session_id", UUID.fromString(session).toString()); } catch (IllegalArgumentException ignored) {}
            }
            saveContext(run, actor, workspace, at, context);
        });
    }
    private void saveContext(String run, String actor, String workspace, Instant at, Map<String,Object> context) {
        jdbc.update("INSERT INTO analytics_run_context(run_id,actor_id,workspace_id,requested_at,context) VALUES (?,?,?,?,CAST(? AS jsonb)) ON CONFLICT DO NOTHING",
                run, actor, workspace, Timestamp.from(at), json(context));
    }
    public void ingestRequested(Document d, String run, String operation, String actor, Instant at, String trigger) {
        recorder.metadata(() -> {
            var p = props("ingest_id", run, "document_id", original(d.getId(), d.getWorkspaceId()), "operation_id", operation,
                    "trigger", trigger, "source", source(d));
            var exports = jdbc.queryForList("SELECT export_id::text FROM chat_export_links WHERE document_id=? ORDER BY CASE WHEN user_id=? THEN 0 ELSE 1 END, CASE WHEN user_id=? THEN created_at END DESC, created_at ASC, export_id LIMIT 1", String.class, (String)p.get("document_id"), actor, actor);
            if (!exports.isEmpty()) p.put("export_id", exports.getFirst());
            else if ("chat".equals(p.get("source")))
                p.put("export_id", AnalyticsRecorder.eventId("chat_exported", "legacy:" + p.get("document_id")).toString());
            saveContext(run, actor, d.getWorkspaceId(), at, p);
            recorder.record("wiki_ingest_requested", run, actor, d.getWorkspaceId(), at, p);
        });
    }
    public void ingestCompleted(JsonNode event, OperationLog operation) {
        if (operation == null || "cancelled".equals(event.path("error").asText())) return;
        recorder.safely(() -> {
            String run = event.path("run_id").asText();
            Context context = context(run);
            // Existing in-flight commands may predate analytics; their operation identity is authoritative.
            if (context == null) {
                // A pre-migration in-flight run has no trustworthy auto/manual snapshot.
                // Its success still participates in canonical first-ingest history.
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("analytics ingest skipped: missing request provenance");
                return;
            }
            var p = asMap(context.data());
            if ("chat".equals(p.get("source")) && !p.containsKey("export_id"))
                p.put("export_id", AnalyticsRecorder.eventId("chat_exported", "legacy:" + operation.getTargetDocumentId()).toString());
            boolean success = operation.getStatus() == fruition.core.aihistory.domain.OperationStatus.succeeded;
            p.put("status", success ? "succeeded" : "failed"); p.put("doc_count", 1);
            p.put("pages_changed", operation.getChangedResourceCount());
            Instant at = operation.getCompletedAt() == null ? Instant.now() : operation.getCompletedAt();
            p.put("duration_sec", seconds(operation.getCreatedAt(), at));
            p.put("operation_id", operation.getOperationId());
            if (!success) p.put("failure_reason", AnalyticsSchema.failureReason(event.path("error").asText()));
            // Current operation may not be JPA-flushed yet; include its actual completion in the minimum.
            Instant first = firstIngest((String)p.get("document_id"), operation.getWorkspaceId());
            if (success && (first == null || at.isBefore(first))) first = at;
            if (first != null) p.put("first_success_ingested_at", first.toString());
            recorder.insert("wiki_ingest_completed", run, operation.getUserId(), operation.getWorkspaceId(), at, p);
        });
    }
    public void converted(Document placeholder, String sourceId, int pages, String failure) {
        if ("cancelled".equals(failure)) return;
        recorder.safely(() -> {
            String run = "convert:" + placeholder.getId();
            var row = jdbc.queryForMap("SELECT user_id, workspace_id, created_at FROM ai_task_runs WHERE id=?", run);
            Instant at = Instant.now();
            boolean reconvert = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM documents WHERE source_document_id=? AND origin='convert' AND id<>? AND uploaded_at<=?)", Boolean.class,
                    sourceId, placeholder.getId(), Timestamp.from(placeholder.getUploadedAt())));
            var p = props("conversion_id",run,"document_id",sourceId,"status",failure == null ? "succeeded" : "failed", "is_reconvert",reconvert,
                    "duration_sec",seconds(instant(row.get("created_at")), at));
            if (failure == null) p.put("page_count",pages); else p.put("failure_reason",AnalyticsSchema.failureReason(failure));
            recorder.insert("pdf_convert_completed",run,(String)row.get("user_id"),(String)row.get("workspace_id"),at,p);
        });
    }
    public void queryCompleted(JsonNode event, boolean agent, String error) {
        if ("cancelled".equals(error) || "cancelled".equals(event.path("status").asText())) return;
        recorder.safely(() -> {
            String run = event.path("run_id").asText();
            Context ctx = context(run);
            var request = event.path("request");
            var identity = jdbc.queryForMap("SELECT user_id,workspace_id,created_at FROM ai_task_runs WHERE id=?",run);
            String actor = (String)identity.get("user_id"), workspace = (String)identity.get("workspace_id");
            String conversation = ctx == null ? request.path("session_id").asText() : ctx.data().path("conversation_id").asText();
            Instant requested = ctx == null ? instant(identity.get("created_at")) : ctx.at();
            Instant at = Instant.now();
            String eventName = error == null ? "query_answered" : "query_failed";
            if (jdbc.update("INSERT INTO analytics_query_terminals(query_id,event_name,occurred_at) VALUES (?,?,?) ON CONFLICT DO NOTHING",run,eventName,Timestamp.from(at)) == 0) return;
            // QueryMessageContext uses camelCase; AgentMessageContext explicitly uses snake_case.
            // Reading the agent key for a query selects no references even after JPA inserted them.
            String messageId = request.path("message_context")
                    .path(agent ? "assistant_message_id" : "assistantMessageId").asText();
            Integer turn = jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE session_id=? AND role='user' AND created_at<=?",Integer.class,conversation,Timestamp.from(requested));
            var p = props("query_id",run,"conversation_id",conversation,"turn_index",Math.max(1,turn == null ? 1 : turn),
                    "entry_point",agent ? "task" : "chat", "query_requested_at",requested.toString(),"duration_sec",seconds(requested,at));
            if (ctx != null && ctx.data().has("$session_id")) p.put("$session_id",ctx.data().path("$session_id").asText());
            if (error != null) p.put("failure_reason",AnalyticsSchema.failureReason(error));
            else {
                // References are already filtered by ChatEvidenceRecorder. Resolve all source_refs too,
                // checking workspace/deletion and mapping converted children to original source IDs.
                var references = jdbc.queryForList("SELECT document_id AS source_document_id, source_refs FROM chat_message_references WHERE chat_message_id=?",messageId);
                var ids = new TreeSet<String>();
                for (var reference : references) {
                    addOriginal(ids,(String)reference.get("source_document_id"),workspace);
                    for (var ref : tree((String)reference.get("source_refs"))) addOriginal(ids,ref.path("source_document_id").asText(null),workspace);
                }
                JsonNode payload = agent ? event.path("payload").path("chat") : event.path("payload");
                int web = 0;
                for (var snippet : payload.path("evidence_snippets"))
                    if (snippet.path("source_document_id").asText().startsWith("web:") && !snippet.path("text").asText().isBlank()) web++;
                int internal = references.size();
                p.put("internal_citation_count",internal); p.put("web_citation_count",web);
                p.put("citation_count",internal+web); p.put("has_citations",internal+web>0);
                p.put("cited_document_ids",ids); p.put("web_search_used",payload.path("web_search_executed").asBoolean());
                var history = new TreeMap<String,String>();
                for (String id : ids) { Instant first = firstIngest(id,workspace); if (first != null) history.put(id,first.toString()); }
                p.put("cited_document_first_ingested_at",history);
            }
            recorder.insert(eventName,run,actor,workspace,at,p);
        });
    }
    public void proposed(JsonNode event) {
        var payload = event.path("payload");
        if (!"markdown_edit".equals(payload.path("action").asText()) || !payload.path("edit").path("changed").asBoolean()) return;
        recorder.safely(() -> {
            String run = event.path("run_id").asText();
            var row = jdbc.queryForMap("SELECT user_id,workspace_id,document_id FROM agent_apply_projections WHERE run_id=?",run);
            recorder.insert("ai_edit_proposed",run,(String)row.get("user_id"),(String)row.get("workspace_id"),Instant.now(),
                    props("proposal_id",run,"document_id",row.get("document_id"),"edit_type",editType(payload.path("edit"))));
        });
    }
    public void accepted(String operation, String actor, String workspace, String document, Instant at) {
        recorder.safely(() -> {
            var rows = jdbc.queryForList("SELECT run_id,result::text FROM agent_apply_projections WHERE apply_operation_id=?",operation);
            if (rows.isEmpty()) return;
            var row = rows.getFirst(); String proposal = (String)row.get("run_id");
            recorder.insert("ai_edit_decided",proposal,actor,workspace,at,props("proposal_id",proposal,"document_id",document,
                    "edit_type",editType(tree((String)row.get("result")).path("edit")),"decision","accepted","operation_id",operation));
        });
    }
    /** Terminal AI task failure. Query failures use query_failed; cancellation is not a failure. */
    public void taskFailed(String task, String kind, String stage, String error, String action) {
        if ("cancelled".equals(error)) return;
        recorder.safely(() -> insertTaskFailed(task, kind, stage, error, action));
    }
    public void applyConflicted(String applyOperation) {
        recorder.safely(() -> {
            var runs = jdbc.queryForList("SELECT run_id FROM agent_apply_projections WHERE apply_operation_id=?",String.class,applyOperation);
            if (!runs.isEmpty()) insertTaskFailed(runs.getFirst(),"edit_apply","apply","conflict",null);
        });
    }
    private void insertTaskFailed(String task, String kind, String stage, String error, String action) {
        var row = jdbc.queryForMap("SELECT r.user_id,r.workspace_id,r.created_at,r.kind,p.document_id FROM ai_task_runs r LEFT JOIN agent_apply_projections p ON p.run_id=r.id WHERE r.id=?",task);
        Instant at = Instant.now();
        var p = props("task_id",task,"task_kind",kind,"stage",stage,"failure_reason",AnalyticsSchema.failureReason(error),
                "duration_sec",seconds(instant(row.get("created_at")),at));
        if (action == null && "sync_call".equals(kind)) action = (String)row.get("kind");
        if (action != null && !action.isBlank()) p.put("action",AnalyticsSchema.AGENT_ACTIONS.contains(action) ? action : "unknown");
        if (row.get("document_id") != null) p.put("document_id",row.get("document_id"));
        recorder.insert("ai_task_failed",kind+":"+task,(String)row.get("user_id"),(String)row.get("workspace_id"),at,p);
    }
    public void aiDocumentCreated(String proposal, Document d) {
        recorder.record("ai_document_created",d.getId(),d.getUserId(),d.getWorkspaceId(),d.getUploadedAt(),
                props("proposal_id",proposal,"document_id",d.getId()));
    }
    /** error null means the draft is ready; the run key keeps one terminal event even if a late result follows a timeout. */
    public void meetingNotesCompleted(String run, String error) {
        if ("MEETING_NOTES_CANCELLED".equals(error)) return;
        recorder.safely(() -> {
            var row = jdbc.queryForMap("SELECT n.meeting_id,r.user_id,r.workspace_id,r.created_at FROM meeting_notes n JOIN ai_task_runs r ON r.id=n.ai_run_id WHERE n.ai_run_id=?",run);
            Instant at = Instant.now();
            var p = props("meeting_id",row.get("meeting_id"),"status",error == null ? "succeeded" : "failed",
                    "duration_sec",seconds(instant(row.get("created_at")),at));
            if (error != null) p.put("failure_reason",AnalyticsSchema.failureReason(error));
            recorder.insert("meeting_notes_completed",run,(String)row.get("user_id"),(String)row.get("workspace_id"),at,p);
        });
    }
    public void restored(OperationLog restore, String failure) {
        recorder.safely(() -> {
            var original = jdbc.queryForMap("SELECT operation_type,completed_at FROM ai_operation_logs WHERE operation_id=?",restore.getRestoredFrom());
            boolean success = restore.getStatus() == fruition.core.aihistory.domain.OperationStatus.succeeded;
            var cascaded = new TreeSet<String>();
            for (var id : tree(restore.getRestoreManifest()).path("excludedOperationIds")) if (!id.asText().equals(restore.getRestoredFrom())) cascaded.add(id.asText());
            var p = props("operation_id",restore.getRestoredFrom(),"restore_attempt_id",restore.getOperationId(),
                    "operation_type",original.get("operation_type"),"status",success ? "succeeded":"failed",
                    "restored_operation_count",success ? cascaded.size()+1 : 0,"cascaded_operation_ids",success ? cascaded : Set.of(),
                    "restore_requested_at",restore.getCreatedAt().toString(),"time_since_operation_sec",seconds(instant(original.get("completed_at")),restore.getCreatedAt()));
            if (restore.getTargetDocumentId()!=null) p.put("document_id",restore.getTargetDocumentId());
            if (!success) p.put("failure_reason",AnalyticsSchema.failureReason(failure));
            recorder.insert("ai_operation_rolled_back",success ? "success:"+restore.getRestoredFrom() : "failure:"+restore.getOperationId(),
                    restore.getUserId(),restore.getWorkspaceId(),restore.getCompletedAt()==null ? Instant.now() : restore.getCompletedAt(),p);
        });
    }
    public void explicitSkillRun(String run, String skill, String actor, String workspace, Instant at) {
        if (skillMetadata == null) return;
        skillMetadata.find(workspace, actor, skill).ifPresent(metadata ->
                skillRun(run, skill, metadata.scope(), metadata.creator(), actor, workspace, at));
    }
    public void skillRun(String run,String skill,String scope,String creator,String actor,String workspace,Instant at) {
        recorder.record("skill_run",run,actor,workspace,at,props("skill_id",skill,"skill_run_id",run,"scope",scope,"is_creator",actor.equals(creator)));
    }
    public void exported(String exportId,String document,String conversation,int pairs,String mode,String status,String actor,String workspace) {
        recorder.record("chat_exported",exportId,actor,workspace,Instant.now(),props("export_id",exportId,"document_id",document,"conversation_id",conversation,"pair_count",pairs,"mode",mode,"status",status));
    }
    private void addOriginal(Set<String> ids,String id,String workspace) {
        if (id == null || id.startsWith("web:")) return;
        var rows = jdbc.queryForList("SELECT o.original_id FROM analytics_document_origins o JOIN documents d ON d.id=o.document_id JOIN documents original ON original.id=o.original_id WHERE o.document_id=? AND o.workspace_id=? AND d.deleted_at IS NULL AND original.deleted_at IS NULL",String.class,id,workspace);
        ids.addAll(rows);
    }
    private String original(String id,String workspace) {
        var rows=jdbc.queryForList("SELECT original_id FROM analytics_document_origins WHERE document_id=? AND workspace_id=?",String.class,id,workspace);
        return rows.isEmpty()?id:rows.getFirst();
    }
    private String source(Document document) {
        String original = original(document.getId(), document.getWorkspaceId());
        var rows = jdbc.queryForList("SELECT origin FROM documents WHERE id=?", String.class, original);
        String origin = rows.isEmpty() ? document.getOrigin() : rows.getFirst();
        return switch (origin) {
            case "chat_export" -> "chat";
            case "upload", "convert", "convert_part" -> "upload";
            default -> "existing_document";
        };
    }
    private Instant firstIngest(String id,String workspace) {
        var rows=jdbc.queryForList("SELECT first_ingested_at FROM analytics_first_ingests WHERE document_id=? AND workspace_id=?",id,workspace);
        return rows.isEmpty()?null:instant(rows.getFirst().get("first_ingested_at"));
    }
    private Context context(String run) {
        var rows=jdbc.queryForList("SELECT requested_at,context::text FROM analytics_run_context WHERE run_id=?",run);
        return rows.isEmpty()?null:new Context(instant(rows.getFirst().get("requested_at")),tree((String)rows.getFirst().get("context")));
    }
    private record Context(Instant at,JsonNode data) {}
    private Map<String,Object> asMap(JsonNode data) { return mapper.convertValue(data,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>() {}); }
    private String json(Object value) { try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException(e);} }
    private JsonNode tree(String value) {try{return value==null?mapper.createArrayNode():mapper.readTree(value);}catch(Exception e){throw new IllegalArgumentException(e);} }
    private static Instant instant(Object value) { if(value instanceof Timestamp t)return t.toInstant(); if(value instanceof OffsetDateTime t)return t.toInstant(); return (Instant)value; }
    static double seconds(Instant from,Instant to) {return from==null?0:Math.max(0,Duration.between(from,to).toMillis()/1000.0);}
    static String editType(JsonNode edit) {
        if("insert_after".equals(edit.path("operation").asText()))return "append";
        if ("replace".equals(edit.path("operation").asText())) return switch (edit.path("actual_target").path("type").asText()) {
            case "whole_document" -> "rewrite";
            case "selection", "current_section" -> "replace";
            default -> "other";
        };
        return "other";
    }
    static Map<String,Object> props(Object... pairs) {
        var map=new LinkedHashMap<String,Object>(); for(int i=0;i<pairs.length;i+=2)map.put((String)pairs[i],pairs[i+1]); return map;
    }
}
