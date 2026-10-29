package fruition.core.meeting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 회의록 초안 버전 저장소. 각 호출은 짧은 단독 트랜잭션이다(AI 호출을 트랜잭션 밖에 두기 위해). */
@Repository
public class MeetingNotesRepository {

    public record Note(String meetingId, int version, String generationRequestId, String status, boolean partial,
                       String result, String errorCode, String applyRequestId, String applyMode,
                       String applyDocumentId, Long applyBaseRevision, String applyDisplayName, UUID applyFolderId,
                       String applyMarkdown, Instant appliedAt, Instant updatedAt, String aiRunId,
                       String segmentSnapshot) {}

    private final JdbcTemplate jdbc;

    public MeetingNotesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 같은 생성 요청 ID가 이미 있거나, 다른 요청과 같은 버전 번호를 동시에 잡으면 빈 값을 돌려준다.
     * 호출자는 요청 ID로 다시 조회해 재시도인지 동시 생성인지 구분한다.
     */
    public Optional<Integer> insertGenerating(String meetingId, String requestId, boolean partial, String snapshotJson,
                                          String aiRunId) {
        return jdbc.queryForList("""
                INSERT INTO meeting_notes (meeting_id, version, generation_request_id, status, partial, segment_snapshot,
                                           ai_run_id, created_at, updated_at)
                SELECT ?, COALESCE(MAX(version), 0) + 1, ?, 'generating', ?, ?::jsonb, ?, now(), now()
                FROM meeting_notes WHERE meeting_id = ?
                ON CONFLICT DO NOTHING
                RETURNING version
                """, Integer.class, meetingId, requestId, partial, snapshotJson, aiRunId, meetingId).stream().findFirst();
    }

    public boolean markReady(String meetingId, int version, String resultJson) {
        return jdbc.update("""
                UPDATE meeting_notes SET status = 'ready', result = ?::jsonb, error_code = NULL, updated_at = now()
                WHERE meeting_id = ? AND version = ? AND status = 'generating'
                """, resultJson, meetingId, version) == 1;
    }

    public boolean markFailed(String meetingId, int version, String errorCode) {
        return jdbc.update("""
                UPDATE meeting_notes SET status = 'failed', error_code = ?, updated_at = now()
                WHERE meeting_id = ? AND version = ? AND status = 'generating'
                """, errorCode, meetingId, version) == 1;
    }

