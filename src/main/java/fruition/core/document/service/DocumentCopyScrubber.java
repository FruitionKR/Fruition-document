package fruition.core.document.service;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 영구 삭제한 문서의 본문 복사본을 core_db에서 지운다. 행은 이력으로 남기고 본문 값만 지운다.
 *
 * <p>{@link #scrub}은 문서 행을 지우는 트랜잭션 안에서 돈다. 표현식 인덱스(V98~V100)로 그 문서의 행만 잠그므로 큰 표를 훑지 않는다.
 *
 * <p>진행 중인 실행은 건드리지 않는다. 취소가 원본 command의 해시로 AI에 등록하고, 늦게 온 결과가 projection에 본문을 쓸 수 있어서다.
 * 끝난 뒤 {@link #scrubOrphans}가 "문서가 더 없는 행"을 찾아 지운다. 기억해 둘 대기열이 없어 이 기능 전에 지운 문서도 함께 정리된다.
 * 다시 취소를 요청할 수 있는 끝난 실행은 {@code document_purged} 표시를 남겨 AI가 해시 대조를 건너뛰게 한다.
 *
 * <p>사용자 메시지(conversation_context, message)와 채팅 기록은 범위 밖이라 지우지 않는다.
 */
@Component
public class DocumentCopyScrubber {

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
    private static final String DOCUMENT_CHANGE_TABLES =
            "table_name IN ('documents', 'document_content_versions', 'document_edit_states', 'document_edit_writes')";

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
        return commands + projections;
    }
}
