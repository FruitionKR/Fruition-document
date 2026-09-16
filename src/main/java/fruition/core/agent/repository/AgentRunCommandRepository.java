package fruition.core.agent.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** core가 소유하는 Markdown Agent 적용 예약 projection을 기록한다. */
@Repository
public class AgentRunCommandRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentRunCommandRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void create(String runId, String workspaceId, String userId, String documentId,
                       Long baseVersion, String applyOperationId) {
        jdbcTemplate.update("""
                INSERT INTO agent_apply_projections (
                    run_id, workspace_id, user_id, document_id, base_version,
                    apply_operation_id, status
                ) VALUES (?, ?, ?, ?, ?, ?, 'queued')
                """, runId, workspaceId, userId, documentId, baseVersion, applyOperationId);
    }

    /** 승인된 autonomous Agent Tool의 문서 편집을 기존 적용 표·감사 경계에 연결한다. */
    public void prepareToolApply(String projectionId, String workspaceId, String userId,
                                 String documentId, long baseVersion, String applyOperationId,
                                 String markdown) {
        jdbcTemplate.update("""
                INSERT INTO agent_apply_projections (
                    run_id, workspace_id, user_id, document_id, base_version,
                    apply_operation_id, status, ready_markdown, autonomous_tool
                ) VALUES (?, ?, ?, ?, ?, ?, 'ready', ?, true)
                ON CONFLICT (run_id) DO NOTHING
                """, projectionId, workspaceId, userId, documentId, baseVersion,
                applyOperationId, markdown);
    }

    public Optional<RunView> find(String workspaceId, String userId, String runId) {
        return jdbcTemplate.query("""
                SELECT run_id, document_id, base_version, apply_operation_id, status, result, error_code
                FROM agent_apply_projections
                WHERE run_id = ? AND workspace_id = ? AND user_id = ?
                """, rs -> rs.next() ? Optional.of(new RunView(
                        rs.getString("run_id"), rs.getString("document_id"), rs.getLong("base_version"),
                        rs.getString("apply_operation_id"), rs.getString("status"),
                        readResult(rs.getString("result")), rs.getString("error_code"))) : Optional.empty(),
                runId, workspaceId, userId);
    }

    private JsonNode readResult(String value) {
        if (value == null) return null;
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Agent 결과를 읽지 못했습니다.", e);
        }
    }

    public record RunView(String runId, String documentId, long baseVersion,
                          String applyOperationId, String status, JsonNode result, String error) {}
}
