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
        return save(workspaceId, documentId, markdown, contentHash, baseRevision,
                revisionWriteId, actorUserId, applyOperationId, null);
    }

    /**
     * @param staleBaseResolver 기준 revision이 현재와 다를 때 저장할 본문을 정한다. null이거나 null을 돌려주면
     *                          {@link DocumentVersionConflictException}으로 거절한다. 멱등 판정(request hash)은
     *                          병합 전 요청 본문으로 하므로 같은 요청의 재전송은 처음 결과를 그대로 돌려받는다.
     */
    public PostgresDocumentEditSaveResult save(
            String workspaceId,
            String documentId,
            String markdown,
            String contentHash,
            long baseRevision,
            String revisionWriteId,
            String actorUserId,
            String applyOperationId,
            StaleBaseResolver staleBaseResolver
    ) {
        return transactionTemplate.execute(status -> saveInTransaction(
                workspaceId, documentId, markdown, contentHash, baseRevision,
                revisionWriteId, actorUserId, applyOperationId, staleBaseResolver));
    }

    private PostgresDocumentEditSaveResult saveInTransaction(
            String workspaceId,
            String documentId,
            String markdown,
            String contentHash,
            long baseRevision,
            String revisionWriteId,
            String actorUserId,
            String applyOperationId,
            StaleBaseResolver staleBaseResolver
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
        long expectedRevision = baseRevision;
        String savedMarkdown = markdown;
        String savedContentHash = contentHash;
        if (currentRevision != baseRevision) {
            Resolution resolution = staleBaseResolver == null
                    ? null : staleBaseResolver.resolve(currentRevision, baseMarkdown);
            if (resolution == null) {
                throw new DocumentVersionConflictException("문서 편집 버전이 일치하지 않습니다.", currentRevision);
            }
            expectedRevision = currentRevision;
            savedMarkdown = resolution.markdown();
            savedContentHash = resolution.contentHash();
        }

        Instant writeAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        boolean changed = !Objects.equals(baseContentHash, savedContentHash);
        long resultRevision = changed ? expectedRevision + 1 : expectedRevision;
        beforeStateWrite.run(changed);
        int updated = changed
                ? jdbcTemplate.update("""
                        UPDATE document_edit_states
                        SET markdown = ?, content_hash = ?, revision = ?, updated_at = ?
                        WHERE document_id = ? AND revision = ?
                        """, savedMarkdown, savedContentHash, resultRevision, Timestamp.from(writeAt), documentId,
                        expectedRevision)
                : jdbcTemplate.update("""
                        UPDATE document_edit_states
                        SET updated_at = updated_at
                        WHERE document_id = ? AND revision = ? AND content_hash = ?
                        """, documentId, expectedRevision, savedContentHash);
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
            throw new DocumentVersionConflictException("문서 편집 버전이 일치하지 않습니다.", jdbcTemplate.queryForObject(
                    "SELECT revision FROM document_edit_states WHERE document_id = ?", Long.class, documentId));
        }

        jdbcTemplate.update("""
                    INSERT INTO document_edit_writes(
                        document_id, revision_write_id, request_hash, result_revision,
                        result_content_hash, result_updated_at, actor_user_id, changed, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, documentId, revisionWriteId, requestHash, resultRevision,
                    savedContentHash, Timestamp.from(writeAt), actorUserId, changed, Timestamp.from(writeAt));
        if (changed) {
            jdbcTemplate.update("""
                        INSERT INTO document_edit_outbox(
                            event_id, document_id, workspace_id, revision, content_hash,
                            event_type, schema_version, created_at, published
                        ) VALUES (?, ?, ?, ?, ?, 'document.edit.saved.v1', 1, ?, false)
                        """, eventId(documentId, revisionWriteId), documentId, workspaceId,
                        resultRevision, savedContentHash, Timestamp.from(writeAt));
        }
        return new PostgresDocumentEditSaveResult(
                expectedRevision, baseMarkdown, baseContentHash, resultRevision,
                savedContentHash, changed ? writeAt : jdbcTemplate.queryForObject(
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

    /** 기준 revision이 지나간 저장을 어떻게 반영할지 정한다. null을 돌려주면 충돌로 거절한다. */
    @FunctionalInterface
    public interface StaleBaseResolver {
        Resolution resolve(long currentRevision, String currentMarkdown);
    }

    /** 실제로 저장할 본문과 그 해시. */
    public record Resolution(String markdown, String contentHash) {}

    @FunctionalInterface
    interface BeforeStateWrite {
        void run(boolean changed);
    }
}