    /** 한 워크스페이스의 회의록 생성을 직렬화한다. 같은 트랜잭션에서 busy 확인과 insertGenerating을 하는 호출자가 쥔다. */
    public void lockWorkspace(String workspaceId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "meeting-notes-generation:" + workspaceId);
    }

    public boolean hasGenerating(String workspaceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM meeting_notes n JOIN meetings m ON m.id = n.meeting_id
                               WHERE m.workspace_id = ? AND n.status = 'generating')
                """, Boolean.class, workspaceId));
    }

    /** AI 결과 이벤트가 끝내 오지 않으면 generating이 남는다. 오래된 것은 실패로 정리하고 ai run ID를 돌려준다. workspaceId가 null이면 전체. */
    public List<String> expireGenerating(String workspaceId, Instant before) {
        return jdbc.query("""
                UPDATE meeting_notes n SET status = 'failed', error_code = 'MEETING_NOTES_TIMEOUT', updated_at = now()
                FROM meetings m
                WHERE m.id = n.meeting_id AND (?::text IS NULL OR m.workspace_id = ?) AND n.status = 'generating' AND n.updated_at < ?
                RETURNING n.ai_run_id
                """, (rs, i) -> rs.getString(1), workspaceId, workspaceId, java.sql.Timestamp.from(before))
                .stream().filter(java.util.Objects::nonNull).toList();
    }

    /** 사용자가 취소한 generating 초안을 실패로 바꾼다. 없으면 빈 값. */
    public Optional<Integer> cancelGenerating(String meetingId) {
        return jdbc.queryForList("""
                UPDATE meeting_notes SET status = 'failed', error_code = 'MEETING_NOTES_CANCELLED', updated_at = now()
                WHERE meeting_id = ? AND status = 'generating'
                RETURNING version
                """, Integer.class, meetingId).stream().findFirst();
    }

    /** 저장 전에 대상·본문을 남긴다. 다른 요청이 먼저 잡았으면 false. */
    public boolean claimApply(String meetingId, int version, String requestId, String mode, String documentId,
                              Long baseRevision, String displayName, UUID folderId, String markdown) {
        return jdbc.update("""
                UPDATE meeting_notes SET apply_request_id = ?, apply_mode = ?, apply_document_id = ?,
                       apply_base_revision = ?, apply_display_name = ?, apply_folder_id = ?, apply_markdown = ?,
                       updated_at = now()
                WHERE meeting_id = ? AND version = ? AND status = 'ready' AND apply_request_id IS NULL
                """, requestId, mode, documentId, baseRevision, displayName, folderId, markdown, meetingId, version) == 1;
    }

    /** 문서가 바뀌지 않은 거절(충돌·권한 등)이면 선점을 풀어 새 요청으로 다시 저장할 수 있게 한다. */
    public void releaseApply(String meetingId, int version, String requestId) {
        jdbc.update("""
                UPDATE meeting_notes SET apply_request_id = NULL, apply_mode = NULL, apply_document_id = NULL,
                       apply_base_revision = NULL, apply_display_name = NULL, apply_folder_id = NULL,
                       apply_markdown = NULL, updated_at = now()
                WHERE meeting_id = ? AND version = ? AND apply_request_id = ? AND status = 'ready'
                """, meetingId, version, requestId);
    }

    public void markApplied(String meetingId, int version, String requestId, String documentId) {
        jdbc.update("""
                UPDATE meeting_notes SET status = 'applied', apply_document_id = ?, applied_at = COALESCE(applied_at, now()),
                       updated_at = now()
                WHERE meeting_id = ? AND version = ? AND apply_request_id = ?
                """, documentId, meetingId, version, requestId);
    }

    public Optional<Note> find(String meetingId, int version) {
        return jdbc.query("SELECT * FROM meeting_notes WHERE meeting_id = ? AND version = ?",
                (rs, n) -> note(rs), meetingId, version).stream().findFirst();
    }

    public Optional<Note> findByRequest(String meetingId, String requestId) {
        return jdbc.query("SELECT * FROM meeting_notes WHERE meeting_id = ? AND generation_request_id = ?",
                (rs, n) -> note(rs), meetingId, requestId).stream().findFirst();
    }

    public Optional<Note> findByRun(String aiRunId) {
        return jdbc.query("SELECT * FROM meeting_notes WHERE ai_run_id = ?", (rs, n) -> note(rs), aiRunId)
                .stream().findFirst();
    }

    public Optional<Note> latest(String meetingId) {
        return jdbc.query("SELECT * FROM meeting_notes WHERE meeting_id = ? ORDER BY version DESC LIMIT 1",
                (rs, n) -> note(rs), meetingId).stream().findFirst();
    }

    public Optional<Integer> lastReadyVersion(String meetingId) {
        List<Integer> versions = jdbc.queryForList(
                "SELECT MAX(version) FROM meeting_notes WHERE meeting_id = ? AND status IN ('ready', 'applied')",
                Integer.class, meetingId);
        return versions.stream().filter(java.util.Objects::nonNull).findFirst();
    }

    private static Note note(ResultSet rs) throws SQLException {
        Long base = rs.getObject("apply_base_revision") == null ? null : rs.getLong("apply_base_revision");
        java.sql.Timestamp applied = rs.getTimestamp("applied_at");
        return new Note(rs.getString("meeting_id"), rs.getInt("version"), rs.getString("generation_request_id"),
                rs.getString("status"), rs.getBoolean("partial"), rs.getString("result"), rs.getString("error_code"),
                rs.getString("apply_request_id"), rs.getString("apply_mode"), rs.getString("apply_document_id"), base,
                rs.getString("apply_display_name"), rs.getObject("apply_folder_id", UUID.class),
                rs.getString("apply_markdown"), applied == null ? null : applied.toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("ai_run_id"), rs.getString("segment_snapshot"));
    }
}
