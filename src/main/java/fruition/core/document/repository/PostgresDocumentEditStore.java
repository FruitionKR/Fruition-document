package fruition.core.document.repository;

import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.shared.idempotency.IdempotencyConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class PostgresDocumentEditStore {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final BeforeStateWrite beforeStateWrite;

    @Autowired
    public PostgresDocumentEditStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this(jdbcTemplate, transactionManager, changed -> { });
    }

    PostgresDocumentEditStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            BeforeStateWrite beforeStateWrite
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.beforeStateWrite = beforeStateWrite;
    }

    public Optional<DocumentEditState> findState(String documentId) {
        return jdbcTemplate.query("""
                SELECT document_id, markdown, content_hash, revision, created_at, updated_at
                FROM document_edit_states
                WHERE document_id = ?
                """, rs -> rs.next()
                ? Optional.of(new DocumentEditState(
                        rs.getString("document_id"), rs.getString("markdown"),
                        rs.getString("content_hash"), rs.getLong("revision"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                : Optional.empty(), documentId);
    }

    public PostgresDocumentEditSaveResult save(
            String workspaceId,
            String documentId,
            String markdown,
            String contentHash,
            long baseRevision,
            String revisionWriteId,
            String actorUserId,
            String applyOperationId
    ) {
        return transactionTemplate.execute(status -> saveInTransaction(
                workspaceId, documentId, markdown, contentHash, baseRevision,
                revisionWriteId, actorUserId, applyOperationId));
    }

    private PostgresDocumentEditSaveResult saveInTransaction(
            String workspaceId,
            String documentId,
            String markdown,
            String contentHash,
            long baseRevision,
            String revisionWriteId,
            String actorUserId,
            String applyOperationId
    ) {
        Map<String, Object> state = jdbcTemplate.query("""
                SELECT markdown, content_hash, revision
                FROM document_edit_states
                WHERE document_id = ?
                """, rs -> rs.next() ? Map.of(
                "markdown", rs.getString("markdown"),
                "contentHash", rs.getString("content_hash"),
                "revision", rs.getLong("revision")) : null, documentId);
        if (state == null) {
            throw new DocumentVersionConflictException("문서 편집 버전이 일치하지 않습니다.");
        }

        String requestHash = requestHash(baseRevision, contentHash, applyOperationId);
        Map<String, Object> existing = jdbcTemplate.query("""
                SELECT request_hash, result_revision, result_content_hash, result_updated_at,
                       actor_user_id, changed
                FROM document_edit_writes
                WHERE document_id = ? AND revision_write_id = ?
                """, rs -> rs.next() ? Map.of(
                "requestHash", rs.getString("request_hash"),
                "revision", rs.getLong("result_revision"),
                "contentHash", rs.getString("result_content_hash"),
                "updatedAt", rs.getTimestamp("result_updated_at").toInstant(),
                "actorUserId", rs.getString("actor_user_id"),
                "changed", rs.getBoolean("changed")) : null, documentId, revisionWriteId);
        if (existing != null) {
            return replayOrConflict(existing, requestHash, baseRevision);
        }

        long currentRevision = (long) state.get("revision");
        String baseMarkdown = (String) state.get("markdown");
        String baseContentHash = (String) state.get("contentHash");
        if (currentRevision != baseRevision) {
            throw new DocumentVersionConflictException("문서 편집 버전이 일치하지 않습니다.");
        }

        Instant writeAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        boolean changed = !Objects.equals(baseContentHash, contentHash);
        long resultRevision = changed ? baseRevision + 1 : baseRevision;
        beforeStateWrite.run(changed);
        int updated = changed
                ? jdbcTemplate.update("""
                        UPDATE document_edit_states
                        SET markdown = ?, content_hash = ?, revision = ?, updated_at = ?
                        WHERE document_id = ? AND revision = ?
                        """, markdown, contentHash, resultRevision, Timestamp.from(writeAt), documentId, baseRevision)
                : jdbcTemplate.update("""
                        UPDATE document_edit_states
                        SET updated_at = updated_at
                        WHERE document_id = ? AND revision = ? AND content_hash = ?
                        """, documentId, baseRevision, contentHash);
        if (updated != 1) {
            existing = jdbcTemplate.query("""
                    SELECT request_hash, result_revision, result_content_hash, result_updated_at,
                           actor_user_id, changed
                    FROM document_edit_writes
                    WHERE document_id = ? AND revision_write_id = ?
                    """, rs -> rs.next() ? Map.of(
                    "requestHash", rs.getString("request_hash"),
                    "revision", rs.getLong("result_revision"),
                    "contentHash", rs.getString("result_content_hash"),
                    "updatedAt", rs.getTimestamp("result_updated_at").toInstant(),
                    "actorUserId", rs.getString("actor_user_id"),
                    "changed", rs.getBoolean("changed")) : null, documentId, revisionWriteId);
            if (existing != null) {
                return replayOrConflict(existing, requestHash, baseRevision);
            }
            throw new DocumentVersionConflictException("문서 편집 버전이 일치하지 않습니다.");
        }

        jdbcTemplate.update("""
                    INSERT INTO document_edit_writes(
                        document_id, revision_write_id, request_hash, result_revision,
                        result_content_hash, result_updated_at, actor_user_id, changed, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, documentId, revisionWriteId, requestHash, resultRevision,
                    contentHash, Timestamp.from(writeAt), actorUserId, changed, Timestamp.from(writeAt));
        if (changed) {
            jdbcTemplate.update("""
                        INSERT INTO document_edit_outbox(
                            event_id, document_id, workspace_id, revision, content_hash,
                            event_type, schema_version, created_at, published
                        ) VALUES (?, ?, ?, ?, ?, 'document.edit.saved.v1', 1, ?, false)
                        """, eventId(documentId, revisionWriteId), documentId, workspaceId,
                        resultRevision, contentHash, Timestamp.from(writeAt));
        }
        return new PostgresDocumentEditSaveResult(
                baseRevision, baseMarkdown, baseContentHash, resultRevision,
                contentHash, changed ? writeAt : jdbcTemplate.queryForObject(
                        "SELECT updated_at FROM document_edit_states WHERE document_id = ?",
                        Instant.class, documentId), actorUserId, changed, false);
    }

    private String requestHash(long baseRevision, String contentHash, String applyOperationId) {
        String payload = baseRevision + ":" + contentHash + ":" + (applyOperationId == null ? "" : applyOperationId);
        return sha256(payload);
    }

    private String eventId(String documentId, String revisionWriteId) {
        int documentByteLength = documentId.getBytes(StandardCharsets.UTF_8).length;
        return sha256(documentByteLength + ":" + documentId + revisionWriteId);
    }

    private PostgresDocumentEditSaveResult replayOrConflict(
            Map<String, Object> existing, String requestHash, long baseRevision) {
        if (!Objects.equals(existing.get("requestHash"), requestHash)) {
            throw new IdempotencyConflictException(
                    "같은 revision_write_id를 다른 저장 요청에 사용할 수 없습니다.");
        }
        return new PostgresDocumentEditSaveResult(
                baseRevision, null, null, (long) existing.get("revision"),
                (String) existing.get("contentHash"), (Instant) existing.get("updatedAt"),
                (String) existing.get("actorUserId"), (boolean) existing.get("changed"), true);
    }

    private String sha256(String payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("편집 요청 hash를 계산하지 못했습니다.", exception);
        }
    }

    @FunctionalInterface
    interface BeforeStateWrite {
        void run(boolean changed);
    }
}
