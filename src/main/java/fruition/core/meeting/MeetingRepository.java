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
                          String recordingKey, String recordingContentType, Long recordingBytes, String error,
                          String transcriptionRunId) {

        public Meeting(String id, String workspaceId, String createdBy, String displayName,
                       String documentId, String source, String status, Instant createdAt) {
            this(id, workspaceId, createdBy, displayName, documentId, source, status, createdAt, null, null, null, null, null);
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

    /** 목록 한 줄. 전사 구간 같은 무거운 내용은 읽지 않는다. */
    public record Summary(String id, String displayName, String source, String status,
                          boolean hasRecording, boolean hasNotes, Instant createdAt, Instant updatedAt) {}

    /**
     * 만든 사람의 회의를 최근 생성 순으로 읽는다. {@code cursorCreatedAt}이 있으면 그 (created_at, id)보다 뒤의 것만 준다.
     * 회의록은 초안이 만들어졌거나(ready) 저장된(applied) 버전이 있으면 있는 것으로 본다.
     */
    public List<Summary> findOwnedPage(String workspaceId, String userId, List<String> statuses,
                                       Instant cursorCreatedAt, String cursorId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.display_name, m.source, m.status, m.recording_key IS NOT NULL AS has_recording,
                       EXISTS (SELECT 1 FROM meeting_notes n
                               WHERE n.meeting_id = m.id AND n.status IN ('ready', 'applied')) AS has_notes,
                       m.created_at, m.updated_at
                FROM meetings m
                WHERE m.workspace_id = ? AND m.created_by = ?
                """);
        List<Object> args = new java.util.ArrayList<>(List.of(workspaceId, userId));
        if (!statuses.isEmpty()) {
            sql.append(" AND m.status IN (").append(String.join(", ", java.util.Collections.nCopies(statuses.size(), "?")))
                    .append(')');
            args.addAll(statuses);
        }
        if (cursorCreatedAt != null) {
            sql.append(" AND (m.created_at, m.id) < (?, ?)");
            args.add(Timestamp.from(cursorCreatedAt));
            args.add(cursorId);
        }
        sql.append(" ORDER BY m.created_at DESC, m.id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, n) -> new Summary(rs.getString("id"), rs.getString("display_name"),
                rs.getString("source"), rs.getString("status"), rs.getBoolean("has_recording"),
                rs.getBoolean("has_notes"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), args.toArray());
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

    /** 원본 교체 결과. {@code previousKey}는 교체되기 전 원본(없으면 null)이다. */
    public record RecordingSwap(String previousKey) {}

    /**
     * 원본을 기록하고 상태를 바꾼다. 행을 잠근 뒤 허용 상태인지(그리고 {@code requireNoRecording}이면 원본이 없는지)
     * 확인하므로 겹친 요청 중 하나만 성공한다. {@code transcriptionRunId}는 전사를 시작할 때만 넘기고, 아니면 null이다. 실패하면 빈 값, 성공하면 교체 전 원본 키(없으면 null)를 돌려준다.
     */
    @org.springframework.transaction.annotation.Transactional
    public Optional<RecordingSwap> saveRecording(String id, String key, String contentType, long bytes,
                                                 List<String> allowedStatuses, String nextStatus,
                                                 boolean requireNoRecording, String transcriptionRunId) {
        List<String[]> current = jdbc.query("SELECT status, recording_key FROM meetings WHERE id = ? FOR UPDATE",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, id);
        if (current.isEmpty() || !allowedStatuses.contains(current.get(0)[0])
                || (requireNoRecording && current.get(0)[1] != null)) {
            return Optional.empty();
        }
        jdbc.update("""
                UPDATE meetings SET recording_key = ?, recording_content_type = ?, recording_bytes = ?,
                       status = ?, error = NULL, transcription_run_id = ?, updated_at = now()
                WHERE id = ?
                """, key, contentType, bytes, nextStatus, transcriptionRunId, id);
        return Optional.of(new RecordingSwap(current.get(0)[1]));
    }

    /** 전사 결과를 연결 하나(정상 종료)와 구간들로 저장하고 회의를 연다. 그사이 취소·재업로드로 run이 바뀌었으면 저장하지 않는다. */
    @org.springframework.transaction.annotation.Transactional
    public boolean completeTranscription(String id, String runId, List<String> sentences) {
        if (jdbc.update("""
                UPDATE meetings SET status = 'open', error = NULL, updated_at = now()
                WHERE id = ? AND status = 'transcribing' AND transcription_run_id = ?
                """, id, runId) != 1) {
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

    /** @return 이 run의 전사 중이던 회의를 실패로 바꿨으면 true */
    public boolean failTranscription(String id, String runId, String error) {
        return jdbc.update("""
                UPDATE meetings SET status = 'failed', error = ?, updated_at = now()
                WHERE id = ? AND status = 'transcribing' AND transcription_run_id = ?
                """, error, id, runId) == 1;
    }

    /**
     * 원본 키가 호출자가 지운 키와 같을 때만 회의를 지운다(키가 없으면 NULL끼리 비교). 그사이 재업로드로 키가 바뀌었거나
     * 전사가 시작됐으면 0을 돌려주고 행을 남긴다. 연결·구간·회의록 초안은 FK cascade로 함께 지워진다.
     */
    public int deleteIfUnchanged(String id, String recordingKey) {
        return jdbc.update("""
                DELETE FROM meetings
                WHERE id = ? AND recording_key IS NOT DISTINCT FROM ? AND status <> 'transcribing'
                """, id, recordingKey);
    }

    private static Meeting meeting(java.sql.ResultSet rs) throws java.sql.SQLException {
        long bytes = rs.getLong("recording_bytes");
        return new Meeting(rs.getString("id"), rs.getString("workspace_id"), rs.getString("created_by"),
                rs.getString("display_name"), rs.getString("document_id"), rs.getString("source"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("recording_key"), rs.getString("recording_content_type"),
                rs.wasNull() ? null : bytes, rs.getString("error"), rs.getString("transcription_run_id"));
    }
}
