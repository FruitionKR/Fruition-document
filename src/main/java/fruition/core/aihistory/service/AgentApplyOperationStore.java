package fruition.core.aihistory.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * AI 편집안에 붙는 일회용 적용 표. Agent turn에서 발급하고 저장 요청에서 소비한다.
 *
 * <p>{@code source=agent} 문자열은 클라이언트가 임의로 넣을 수 있어 수동 편집을 AI 작업으로
 * 위장할 수 있다. Backend가 발급한 값을 대조해야 로그가 오염되지 않는다.
 *
 * <p>표는 core의 좁은 적용 projection에 저장해 서버 재시작과 다중 인스턴스에서도 유지한다.
 */
@Component
public class AgentApplyOperationStore {

    private final JdbcTemplate jdbcTemplate;

    public AgentApplyOperationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 편집안 하나에 대한 적용 표를 발급한다. */
    public String newOperationId() {
        return "op_" + randomSuffix();
    }

    /**
     * 표를 확인하고 소비한다. 같은 표로 두 번 기록되지 않도록 조회와 동시에 제거한다.
     *
     * @return 이 사용자·문서에 발급된 유효한 표면 {@code true}
     */
    @org.springframework.transaction.annotation.Transactional
    public boolean consume(String operationId, String userId, String documentId, String revisionWriteId) {
        if (operationId == null || operationId.isBlank()
                || revisionWriteId == null || revisionWriteId.isBlank() || revisionWriteId.length() > 255) {
            return false;
        }
        if (!authorizeSave(operationId, userId, documentId)) return false;
        if (jdbcTemplate.update("""
                UPDATE agent_apply_projections
                SET status = 'consumed', apply_revision_write_id = ?, apply_consumed_at = now(), updated_at = now()
                WHERE apply_operation_id = ?
                  AND user_id = ?
                  AND document_id = ?
                  AND status = 'ready'
                  AND apply_consumed_at IS NULL
                """, revisionWriteId, operationId, userId, documentId) == 1) {
            return true;
        }
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM agent_apply_projections
                    WHERE apply_operation_id = ?
                      AND user_id = ?
                      AND document_id = ?
                      AND status = 'consumed'
                      AND apply_consumed_at IS NOT NULL
                      AND apply_revision_write_id = ?
                )
                """, Boolean.class, operationId, userId, documentId, revisionWriteId));
    }

    @org.springframework.transaction.annotation.Transactional
    public boolean consume(String operationId, String userId, String documentId, String revisionWriteId,
                           long baseRevision, String markdown) {
        if (operationId == null || operationId.isBlank()
                || revisionWriteId == null || revisionWriteId.isBlank() || revisionWriteId.length() > 255
                || markdown == null) {
            return false;
        }
        if (!authorizeSave(operationId, userId, documentId)) return false;
        if (jdbcTemplate.update("""
                UPDATE agent_apply_projections
                SET status = 'consumed', apply_revision_write_id = ?, apply_consumed_at = now(), updated_at = now()
                WHERE apply_operation_id = ?
                  AND user_id = ?
                  AND document_id = ?
                  AND base_version = ?
                  AND ready_markdown = ?
                  AND status = 'ready'
                  AND apply_consumed_at IS NULL
                """, revisionWriteId, operationId, userId, documentId, baseRevision, markdown) == 1) {
            return true;
        }
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM agent_apply_projections
                    WHERE apply_operation_id = ?
                      AND user_id = ?
                      AND document_id = ?
                      AND base_version = ?
                      AND ready_markdown = ?
                      AND status = 'consumed'
                      AND apply_consumed_at IS NOT NULL
                      AND apply_revision_write_id = ?
                )
                """, Boolean.class, operationId, userId, documentId, baseRevision, markdown, revisionWriteId));
    }

    public boolean authorizeSave(String operationId, String userId, String documentId) {
        var tools = jdbcTemplate.queryForList("SELECT autonomous_tool FROM agent_apply_projections "
                + "WHERE apply_operation_id = ? AND user_id = ? AND document_id = ?", Boolean.class, operationId, userId, documentId);
        if (!tools.isEmpty() && Boolean.TRUE.equals(tools.getFirst())) return true;
        var tasks = jdbcTemplate.queryForList("""
                SELECT task.id FROM ai_task_runs task JOIN agent_apply_projections projection ON projection.run_id = task.id
                WHERE projection.apply_operation_id = ? AND projection.user_id = ? AND projection.document_id = ?
                AND task.status IN ('running', 'completed') FOR UPDATE OF task
                """, String.class, operationId, userId, documentId);
        if (tasks.isEmpty()) return false;
        jdbcTemplate.queryForObject("SELECT set_config('app.ai_task_run_id', ?, true)", String.class, tasks.getFirst());
        return true;
    }

    private String randomSuffix() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
