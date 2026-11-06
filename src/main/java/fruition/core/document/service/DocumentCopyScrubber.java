package fruition.core.document.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 영구 삭제한 문서의 본문 복사본을 core_db에서 지운다. 행은 이력으로 남기고 본문 값만 지운다.
 *
 * <p>{@link #scrub}은 문서 행을 지우는 트랜잭션 안에서 돈다. 표현식 인덱스(V98~V100, V102, V103)로 그 문서의 행만 잠그므로 큰 표를 훑지 않는다.
 *
 * <p>진행 중인 실행은 건드리지 않는다. 취소가 원본 command의 해시로 AI에 등록하고, 늦게 온 결과가 projection에 본문을 쓸 수 있어서다.
 * 끝난 뒤 {@link #scrubOrphans}가 "문서가 더 없는 행"을 찾아 지운다. 기억해 둘 대기열이 없어 이 기능 전에 지운 문서도 함께 정리된다.
 * 다시 취소를 요청할 수 있는 끝난 실행은 {@code document_purged} 표시를 남겨 AI가 해시 대조를 건너뛰게 한다.
 *
 * <p>사용자 메시지(conversation_context, message)와 답변 본문은 범위 밖이라 지우지 않는다. 채팅 메시지의 변경 기록도 같은 값이라 남긴다.
 * 채팅 인용은 위치(문서·블록)를 남기고 원문(quote)만 비운다({@link #scrubCitations}).
 */
@Component
public class DocumentCopyScrubber {

    private static final Logger log = LoggerFactory.getLogger(DocumentCopyScrubber.class);
    /** 취소·재시도·결과 반영이 원본 command를 더 쓰지 않는 상태. rollback_failed의 재시도는 document_purged 표시로 처리한다. */
    private static final String FINISHED_RUN = "status IN ('completed', 'failed', 'cancelled', 'rollback_failed')";
    private static final String COMMAND_HAS_BODY = """
            (command #> '{editor_snapshot,markdown}' IS NOT NULL
             OR command -> 'input_markdown' IS NOT NULL OR command -> 'input_blocks' IS NOT NULL)""";
    private static final String SCRUB_COMMAND = """
            UPDATE ai_task_runs
            SET command = (((command #- '{editor_snapshot,markdown}') - 'input_markdown') - 'input_blocks')
                          || '{"document_purged": true}'::jsonb
            """;
    /** queued는 결과가 아직 오지 않았다. 끝나면 scrubOrphans가 지운다. ready는 적용할 수 없으니 실패로 닫는다. */
    private static final String SCRUB_PROJECTION = """
            UPDATE agent_apply_projections
            SET ready_markdown = NULL,
                result = (result - 'edit' - 'generated_markdown') #- '{chat,evidence_snippets}',
                error_code = CASE WHEN status = 'ready' THEN 'document_purged' ELSE error_code END,
                status = CASE WHEN status = 'ready' THEN 'failed' ELSE status END,
                updated_at = now()
            """;
    private static final String PROJECTION_HAS_BODY = """
            (ready_markdown IS NOT NULL OR result -> 'edit' IS NOT NULL OR result -> 'generated_markdown' IS NOT NULL
             OR result #> '{chat,evidence_snippets}' IS NOT NULL)""";
    /** V99 부분 인덱스의 표현식과 같아야 인덱스를 탄다. */
    private static final String CHANGE_DOCUMENT_ID =
            "(CASE table_name WHEN 'documents' THEN row_key->>'id' ELSE row_key->>'document_id' END)";
    private static final String CHANGE_DOCUMENT_ID_OF_C =
            "(CASE c.table_name WHEN 'documents' THEN c.row_key->>'id' ELSE c.row_key->>'document_id' END)";
    private static final String DOCUMENT_CHANGE_TABLES =
            "table_name IN ('documents', 'document_content_versions', 'document_edit_states', 'document_edit_writes')";
    /** agent receipt는 재전달 판정(키)에만 쓰고 event_payload는 읽지 않는다. 질의 receipt는 재전달이 읽어 근거만 비운다. */
    private static final String CLEAR_AGENT_RECEIPT =
            "UPDATE ai_task_result_receipts SET event_payload = NULL WHERE task_kind = 'agent' AND event_payload IS NOT NULL ";
    /** V102·V103 인덱스의 표현식과 같아야 인덱스를 탄다. 근거에는 문서 id가 있지만 행에는 없는 질의를 이것으로 찾는다. */
    private static final String PROJECTION_EVIDENCE = "result #> '{chat,evidence_snippets}'";
    private static final String RECEIPT_EVIDENCE =
            "COALESCE(event_payload #> '{payload,chat,evidence_snippets}', event_payload #> '{payload,evidence_snippets}')";
    private static final String CITES_DOCUMENT =
            " @> jsonb_build_array(jsonb_build_object('source_document_id', CAST(:id AS text)))";
    /** 여러 문서를 걸친 근거는 대표 id가 다른 문서이거나 비어 있어 source_refs도 본다. 같은 GIN 인덱스(V102·V103)를 타므로 인덱스를 더 두지 않는다. */
    private static final String REFS_DOCUMENT =
            " @> jsonb_build_array(jsonb_build_object('source_refs', jsonb_build_array(jsonb_build_object('source_document_id', CAST(:id AS text)))))";
    /** 이 문서를 가리키는 근거 본문만 비운다. 문서별로 나눌 수 없는 여러 문서 근거는 함께 비운다. 순서와 다른 문서의 근거, 답변은 그대로다. */
    private static final String BLANK_EVIDENCE = """
            jsonb_set(%1$s, '%2$s', (
                SELECT jsonb_agg(CASE WHEN snippet->>'source_document_id' = :id OR snippet->'source_refs'""" + CITES_DOCUMENT + """
             THEN snippet || '{"text": ""}' ELSE snippet END
                                 ORDER BY position)
                FROM jsonb_array_elements(%1$s #> '%2$s') WITH ORDINALITY AS item(snippet, position)))""";

    /** V106 인덱스의 표현식과 같아야 인덱스를 탄다. 여러 문서를 가리키는 인용은 대표 문서(document_id)만으로 찾을 수 없다. */
    private static final String CITATION_OF_DOCUMENT = "(document_id = :id OR source_refs::jsonb" + CITES_DOCUMENT + ")";
    /** 대표 문서가 지워진 인용. 근거 저장은 문서가 있는 인용만 남기므로 document_id가 NULL이면 FK가 비운 행이다(V105). */
    private static final String ORPHAN_CITATION = "document_id IS NULL AND quote <> ''";
    /**
     * 대표 문서는 살아 있고 source_refs의 다른 문서만 이 기능 전에 지워진 인용. EXISTS 앞의 세 조건은 V107 부분 인덱스와 같아야 인덱스를 탄다.
     * 웹 근거는 문서가 아니고, 휴지통 문서는 행이 있어 대상이 아니다.
     */
    private static final String ORPHAN_SOURCE_REF_CITATION = """
            document_id IS NOT NULL AND quote <> ''
              AND jsonb_path_exists(source_refs::jsonb, '$[1]')
              AND EXISTS (SELECT 1 FROM jsonb_path_query(source_refs::jsonb, '$[*].source_document_id') AS ref(value)
                          WHERE value #>> '{}' NOT LIKE 'web:%' AND NOT EXISTS (SELECT 1 FROM documents d WHERE d.id = value #>> '{}'))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DocumentCopyScrubber(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 호출자의 트랜잭션에서 지운다. 되돌려지면 함께 되돌려진다. */
    public void scrub(List<String> documentIds) {
        MapSqlParameterSource params = new MapSqlParameterSource("ids", documentIds);
        // 되돌리기는 변경 전 값을 그대로 쓰므로 값을 비우지 않고 기록을 지운다. 지운 기록은 되돌리기가 되살리지 않는다.
        jdbc.update("DELETE FROM ai_task_changes WHERE " + DOCUMENT_CHANGE_TABLES
                + " AND " + CHANGE_DOCUMENT_ID + " IN (:ids)", params);
        jdbc.update("""
                DELETE FROM ai_task_changes
                WHERE table_name = 'agent_apply_projections'
                  AND run_id IN (SELECT run_id FROM agent_apply_projections WHERE document_id IN (:ids))
                  AND row_key->>'run_id' = run_id
                """, params);
        jdbc.update(SCRUB_PROJECTION + " WHERE document_id IN (:ids) AND status <> 'queued' AND " + PROJECTION_HAS_BODY, params);
        jdbc.update(SCRUB_COMMAND + " WHERE command->>'document_id' IN (:ids) AND " + FINISHED_RUN
                + " AND " + COMMAND_HAS_BODY, params);
        jdbc.update(CLEAR_AGENT_RECEIPT + "AND run_id IN (SELECT id FROM ai_task_runs WHERE command->>'document_id' IN (:ids))", params);
        documentIds.forEach(this::blankEvidence);
    }

    /**
     * 문서를 열지 않은 질의는 행에 문서 id가 없고, 채팅 근거의 document_id는 문서를 지울 때 NULL이 되며, 질의 경로의 메시지는 run을 모른다.
     * 그래서 근거 항목의 source_document_id로 찾는다. 답변은 채팅 표시·조회 API·재전달이 쓰는 범위 밖 값이라 남긴다.
     *
     * <p>projection의 updated_at을 바꾸지 않는다. 변경 기록의 값도 같게 고쳐 작업 취소의 "현재 값 = 변경 후 값" 대조가 맞게 한다.
     */
    private void blankEvidence(String documentId) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", documentId);
        String citing = citesInEvidence(PROJECTION_EVIDENCE);
        for (String column : List.of("before_value", "after_value")) {
            jdbc.update("UPDATE ai_task_changes SET %1$s = %2$s WHERE table_name = 'agent_apply_projections' AND %3$s AND run_id IN (SELECT run_id FROM agent_apply_projections WHERE %4$s)"
                    .formatted(column, BLANK_EVIDENCE.formatted(column, "{result,chat,evidence_snippets}"),
                            citesInEvidence(column + " #> '{result,chat,evidence_snippets}'"), citing), params);
        }
        jdbc.update("UPDATE agent_apply_projections SET result = %s WHERE %s"
                .formatted(BLANK_EVIDENCE.formatted("result", "{chat,evidence_snippets}"), citing), params);
        String citingReceipt = citesInEvidence(RECEIPT_EVIDENCE);
        jdbc.update(CLEAR_AGENT_RECEIPT + "AND " + citingReceipt, params);
        jdbc.update("UPDATE ai_task_result_receipts SET event_payload = %s WHERE task_kind = 'query' AND event_payload IS NOT NULL AND %s"
                .formatted(BLANK_EVIDENCE.formatted("event_payload", "{payload,evidence_snippets}"), citingReceipt), params);
    }

    private static String citesInEvidence(String evidence) {
        return "(" + evidence + CITES_DOCUMENT + " OR " + evidence + REFS_DOCUMENT + ")";
    }

    /**
     * 이 문서들을 가리키는 인용의 원문을 비운다. 문서 FK가 document_id를 NULL로 비우면 찾을 수 없어 문서 행을 지우기 전에 부른다.
     * 여러 문서를 가리키는 인용은 원문을 문서별로 나눌 수 없어 함께 비우고, 살아 있는 대표 문서는 남긴다.
     */
    public void scrubCitations(List<String> documentIds) {
        for (String documentId : documentIds) {
            blankCitations(CITATION_OF_DOCUMENT, new MapSqlParameterSource("id", documentId), """
                    %1$s || CASE WHEN %1$s->>'document_id' = :id THEN '{"quote": "", "document_id": null}'::jsonb
                                 ELSE '{"quote": ""}'::jsonb END""");
        }
    }

    /**
     * 변경 기록을 먼저 고친다. 되돌리기가 기록 → 행 순서로 잠그므로 같은 순서여야 교착하지 않는다.
     * 되돌리기는 "현재 값 = 변경 후 값"을 대조하므로 기록을 행과 같게 맞춘다. 문서 FK가 비우는 document_id는 기록에 남지 않아 함께 고친다.
     *
     * @return 원문을 비운 인용 수
     */
    private int blankCitations(String condition, MapSqlParameterSource params, String journalValue) {
        jdbc.update("UPDATE ai_task_changes SET before_value = %s, after_value = %s "
                .formatted(journalValue.formatted("before_value"), journalValue.formatted("after_value"))
                + "WHERE table_name = 'chat_message_references' AND row_key->>'id' IN "
                + "(SELECT id::text FROM chat_message_references WHERE " + condition + ")", params);
        return jdbc.update("UPDATE chat_message_references SET quote = '' WHERE " + condition, params);
    }

    /**
     * AI가 알려 준 소스 페이지의 버전 본문과 그 변경 기록을 지운다({@code document_purge} 결과). core_db에는 페이지 종류가 없고
     * 기여의 {@code source_document_id}는 개념 페이지에도 있어 document만으로는 소스 페이지를 가릴 수 없다. 그래서 AI 처리 뒤에 돈다.
     *
     * <p>버전 행은 남기고 본문만 비운다. 버전 기록·변경분·다음 revision 채번이 행을 읽고, 빈 본문은 변경 없음으로 보인다.
     * 살아 있는 문서가 받치는 페이지나 다른 워크스페이스 작업의 페이지는 소스 페이지일 수 없어 건드리지 않는다.
     *
     * @return 대상으로 확인한 페이지 수. 재전달해도 같다
     */
    @Transactional
    public int scrubSourcePages(String workspaceId, List<String> pageIds) {
        MapSqlParameterSource params = new MapSqlParameterSource("pages", pageIds).addValue("ws", workspaceId);
        List<String> eligible = jdbc.queryForList("""
                SELECT DISTINCT v.page_id FROM wiki_page_versions v
                WHERE v.page_id IN (:pages)
                  AND NOT EXISTS (SELECT 1 FROM wiki_page_contributions c JOIN documents d ON d.id = c.source_document_id
                                  WHERE c.page_id = v.page_id)
                  AND NOT EXISTS (SELECT 1 FROM wiki_page_contributions c
                                  JOIN ai_operation_logs l ON l.operation_id = c.ingest_operation_id
                                  WHERE c.page_id = v.page_id AND l.workspace_id <> :ws)
                """, params, String.class);
        if (eligible.size() != pageIds.size()) {
            log.warn("[소스 페이지 본문 정리 제외] workspaceId={} requested={} eligible={}",
                    workspaceId, pageIds.size(), eligible.size());
        }
        if (eligible.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource targets = new MapSqlParameterSource("pages", eligible);
        jdbc.update("UPDATE wiki_page_versions SET markdown = '' WHERE page_id IN (:pages) AND markdown <> ''", targets);
        jdbc.update("DELETE FROM ai_task_changes WHERE table_name = 'wiki_page_versions' "
                + "AND row_key->>'page_id' IN (:pages)", targets);
        return eligible.size();
    }

    /** 문서가 더 없는데 본문이 남은 행을 한 묶음 지운다. 지운 행 수를 돌려준다. 호출자가 묶음마다 트랜잭션을 나눈다. */
    public int scrubOrphans(int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("limit", limit);
        int commands = jdbc.update(SCRUB_COMMAND + """
                 WHERE id IN (
                    SELECT id FROM ai_task_runs
                    WHERE command->>'document_id' IS NOT NULL AND %s AND %s
                      AND NOT EXISTS (SELECT 1 FROM documents d WHERE d.id = command->>'document_id')
                    LIMIT :limit FOR UPDATE SKIP LOCKED)
                """.formatted(FINISHED_RUN, COMMAND_HAS_BODY), params);
        int projections = jdbc.update(SCRUB_PROJECTION + """
                 WHERE run_id IN (
                    SELECT run_id FROM agent_apply_projections
                    WHERE document_id IS NOT NULL AND status <> 'queued' AND %s
                      AND NOT EXISTS (SELECT 1 FROM documents d WHERE d.id = document_id)
                    LIMIT :limit FOR UPDATE SKIP LOCKED)
                """.formatted(PROJECTION_HAS_BODY), params);
        // 취소·늦은 결과로 영구 삭제 뒤에 남은 receipt와 projection의 변경 기록. 진행 중 실행은 receipt가 아직 없다.
        int receipts = jdbc.update(CLEAR_AGENT_RECEIPT + """
                AND run_id IN (
                    SELECT r.run_id FROM ai_task_result_receipts r JOIN ai_task_runs t ON t.id = r.run_id
                    WHERE r.task_kind = 'agent' AND r.event_payload IS NOT NULL AND t.command->>'document_id' IS NOT NULL
                      AND NOT EXISTS (SELECT 1 FROM documents d WHERE d.id = t.command->>'document_id')
                    LIMIT :limit FOR UPDATE OF r SKIP LOCKED)
                """, params);
        int changes = jdbc.update("""
                DELETE FROM ai_task_changes WHERE id IN (
                    SELECT c.id FROM ai_task_changes c JOIN agent_apply_projections p ON p.run_id = c.run_id
                    WHERE c.table_name = 'agent_apply_projections' AND c.row_key->>'run_id' = c.run_id
                      AND p.document_id IS NOT NULL
                      AND NOT EXISTS (SELECT 1 FROM documents d WHERE d.id = p.document_id)
                    LIMIT :limit FOR UPDATE OF c SKIP LOCKED)
                """, params);
        return commands + projections + receipts + changes + scrubOrphanDocumentChanges(limit) + scrubOrphanCitations(limit);
    }

    /**
     * 이 기능 전에 영구 삭제한 문서의 변경 기록. {@link #scrub}과 같은 표를 같은 이유로 지운다. 되돌리기는 값을 그대로 쓰므로 비우지 않고, 문서가 없으면 어차피 되돌릴 수 없다.
     * 끝난 실행만 본다. 실행 행까지 잠가, 겹친 취소가 시작되면 이번에는 건너뛰고 취소가 시작된 뒤에는 대상에서 빠진다. 문서가 있는지만 보므로 휴지통 문서는 남는다.
     * 지울 행이 없어도 문서 기록(V99 인덱스)을 한 번 훑는다. 없는 행을 찾는 조건이라 인덱스로 더 줄일 수 없고, 하루 한 번 도는 작업이다.
     */
    private int scrubOrphanDocumentChanges(int limit) {
        return jdbc.update("""
                DELETE FROM ai_task_changes WHERE id IN (
                    SELECT c.id FROM ai_task_changes c JOIN ai_task_runs t ON t.id = c.run_id
                    WHERE c.%s AND %s IS NOT NULL AND t.%s
                      AND NOT EXISTS (SELECT 1 FROM documents d WHERE d.id = %s)
                    LIMIT :limit FOR UPDATE OF c SKIP LOCKED FOR SHARE OF t SKIP LOCKED)
                """.formatted(DOCUMENT_CHANGE_TABLES, CHANGE_DOCUMENT_ID_OF_C, FINISHED_RUN, CHANGE_DOCUMENT_ID_OF_C),
                new MapSqlParameterSource("limit", limit));
    }

    /** 이 기능 전에 영구 삭제한 문서의 인용. 찾아 둔 id로만 고쳐 묶음 크기를 지킨다. */
    private int scrubOrphanCitations(int limit) {
        return blankFoundCitations(ORPHAN_CITATION, limit, "%s || '{\"quote\": \"\", \"document_id\": null}'::jsonb")
                + blankFoundCitations(ORPHAN_SOURCE_REF_CITATION, limit, "%s || '{\"quote\": \"\"}'::jsonb");
    }

    private int blankFoundCitations(String condition, int limit, String journalValue) {
        List<Long> citations = jdbc.queryForList("SELECT id FROM chat_message_references WHERE " + condition + " LIMIT :limit",
                new MapSqlParameterSource("limit", limit), Long.class);
        if (citations.isEmpty()) {
            return 0;
        }
        return blankCitations("id IN (:citations)", new MapSqlParameterSource("citations", citations), journalValue);
    }
}
