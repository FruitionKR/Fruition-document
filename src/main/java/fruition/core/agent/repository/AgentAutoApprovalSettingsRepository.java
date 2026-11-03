package fruition.core.agent.repository;

import fruition.core.agent.dto.AgentAutoApprovalSettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AgentAutoApprovalSettingsRepository {

    private final JdbcTemplate jdbc;

    public AgentAutoApprovalSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 저장한 적 없으면 모두 꺼짐이다. */
    public AgentAutoApprovalSettings find(String userId) {
        List<AgentAutoApprovalSettings> rows = jdbc.query(
                "SELECT content_edit, organize FROM agent_auto_approval_settings WHERE user_id = ?",
                (rs, rowNum) -> new AgentAutoApprovalSettings(rs.getBoolean("content_edit"), rs.getBoolean("organize")),
                userId);
        return rows.isEmpty() ? AgentAutoApprovalSettings.DISABLED : rows.get(0);
    }

    public void save(String userId, AgentAutoApprovalSettings settings) {
        jdbc.update("""
                INSERT INTO agent_auto_approval_settings (user_id, content_edit, organize, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (user_id) DO UPDATE
                SET content_edit = EXCLUDED.content_edit, organize = EXCLUDED.organize, updated_at = now()
                """, userId, settings.contentEdit(), settings.organize());
    }
}
