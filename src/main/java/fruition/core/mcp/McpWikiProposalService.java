package fruition.core.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Six staged I9 adapters. They are intentionally absent from {@link McpToolCatalog#tools()}. */
@Service
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpWikiProposalService {
    private static final String SCHEMA = "mcp.wiki-draft.v1";
    private static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern DOCUMENT = Pattern.compile("(?:doc_|chatdoc_)[A-Za-z0-9][A-Za-z0-9_-]{0,246}");
    private final McpAccessClient access;
    private final McpWikiSourceService sourceService;
    private final McpWikiDraftClient ai;
    private final McpWikiApprovalService approvals;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final URI reviewOrigin;
    private final int maxSourceDocuments;
    private final long maxTotalSourceBytes;

    public McpWikiProposalService(McpAccessClient access, McpWikiSourceService sourceService,
                                  McpWikiDraftClient ai, McpWikiApprovalService approvals,
                                  JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper mapper,
                                  @Value("${app.mcp.review-origin:}") String reviewOrigin,
                                  @Value("${app.mcp.wiki.max-source-documents:}") String maxSourceDocuments,
                                  @Value("${app.mcp.wiki.max-total-source-bytes:}") String maxTotalSourceBytes) {
        this.access=access; this.sourceService=sourceService; this.ai=ai; this.approvals=approvals;
        this.jdbc=jdbc; this.transaction=transaction; this.mapper=mapper;
        this.reviewOrigin=reviewOrigin.isBlank()?null:reviewOrigin(reviewOrigin);
        this.maxSourceDocuments=configuredInt(maxSourceDocuments);
        this.maxTotalSourceBytes=configuredLong(maxTotalSourceBytes);
    }

    public Object invoke(McpIdentity tokenIdentity, String tool, Map<String,Object> arguments) {
        try {
            McpProposalActor actor=McpDocumentProposalService.actor(tokenIdentity,UUID.randomUUID().toString(),tool);
            McpIdentity identity=access.authorizeWikiProposal(actor,permission(tool));
            requireSame(tokenIdentity,identity);
            return switch(tool){
                case "start_ingest" -> start(identity,actor,arguments);
                case "resume_ingest" -> resume(identity,actor,arguments);
                case "get_window" -> window(identity,actor,arguments);
                case "submit_window" -> submit(identity,actor,arguments);
                case "finalize_ingest" -> finalizeDraft(identity,actor,arguments);
                case "submit_lint_changes" -> lint(identity,actor,arguments);
                default -> throw invalid();
            };
        } catch(McpToolException e){throw e;}
        catch(McpAuthorizationException e){throw new McpToolException(e.code());}
        catch(IllegalArgumentException e){throw new McpToolException("INVALID_INPUT");}
        catch(Exception e){throw new McpToolException("DEPENDENCY_UNAVAILABLE");}
    }

    private JsonNode start(McpIdentity id,McpProposalActor actor,Map<String,Object> args){
        exact(args,Set.of("idempotency_key","document_id"));
        String key=text(args.get("idempotency_key"),255),document=text(args.get("document_id"),255);
        if(!DOCUMENT.matcher(document).matches())throw invalid();
        Map<String,Object> body=linked("schema_version",SCHEMA,"actor",actorMap(actor),"idempotency_key",key,
                "request_hash",hash(linked("schema_version",SCHEMA,"actor",hashActor(actor),"document_id",document)),
                "document_id",document);
        JsonNode response=ai.post("/internal/mcp/wiki/ingest-runs",body);
        validateStart(response,id,document);
        JsonNode source=response.path("source_snapshot");
        transaction.executeWithoutResult(tx->{
            jdbc.update("INSERT INTO mcp_wiki_ingest_handles(run_id,draft_id,snapshot_id,document_id,source_version,"
                            +"source_content_hash,workspace_id,user_id,grant_id,client_id,policy_generation) "
                            +"VALUES (?::uuid,?::uuid,?::uuid,?,?,?,?,?,?::uuid,?,?) ON CONFLICT (run_id) DO NOTHING",
                    response.path("run_id").asText(),response.path("draft_id").asText(),source.path("snapshot_id").asText(),
                    document,source.path("source_version").asLong(),source.path("source_content_hash").asText(),
                    id.workspaceId(),id.userId(),id.grantId(),id.clientId(),id.policyGeneration());
            Handle stored=handle(response.path("run_id").asText(),id);
            if(stored==null||!stored.snapshotId.equals(source.path("snapshot_id").asText())
                    ||!stored.documentId.equals(document))throw new McpToolException("IDEMPOTENCY_CONFLICT");
        });
        return response;
    }

    private JsonNode resume(McpIdentity id,McpProposalActor actor,Map<String,Object> args){
        exact(args,Set.of("run_id")); String run=uuid4(args.get("run_id")); handleRequired(run,id);
        JsonNode result=ai.post("/internal/mcp/wiki/ingest-runs/"+run+"/resume",
                linked("schema_version",SCHEMA,"actor",actorMap(actor)));
        requireFields(result,Set.of("schema_version","run_id","draft_id","state","snapshot_id","next_window_ordinal","submitted_window_count"));
        if(!run.equals(result.path("run_id").asText()))throw dependency();
        return result;
    }

    private JsonNode window(McpIdentity id,McpProposalActor actor,Map<String,Object> args){
        exact(args,Set.of("run_id","window_ordinal")); String run=uuid4(args.get("run_id"));handleRequired(run,id);
        long ordinal=positive(args.get("window_ordinal"));
        JsonNode result=ai.post("/internal/mcp/wiki/ingest-runs/"+run+"/windows/read",
                linked("schema_version",SCHEMA,"actor",actorMap(actor),"window_ordinal",ordinal));
        requireFields(result,Set.of("schema_version","run_id","draft_id","snapshot_id","window_id","window_ordinal",
                "window_hash","blocks","has_more","data_boundary","concept_resolution"));
        JsonNode resolution=result.path("concept_resolution");
        requireFields(resolution,Set.of("mode","instructions"));
        if(!"CLIENT_LLM_SEMANTIC".equals(resolution.path("mode").asText())
                ||!resolution.path("instructions").isTextual()||resolution.path("instructions").asText().isBlank())throw dependency();
        if(!run.equals(result.path("run_id").asText())||result.path("window_ordinal").asLong()!=ordinal
                ||!HASH.matcher(result.path("window_hash").asText()).matches())throw dependency();
        jdbc.update("INSERT INTO mcp_wiki_ingest_windows(run_id,window_id,window_ordinal,window_hash) "
                        +"VALUES (?::uuid,?::uuid,?,?) ON CONFLICT (run_id,window_id) DO NOTHING",
                run,result.path("window_id").asText(),ordinal,result.path("window_hash").asText());
        return result;
    }

    private JsonNode submit(McpIdentity id,McpProposalActor actor,Map<String,Object> args){
        exact(args,Set.of("idempotency_key","run_id","window_id","snapshot_id","window_hash","contributions"));
        String run=uuid4(args.get("run_id")),window=uuid4(args.get("window_id")); Handle handle=handleRequired(run,id);
        String snapshot=uuid4(args.get("snapshot_id")),windowHash=hashValue(args.get("window_hash"));
        if(!snapshot.equals(handle.snapshotId))throw invalid();
        Window windowRow=window(run,window,windowHash);
        List<Map<String,Object>> normalized=contributions(args.get("contributions"),handle);
        List<Map<String,Object>> mapping=List.of(linked("snapshot_id",handle.snapshotId,"document_id",handle.documentId,
                "source_version",handle.sourceVersion,"source_content_hash",handle.sourceHash));
        Map<String,Object> canonical=linked("schema_version",SCHEMA,"actor",hashActor(actor),"run_id",run,
                "window_id",window,"snapshot_id",snapshot,"window_hash",windowHash,"source_snapshots",mapping,
                "contributions",normalized);
        Map<String,Object> body=linked("schema_version",SCHEMA,"actor",actorMap(actor),
                "idempotency_key",text(args.get("idempotency_key"),255),"request_hash",hash(canonical),
                "snapshot_id",snapshot,"window_hash",windowHash,"contributions",normalized);
        JsonNode result=ai.post("/internal/mcp/wiki/ingest-runs/"+run+"/windows/"+window+"/submissions",body);
        requireFields(result,Set.of("schema_version","run_id","draft_id","window_id","accepted_submission_hash","state","next_window_ordinal"));
        if(!run.equals(result.path("run_id").asText())||!window.equals(result.path("window_id").asText())
                ||!HASH.matcher(result.path("accepted_submission_hash").asText()).matches())throw dependency();
        jdbc.update("UPDATE mcp_wiki_ingest_windows SET accepted_submission_hash=?,contributions=?::jsonb "
                        +"WHERE run_id=?::uuid AND window_id=?::uuid",
                result.path("accepted_submission_hash").asText(),json(normalized),run,window);
        return result;
    }

    private Object finalizeDraft(McpIdentity id,McpProposalActor actor,Map<String,Object> args){
        exact(args,Set.of("idempotency_key","run_id","snapshot_id","expected_submitted_window_count"));
        String run=uuid4(args.get("run_id"));Handle handle=handleRequired(run,id);String snapshot=uuid4(args.get("snapshot_id"));
        long count=positive(args.get("expected_submitted_window_count"));if(!snapshot.equals(handle.snapshotId))throw invalid();
        List<Map<String,Object>> submissions=jdbc.query("SELECT window_id,window_ordinal,window_hash,accepted_submission_hash,contributions "
                        +"FROM mcp_wiki_ingest_windows WHERE run_id=?::uuid ORDER BY window_ordinal",
                (rs,n)->linked("window_id",rs.getString(1),"window_ordinal",rs.getLong(2),"window_hash",rs.getString(3),
                        "accepted_submission_hash",rs.getString(4),"contributions",readList(rs.getString(5))),run);
        if(submissions.size()!=count||submissions.stream().anyMatch(s->s.get("accepted_submission_hash")==null))throw invalid();
        List<Map<String,Object>> mapping=List.of(linked("snapshot_id",handle.snapshotId,"document_id",handle.documentId,
                "source_version",handle.sourceVersion,"source_content_hash",handle.sourceHash));
        Map<String,Object> canonical=linked("schema_version",SCHEMA,"actor",hashActor(actor),"run_id",run,
                "snapshot_id",snapshot,"expected_submitted_window_count",count,"source_snapshots",mapping,"submissions",submissions);
        Map<String,Object> body=linked("schema_version",SCHEMA,"actor",actorMap(actor),"idempotency_key",
                text(args.get("idempotency_key"),255),"request_hash",hash(canonical),"snapshot_id",snapshot,
                "expected_submitted_window_count",count);
        JsonNode result=ai.post("/internal/mcp/wiki/ingest-runs/"+run+"/finalize",body);
        validateDraftResult(result,"INGEST");
        JsonNode preview=ai.getPreview(result.path("draft_id").asText(),result.path("draft_version").asLong(),id.workspaceId(),id.userId());
        approvals.registerDraft(id,run,preview);
        return withReviewUrl(result,id.workspaceId());
    }

    private Object lint(McpIdentity id,McpProposalActor actor,Map<String,Object> args){
        exact(args,Set.of("idempotency_key","target_snapshot","source_documents","operations"));
        String key=text(args.get("idempotency_key"),255);
        List<Map<String,Object>> targets=targets(args.get("target_snapshot"));
        List<Map<String,Object>> documents=sourceDocuments(args.get("source_documents"));
        if(maxSourceDocuments<1||maxTotalSourceBytes<1)throw dependency();
        if(documents.size()>maxSourceDocuments)throw new McpToolException("SOURCE_LIMIT_EXCEEDED");
        List<Map<String,Object>> operations=lintOperations(args.get("operations"),documents);
        String externalHash=hash(linked("actor",hashActor(actor),"target_snapshot",targets,"source_documents",documents,"operations",operations));
        List<Map<String,Object>> mappings=lintMappings(id,actor,key,externalHash,documents);
        Map<String,String> snapshotByDocument=new LinkedHashMap<>();
        mappings.forEach(m->snapshotByDocument.put((String)m.get("document_id"),(String)m.get("snapshot_id")));
        List<Map<String,Object>> internalOps=replaceLintEvidence(operations,snapshotByDocument);
        Map<String,Object> canonical=linked("schema_version",SCHEMA,"actor",hashActor(actor),
                "target_snapshot",targets.stream().sorted(Comparator.comparing(v->(String)v.get("page_id"))).toList(),
                "source_snapshots",mappings.stream().sorted(Comparator.comparing(v->(String)v.get("snapshot_id"))).toList(),
                "operations",internalOps);
        Map<String,Object> body=linked("schema_version",SCHEMA,"actor",actorMap(actor),"idempotency_key",key,
                "request_hash",hash(canonical),"target_snapshot",targets,"source_snapshots",mappings,"operations",internalOps);
        JsonNode result=ai.post("/internal/mcp/wiki/lint-drafts",body);validateDraftResult(result,"LINT");
        jdbc.update("UPDATE mcp_wiki_lint_intents SET draft_id=?::uuid WHERE workspace_id=? AND user_id=? AND grant_id=?::uuid "
                        +"AND client_id=? AND idempotency_key=?",
                result.path("draft_id").asText(),id.workspaceId(),id.userId(),id.grantId(),id.clientId(),key);
        JsonNode preview=ai.getPreview(result.path("draft_id").asText(),result.path("draft_version").asLong(),id.workspaceId(),id.userId());
        approvals.registerDraft(id,result.path("draft_id").asText(),preview);
        return withReviewUrl(result,id.workspaceId());
    }

    private List<Map<String,Object>> lintMappings(McpIdentity id,McpProposalActor actor,String key,String externalHash,
                                                   List<Map<String,Object>> documents){
        return transaction.execute(tx->{
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","mcp-wiki-lint:"+id.grantId()+":"+key);
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT request_hash,source_snapshots FROM mcp_wiki_lint_intents "
                    +"WHERE workspace_id=? AND user_id=? AND grant_id=?::uuid AND client_id=? AND idempotency_key=?",
                    id.workspaceId(),id.userId(),id.grantId(),id.clientId(),key);
            if(!rows.isEmpty()){
                if(!externalHash.equals(rows.getFirst().get("request_hash")))throw new McpToolException("IDEMPOTENCY_CONFLICT");
                return readList(String.valueOf(rows.getFirst().get("source_snapshots")));
            }
            List<Map<String,Object>> mappings=new ArrayList<>();
            long totalBytes=0;
            for(Map<String,Object> document:documents){
                McpWikiSourceService.Response current=sourceService.read(actor,(String)document.get("document_id"));
                if(current.sourceVersion()!=((Number)document.get("current_version")).longValue()
                        ||!current.sourceContentHash().equals(document.get("content_hash")))throw new McpToolException("SOURCE_CHANGED");
                for(McpWikiSourceService.Block block:current.blocks()){
                    totalBytes=Math.addExact(totalBytes,block.text().getBytes(StandardCharsets.UTF_8).length);
                    if(totalBytes>maxTotalSourceBytes)throw new McpToolException("SOURCE_LIMIT_EXCEEDED");
                }
                mappings.add(linked("snapshot_id",UUID.randomUUID().toString(),"document_id",document.get("document_id"),
                        "source_version",document.get("current_version"),"source_content_hash",document.get("content_hash")));
            }
            jdbc.update("INSERT INTO mcp_wiki_lint_intents(workspace_id,user_id,grant_id,client_id,policy_generation,"
                            +"idempotency_key,request_hash,source_snapshots) VALUES (?,?,?::uuid,?,?,?,?,?::jsonb)",
                    id.workspaceId(),id.userId(),id.grantId(),id.clientId(),id.policyGeneration(),key,externalHash,json(mappings));
            return mappings;
        });
    }

    @SuppressWarnings("unchecked") private List<Map<String,Object>> replaceLintEvidence(List<Map<String,Object>> operations,
                                                                                         Map<String,String> snapshots){
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> operation:operations){
            Map<String,Object> copy=new LinkedHashMap<>(operation),args=new LinkedHashMap<>((Map<String,Object>)operation.get("arguments"));
            if("UPSERT_STATEMENT".equals(operation.get("kind"))){
                List<Map<String,Object>> evidence=new ArrayList<>();
                for(Map<String,Object> item:(List<Map<String,Object>>)args.get("evidence")){
                    String document=(String)item.get("document_id"),snapshot=snapshots.get(document);if(snapshot==null)throw invalid();
                    evidence.add(linked("snapshot_id",snapshot,"block_id",item.get("block_id"),"quote",item.get("quote")));
                }
                args.put("evidence",evidence);
            }
            copy.put("arguments",args);out.add(copy);
        }
        return out;
    }

    @SuppressWarnings("unchecked") private List<Map<String,Object>> contributions(Object raw,Handle handle){
        if(!(raw instanceof List<?> list)||list.isEmpty())throw invalid();List<Map<String,Object>> out=new ArrayList<>();
        for(Object value:list){Map<String,Object> c=map(value);
            exact(c,c.containsKey("merge_target_page_id")?Set.of("concept_name","statements","links","merge_target_page_id")
                    :Set.of("concept_name","statements","links"));
            text(c.get("concept_name"),255);List<Map<String,Object>> statements=new ArrayList<>();
            if(!(c.get("statements") instanceof List<?> statementList)||statementList.isEmpty())throw invalid();
            for(Object item:statementList){Map<String,Object>s=map(item);exact(s,Set.of("statement_id","text","evidence"));
                text(s.get("statement_id"),255);text(s.get("text"),10000);List<Map<String,Object>> evidence=new ArrayList<>();
                if(!(s.get("evidence") instanceof List<?> evidenceList)||evidenceList.isEmpty())throw invalid();
                for(Object e:evidenceList){Map<String,Object>ev=map(e);exact(ev,Set.of("document_id","block_id","quote"));
                    if(!handle.documentId.equals(text(ev.get("document_id"),255)))throw invalid();
                    evidence.add(linked("snapshot_id",handle.snapshotId,"block_id",text(ev.get("block_id"),255),
                            "quote",text(ev.get("quote"),10000)));}
                statements.add(linked("statement_id",s.get("statement_id"),"text",s.get("text"),"evidence",evidence));}
            List<Map<String,Object>> links=new ArrayList<>();if(!(c.get("links") instanceof List<?> linkList))throw invalid();
            for(Object item:linkList){Map<String,Object>l=map(item);exact(l,Set.of("source_statement_id","target_concept_name","relation"));
                if(!"concept_related_to".equals(l.get("relation")))throw invalid();links.add(l);}
            Map<String,Object> normalized=linked("concept_name",c.get("concept_name"),"statements",statements,"links",links);
            if(c.get("merge_target_page_id")!=null){String target=text(c.get("merge_target_page_id"),255);
                if(!target.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,254}"))throw invalid();
                normalized.put("merge_target_page_id",target);}
            out.add(normalized);}
        return out;
    }

    private List<Map<String,Object>> targets(Object raw){
        if(!(raw instanceof List<?> list)||list.isEmpty())throw invalid();List<Map<String,Object>> out=new ArrayList<>();Set<String> ids=new java.util.HashSet<>();
        for(Object value:list){Map<String,Object>v=map(value);exact(v,Set.of("page_id","version","content_hash"));String id=text(v.get("page_id"),255);
            if(!ids.add(id))throw invalid();positive(v.get("version"));hashValue(v.get("content_hash"));out.add(v);}return out;
    }
    private List<Map<String,Object>> sourceDocuments(Object raw){
        if(!(raw instanceof List<?> list)||list.isEmpty())throw invalid();List<Map<String,Object>> out=new ArrayList<>();Set<String> ids=new java.util.HashSet<>();
        for(Object value:list){Map<String,Object>v=map(value);exact(v,Set.of("document_id","current_version","content_hash"));String id=text(v.get("document_id"),255);
            if(!DOCUMENT.matcher(id).matches()||!ids.add(id))throw invalid();positive(v.get("current_version"));hashValue(v.get("content_hash"));out.add(v);}return out;
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> lintOperations(Object raw,List<Map<String,Object>> documents){
        if(!(raw instanceof List<?> list)||list.isEmpty())throw invalid();List<Map<String,Object>> out=new ArrayList<>();Set<String> ids=new java.util.HashSet<>();
        Map<String,Set<String>> fields=Map.of("UPSERT_STATEMENT",Set.of("statement_id","text","evidence"),
                "REMOVE_STATEMENT",Set.of("statement_id","expected_text_hash"),"MERGE_CONCEPTS",Set.of("source_page_ids","destination_page_id"),
                "ADD_LINK",Set.of("source_ref","target_ref","relation"),"REMOVE_LINK",Set.of("source_ref","target_ref","relation"),
                "DELETE_ORPHAN_PAGE",Set.of("page_id","expected_content_hash"));
        for(Object value:list){Map<String,Object>op=map(value);exact(op,Set.of("operation_id","kind","target_page_id","arguments","depends_on"));
            String id=text(op.get("operation_id"),255),kind=text(op.get("kind"),64);if(!ids.add(id)||!fields.containsKey(kind))throw invalid();text(op.get("target_page_id"),255);
            Map<String,Object>a=map(op.get("arguments"));exact(a,fields.get(kind));if("UPSERT_STATEMENT".equals(kind)){
                if(a.get("statement_id")!=null)text(a.get("statement_id"),255);text(a.get("text"),10000);
                if(!(a.get("evidence") instanceof List<?> evidence)||evidence.isEmpty())throw invalid();
                for(Object item:evidence){Map<String,Object>e=map(item);exact(e,Set.of("document_id","block_id","quote"));text(e.get("document_id"),255);text(e.get("block_id"),255);text(e.get("quote"),10000);}}
            if(!(op.get("depends_on") instanceof List<?> deps)||deps.stream().anyMatch(dep->!(dep instanceof String)||!ids.contains(dep)))throw invalid();out.add(op);}return out;
    }

    private Handle handleRequired(String run,McpIdentity id){Handle value=handle(run,id);if(value==null)throw new McpToolException("RESOURCE_NOT_FOUND");return value;}
    private Handle handle(String run,McpIdentity id){
        List<Handle> rows=jdbc.query("SELECT * FROM mcp_wiki_ingest_handles WHERE run_id=?::uuid AND workspace_id=? AND user_id=? "
                        +"AND grant_id=?::uuid AND client_id=? AND policy_generation=?",(rs,n)->new Handle(rs.getString("run_id"),
                        rs.getString("draft_id"),rs.getString("snapshot_id"),rs.getString("document_id"),rs.getLong("source_version"),rs.getString("source_content_hash")),
                run,id.workspaceId(),id.userId(),id.grantId(),id.clientId(),id.policyGeneration());return rows.isEmpty()?null:rows.getFirst();}
    private Window window(String run,String window,String hash){List<Window> rows=jdbc.query("SELECT window_ordinal,window_hash FROM mcp_wiki_ingest_windows "
                    +"WHERE run_id=?::uuid AND window_id=?::uuid",(rs,n)->new Window(rs.getLong(1),rs.getString(2)),run,window);
        if(rows.isEmpty()||!hash.equals(rows.getFirst().hash))throw invalid();return rows.getFirst();}

    private Object withReviewUrl(JsonNode response,String workspace){if(reviewOrigin==null)throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        Map<String,Object> out=mapper.convertValue(response,new TypeReference<>(){});out.put("review_url",UriComponentsBuilder.fromUri(reviewOrigin)
                .pathSegment("mcp","workspaces",workspace,"wiki-drafts",response.path("draft_id").asText()).build().encode().toUriString());return out;}
    private static URI reviewOrigin(String value){URI uri=URI.create(value);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null
                ||uri.getQuery()!=null||uri.getFragment()!=null||(!uri.getPath().isEmpty()&&!"/".equals(uri.getPath())))throw new IllegalArgumentException();return URI.create("https://"+uri.getAuthority());}
    private static String permission(String tool){return "submit_lint_changes".equals(tool)?"wiki.lint":"wiki.ingest";}
    private static void requireSame(McpIdentity expected,McpIdentity actual){if(actual==null||!expected.userId().equals(actual.userId())||!expected.workspaceId().equals(actual.workspaceId())
                ||!expected.grantId().equals(actual.grantId())||!expected.clientId().equals(actual.clientId())||expected.policyGeneration()!=actual.policyGeneration())throw new McpToolException("PERMISSION_REVOKED");}
    private void validateStart(JsonNode n,McpIdentity id,String document){requireFields(n,Set.of("schema_version","run_id","draft_id","state","source_snapshot","next_window_ordinal"));
        JsonNode s=n.path("source_snapshot");requireFields(s,Set.of("snapshot_id","document_id","source_version","source_content_hash","block_count"));
        if(!"ACTIVE_WINDOWING".equals(n.path("state").asText())||!document.equals(s.path("document_id").asText())||!HASH.matcher(s.path("source_content_hash").asText()).matches())throw dependency();}
    private void validateDraftResult(JsonNode n,String kind){Set<String> fields="INGEST".equals(kind)?Set.of("schema_version","run_id","draft_id","draft_version","draft_hash","public_state_hash","state")
                :Set.of("schema_version","draft_id","draft_version","draft_hash","public_state_hash","state");requireFields(n,fields);
        if(!"AWAITING_WEB_APPROVAL".equals(n.path("state").asText())||n.path("draft_version").asLong()!=1||!HASH.matcher(n.path("draft_hash").asText()).matches()||!HASH.matcher(n.path("public_state_hash").asText()).matches())throw dependency();}
    private static void requireFields(JsonNode n,Set<String> fields){if(n==null||!n.isObject()||n.size()!=fields.size()
            ||fields.stream().anyMatch(f->!n.has(f))||(fields.contains("schema_version")
            &&!SCHEMA.equals(n.path("schema_version").asText())))throw dependency();}
    private Map<String,Object> actorMap(McpProposalActor a){return mapper.convertValue(a,new TypeReference<>(){});}private Map<String,Object> hashActor(McpProposalActor a){Map<String,Object>v=actorMap(a);v.remove("invocation_id");return v;}
    private String hash(Object value){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String json(Object v){try{return mapper.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private List<Map<String,Object>> readList(String value){try{return mapper.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException(e);}}
    @SuppressWarnings("unchecked")private static Map<String,Object> map(Object v){if(!(v instanceof Map<?,?>m))throw invalid();return (Map<String,Object>)m;}
    private static void exact(Map<String,Object>m,Set<String>f){if(m==null||!m.keySet().equals(f))throw invalid();}
    private static String text(Object v,int max){if(!(v instanceof String s)||s.isBlank()||!s.equals(s.trim())||s.length()>max)throw invalid();return s;}
    private static long positive(Object v){if(!(v instanceof Number n)||n.longValue()<1||n.doubleValue()!=n.longValue())throw invalid();return n.longValue();}
    private static String uuid4(Object v){String s=text(v,36);try{UUID u=UUID.fromString(s);if(u.version()!=4||!u.toString().equals(s))throw invalid();return s;}catch(Exception e){throw invalid();}}
    private static String hashValue(Object v){String s=text(v,71);if(!HASH.matcher(s).matches())throw invalid();return s;}
    private static int configuredInt(String value){try{int parsed=Integer.parseInt(value);return parsed>0?parsed:0;}catch(Exception e){return 0;}}
    private static long configuredLong(String value){try{long parsed=Long.parseLong(value);return parsed>0?parsed:0;}catch(Exception e){return 0;}}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid I9 tool input");}private static McpToolException dependency(){return new McpToolException("DEPENDENCY_UNAVAILABLE");}
    private static Map<String,Object> linked(Object...v){Map<String,Object>m=new LinkedHashMap<>();for(int i=0;i<v.length;i+=2)m.put((String)v[i],v[i+1]);return m;}
    private record Handle(String runId,String draftId,String snapshotId,String documentId,long sourceVersion,String sourceHash){}
    private record Window(long ordinal,String hash){}
}
