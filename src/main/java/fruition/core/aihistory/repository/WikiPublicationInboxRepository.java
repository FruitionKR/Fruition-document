package fruition.core.aihistory.repository;

import fruition.core.aihistory.dto.WikiPublicationEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class WikiPublicationInboxRepository {

    private final JdbcTemplate jdbc;

    public WikiPublicationInboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Receipt> find(WikiPublicationEvent event) {
        return jdbc.query("""
                SELECT publication_id, operation_id, event_hash, result_hash,
                       published_reference_hash, kind, outcome, workspace_id, user_id, committed_at,
                       disposition, rejection_reason
                FROM wiki_publication_inbox receipt
                WHERE publication_id = ? OR operation_id = ? OR EXISTS (
                    SELECT 1 FROM wiki_publication_event_aliases alias
                    WHERE alias.event_id = ? AND alias.operation_id = receipt.operation_id)
                FOR UPDATE
                """, (rs, row) -> new Receipt(
                        rs.getString("publication_id"), rs.getString("operation_id"), rs.getString("event_hash"),
                        rs.getString("result_hash"), rs.getString("published_reference_hash"),
                        rs.getString("kind"), rs.getString("outcome"),
                        rs.getString("workspace_id"), rs.getString("user_id"),
                        rs.getTimestamp("committed_at").toInstant(),
                        Disposition.valueOf(rs.getString("disposition")),
                        rs.getString("rejection_reason")),
                event.publicationId(), event.operationId(), event.eventId());
    }

    public boolean insert(WikiPublicationEvent event, String eventHash, String resultHash,
                          Disposition disposition, String rejectionReason) {
        return jdbc.update("""
                INSERT INTO wiki_publication_inbox(
                    publication_id, operation_id, event_hash, result_hash,
                    published_reference_hash, kind, outcome, workspace_id, user_id, committed_at,
                    disposition, rejection_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, event.publicationId(), event.operationId(), eventHash, resultHash,
                event.publishedReferenceHash(), event.kind().name(), event.outcome().name(),
                event.workspaceId(), event.userId(), Timestamp.from(event.committedAt()),
                disposition.name(), rejectionReason) == 1;
    }

    public boolean claimEventId(String eventId, String operationId) {
        jdbc.update("""
                INSERT INTO wiki_publication_event_aliases(event_id, operation_id)
                VALUES (?, ?) ON CONFLICT DO NOTHING
                """, eventId, operationId);
        String owner = jdbc.queryForObject(
                "SELECT operation_id FROM wiki_publication_event_aliases WHERE event_id = ?",
                String.class, eventId);
        return operationId.equals(owner);
    }

    public Optional<PublicationReceipt> findReceipt(String workspaceId, String userId,
                                                     String publicationId, String operationId,
                                                     String eventId) {
        return jdbc.query("""
                SELECT receipt.publication_id, receipt.operation_id, alias.event_id,
                       receipt.workspace_id, receipt.user_id, receipt.kind, receipt.outcome,
                       receipt.published_reference_hash, receipt.committed_at,
                       receipt.disposition, receipt.rejection_reason
                FROM wiki_publication_inbox receipt
                JOIN wiki_publication_event_aliases alias
                  ON alias.operation_id = receipt.operation_id
                WHERE receipt.workspace_id = ? AND receipt.user_id = ?
                  AND receipt.publication_id = ? AND receipt.operation_id = ?
                  AND alias.event_id = ?
                """, (rs, row) -> new PublicationReceipt(
                        rs.getString("publication_id"), rs.getString("operation_id"),
                        rs.getString("event_id"), rs.getString("workspace_id"),
                        rs.getString("user_id"), rs.getString("kind"), rs.getString("outcome"),
                        rs.getString("published_reference_hash"),
                        rs.getTimestamp("committed_at").toInstant(),
                        Disposition.valueOf(rs.getString("disposition")),
                        rs.getString("rejection_reason")),
                workspaceId, userId, publicationId, operationId, eventId).stream().findFirst();
    }

    public enum Disposition { ACCEPTED, REJECTED_CONFLICT }

    public record Receipt(String publicationId, String operationId,
                          String eventHash, String resultHash, String publishedReferenceHash,
                          String kind, String outcome, String workspaceId, String userId,
                          Instant committedAt, Disposition disposition, String rejectionReason) {}

    public record PublicationReceipt(String publicationId, String operationId, String eventId,
                                     String workspaceId, String userId, String kind, String outcome,
                                     String publishedReferenceHash, Instant committedAt,
                                     Disposition disposition, String rejectionReason) {}
}
