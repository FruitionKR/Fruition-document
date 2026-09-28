package fruition.core.meeting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 회의·연결·전사 구간 저장소.
 *
 * <p>한 회의의 구간은 실시간 연결 하나만 쓴다(Redis 잠금). 그래서 position을 {@code max + 1}로 매겨도
 * 경합이 없고, UNIQUE (meeting_id, position)이 최종 방어선이다.
 */
@Repository
public class MeetingRepository {

    public record Meeting(String id, String workspaceId, String createdBy, String displayName,
                          String documentId, String source, String status, Instant createdAt,
                          String recordingKey, String recordingContentType, Long recordingBytes, String error) {

        public Meeting(String id, String workspaceId, String createdBy, String displayName,
                       String documentId, String source, String status, Instant createdAt) {
            this(id, workspaceId, createdBy, displayName, documentId, source, status, createdAt, null, null, null, null);
        }
    }

    public record Stream(String id, int order, String endReason) {}

    public record Segment(String id, int position, String text) {}

    public enum CompleteResult { UPDATED, DUPLICATE, CONFLICT, MISSING }

    private final JdbcTemplate jdbc;

    public MeetingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Meeting meeting) {
        jdbc.update("""
                INSERT INTO meetings (id, workspace_id, created_by, display_name, document_id, source, status,
                                      created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, meeting.id(), meeting.workspaceId(), meeting.createdBy(), meeting.displayName(),
                meeting.documentId(), meeting.source(), meeting.status(),
                Timestamp.from(meeting.createdAt()), Timestamp.from(meeting.createdAt()));
    }

    public Optional<Meeting> findOwned(String id, String workspaceId, String userId) {
        return jdbc.query("SELECT * FROM meetings WHERE id = ? AND workspace_id = ? AND created_by = ?",
                (rs, n) -> meeting(rs), id, workspaceId, userId).stream().findFirst();
    }

    public Optional<Meeting> findById(String id) {
        return jdbc.query("SELECT * FROM meetings WHERE id = ?", (rs, n) -> meeting(rs), id).stream().findFirst();
    }

    public Stream openStream(String meetingId) {
        String id = "stream_" + UUID.randomUUID().toString().replace("-", "");
        Integer order = jdbc.queryForObject("""
                INSERT INTO meeting_streams (id, meeting_id, stream_order, started_at)
                SELECT ?, ?, COALESCE(MAX(stream_order), 0) + 1, now() FROM meeting_streams WHERE meeting_id = ?
                RETURNING stream_order
                """, Integer.class, id, meetingId, meetingId);
        return new Stream(id, order, null);
    }

    /** 이미 기록된 종료 사유는 바꾸지 않는다(오류로 닫은 연결이 뒤늦게 interrupted로 덮이지 않게). */
    public void endStream(String streamId, String reason) {
        jdbc.update("UPDATE meeting_streams SET end_reason = ?, ended_at = now() WHERE id = ? AND end_reason IS NULL",
                reason, streamId);
    }

    public List<Stream> streams(String meetingId) {
        return jdbc.query("SELECT id, stream_order, end_reason FROM meeting_streams WHERE meeting_id = ? ORDER BY stream_order",
                (rs, n) -> new Stream(rs.getString("id"), rs.getInt("stream_order"), rs.getString("end_reason")),
                meetingId);
    }

    /** committed 순서대로 position을 매긴다. 같은 구간이 다시 오면 기존 position을 돌려준다. */
    public int register(String meetingId, String segmentId, String streamId) {
        List<Integer> existing = jdbc.queryForList(
                "SELECT position FROM meeting_segments WHERE meeting_id = ? AND id = ?", Integer.class, meetingId, segmentId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return jdbc.queryForObject("""
                INSERT INTO meeting_segments (meeting_id, id, stream_id, position, created_at)
                SELECT ?, ?, ?, COALESCE(MAX(position), 0) + 1, now() FROM meeting_segments WHERE meeting_id = ?
                RETURNING position
                """, Integer.class, meetingId, segmentId, streamId, meetingId);
    }

    public CompleteResult complete(String meetingId, String segmentId, String text) {
        if (jdbc.update("UPDATE meeting_segments SET text = ? WHERE meeting_id = ? AND id = ? AND text IS NULL",
                text, meetingId, segmentId) == 1) {
            return CompleteResult.UPDATED;
        }
        List<String> saved = jdbc.queryForList(
                "SELECT text FROM meeting_segments WHERE meeting_id = ? AND id = ?", String.class, meetingId, segmentId);
        if (saved.isEmpty()) {
            return CompleteResult.MISSING;
        }
        return text.equals(saved.get(0)) ? CompleteResult.DUPLICATE : CompleteResult.CONFLICT;
    }

    public int completedCount(String streamId) {
        return jdbc.queryForObject("SELECT count(*) FROM meeting_segments WHERE stream_id = ? AND text IS NOT NULL",
                Integer.class, streamId);
    }

    /** [구간 수, 확정 글자 수]. 회의 전체 한도 검사용이다. */
    public long[] totals(String meetingId) {
        return jdbc.queryForObject(
                "SELECT count(*), COALESCE(SUM(length(text)), 0) FROM meeting_segments WHERE meeting_id = ?",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}, meetingId);
    }

    public List<Segment> segments(String meetingId) {
        return jdbc.query("SELECT id, position, text FROM meeting_segments WHERE meeting_id = ? ORDER BY position",
                (rs, n) -> new Segment(rs.getString("id"), rs.getInt("position"), rs.getString("text")), meetingId);
    }

    /** 원본을 기록하고 상태를 바꾼다. 허용 상태가 아니면(동시 요청 등) false. */
    public boolean saveRecording(String id, String key, String contentType, long bytes,
                                 List<String> allowedStatuses, String nextStatus) {
        return jdbc.update("""
                UPDATE meetings SET recording_key = ?, recording_content_type = ?, recording_bytes = ?,
                       status = ?, error = NULL, claimed_at = NULL, updated_at = now()
                WHERE id = ? AND status = ANY(?)
                """, key, contentType, bytes, nextStatus, id, allowedStatuses.toArray(String[]::new)) == 1;
    }

    /** 대기 중이거나 선점이 오래된 전사 한 건을 잡는다. 여러 Pod가 같은 건을 동시에 잡지 않는다. */
    public Optional<Meeting> claimTranscription(Instant staleBefore) {
        return jdbc.query("""
                UPDATE meetings SET claimed_at = now()
                WHERE id = (SELECT id FROM meetings
                            WHERE status = 'transcribing' AND (claimed_at IS NULL OR claimed_at < ?)
                            ORDER BY updated_at LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING *
                """, (rs, n) -> meeting(rs), Timestamp.from(staleBefore)).stream().findFirst();
    }

    /** 전사 결과를 연결 하나(정상 종료)와 구간들로 저장하고 회의를 연다. 그사이 삭제·재업로드됐으면 저장하지 않는다. */
    @org.springframework.transaction.annotation.Transactional
    public boolean completeTranscription(String id, String recordingKey, List<String> sentences) {
        if (jdbc.update("""
                UPDATE meetings SET status = 'open', error = NULL, claimed_at = NULL, updated_at = now()
                WHERE id = ? AND status = 'transcribing' AND recording_key = ?
                """, id, recordingKey) != 1) {
            return false;
        }
        Stream stream = openStream(id);
        endStream(stream.id(), "finished");
        for (int i = 0; i < sentences.size(); i++) {
            String segmentId = "s" + stream.order() + "_seg_" + String.format("%04d", i + 1);
            register(id, segmentId, stream.id());
            complete(id, segmentId, sentences.get(i));
        }
        return true;
    }

    public void failTranscription(String id, String recordingKey, String error) {
        jdbc.update("""
                UPDATE meetings SET status = 'failed', error = ?, claimed_at = NULL, updated_at = now()
                WHERE id = ? AND status = 'transcribing' AND recording_key = ?
                """, error, id, recordingKey);
    }

    /** 연결·구간·회의록 초안은 FK cascade로 함께 지워진다. */
    public void delete(String id) {
        jdbc.update("DELETE FROM meetings WHERE id = ?", id);
    }

    private static Meeting meeting(java.sql.ResultSet rs) throws java.sql.SQLException {
        long bytes = rs.getLong("recording_bytes");
        return new Meeting(rs.getString("id"), rs.getString("workspace_id"), rs.getString("created_by"),
                rs.getString("display_name"), rs.getString("document_id"), rs.getString("source"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("recording_key"), rs.getString("recording_content_type"),
                rs.wasNull() ? null : bytes, rs.getString("error"));
    }
}
