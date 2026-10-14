package fruition.core.document.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 문서 변환의 batch 발행·결과 상태({@code document_convert_batches}). 호출자 트랜잭션과 queue 행 잠금 안에서 쓴다. */
@Repository
public class DocumentConvertBatchStore {

    public static final String DISPATCHED = "dispatched";
    public static final String READY = "ready";

    public record Batch(long queueId, int startPage, int pageCount, String command, String status,
                        Integer pageEnd, String resultKey) {}

    private static final RowMapper<Batch> MAPPER = (rs, row) -> new Batch(rs.getLong("queue_id"),
            rs.getInt("start_page"), rs.getInt("page_count"), rs.getString("command"), rs.getString("status"),
            (Integer) rs.getObject("page_end"), rs.getString("result_key"));

    private final JdbcTemplate jdbc;

    public DocumentConvertBatchStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertDispatched(long queueId, int startPage, int pageCount, String command) {
        jdbc.update("INSERT INTO document_convert_batches(queue_id, start_page, page_count, command, status) "
                + "VALUES (?, ?, ?, CAST(? AS jsonb), 'dispatched')", queueId, startPage, pageCount, command);
    }

    public Optional<Batch> find(long queueId, int startPage) {
        return jdbc.query("SELECT queue_id, start_page, page_count, command::text AS command, status, page_end, result_key "
                + "FROM document_convert_batches WHERE queue_id = ? AND start_page = ?",
                MAPPER,
                queueId, startPage).stream().findFirst();
    }

    /** 결과가 도착한 batch를 ready로 바꾼다. 이미 ready이거나 없는 행이면 false(중복 이벤트). */
    public boolean markReady(long queueId, int startPage, int pageEnd, String resultKey) {
        return jdbc.update("UPDATE document_convert_batches SET status = 'ready', page_end = ?, result_key = ? "
                + "WHERE queue_id = ? AND start_page = ? AND status = 'dispatched'",
                pageEnd, resultKey, queueId, startPage) == 1;
    }

    public void delete(long queueId, int startPage) {
        jdbc.update("DELETE FROM document_convert_batches WHERE queue_id = ? AND start_page = ?", queueId, startPage);
    }

    /** 발행했지만 아직 병합하지 않은 batch 수(결과 대기 + 순서 대기). */
    public int count(long queueId) {
        return jdbc.queryForObject("SELECT count(*) FROM document_convert_batches WHERE queue_id = ?",
                Integer.class, queueId);
    }

    /** 결과를 기다리는 batch의 발행 command. 취소 요청에 그대로 싣는다. */
    public List<Batch> findDispatched(long queueId) {
        return jdbc.query("SELECT queue_id, start_page, page_count, command::text AS command, status, page_end, result_key "
                + "FROM document_convert_batches WHERE queue_id = ? AND status = 'dispatched' ORDER BY start_page",
                MAPPER,
                queueId);
    }

    /** placeholder 문서의 결과 대기 batch. 변환이 끝났거나 queue 행이 없으면 빈 목록이다. */
    public List<Batch> findDispatchedByDocument(String documentId) {
        return jdbc.query("SELECT b.queue_id, b.start_page, b.page_count, b.command::text AS command, b.status, "
                + "b.page_end, b.result_key FROM document_convert_batches b "
                + "JOIN document_convert_queue q ON q.id = b.queue_id WHERE q.document_id = ? AND b.status = 'dispatched' "
                + "ORDER BY b.start_page",
                MAPPER,
                documentId);
    }
}
