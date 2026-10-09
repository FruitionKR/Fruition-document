package fruition.core.document.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.domain.Document;
import fruition.core.document.dto.DocumentEditConflictListResponse;
import fruition.core.document.dto.DocumentEditConflictRequest;
import fruition.core.document.dto.DocumentEditConflictResolveRequest;
import fruition.core.document.dto.DocumentEditConflictResponse;
import fruition.core.document.exception.ConflictAlreadyResolvedException;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import fruition.core.document.exception.EditConflictNotFoundException;
import fruition.core.document.exception.InvalidMarkdownContentException;
import fruition.core.document.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * 편집 충돌. 같은 revision에서 갈라진 저장이 409를 받으면 클라이언트가 자기 본문을 충돌로 올려 보존하고,
 * 워크스페이스 OWNER가 서버 본·충돌 본·직접 합친 본 중 하나를 골라 해결한다.
 *
 * <p>해결해도 충돌 행은 지우지 않는다. 서버 본은 이미 버전 이력에 있고, 고르지 않은 충돌 본은 이 행에 남는다.
 * 충돌 알림은 아직 없어서 OWNER가 목록을 조회해 확인한다.
 */
@Service
public class DocumentEditConflictService {

    private static final Logger log = LoggerFactory.getLogger(DocumentEditConflictService.class);

    private static final String CONFLICT_COLUMNS = """
            c.id, c.document_id, c.base_revision, c.markdown, c.author_user_id, c.status, c.resolution,
            c.resolved_by, c.resolved_revision, c.created_at, c.resolved_at""";

    private static final RowMapper<DocumentEditConflictResponse> CONFLICT_MAPPER = (rs, rowNum) -> conflict(rs);

    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final DocumentRepository documentRepository;
    private final DocumentAccessPolicy documentAccessPolicy;
    private final DocumentService documentService;
    private final JdbcTemplate jdbc;

    public DocumentEditConflictService(WorkspaceAccessGuard workspaceAccessGuard, DocumentRepository documentRepository,
                                       DocumentAccessPolicy documentAccessPolicy, DocumentService documentService,
                                       JdbcTemplate jdbc) {
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.documentRepository = documentRepository;
        this.documentAccessPolicy = documentAccessPolicy;
        this.documentService = documentService;
        this.jdbc = jdbc;
    }

    /** 그 문서를 편집할 수 있는 사람만 등록한다. 같은 client_conflict_id 재전송은 기존 충돌을 돌려준다. */
    @Transactional
    public DocumentEditConflictResponse register(String workspaceId, String userId, String documentId,
                                                 DocumentEditConflictRequest request) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        documentAccessPolicy.requireEdit(document, userId);
        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.markdown(request.markdown());

        int inserted = jdbc.update("""
                INSERT INTO document_edit_conflicts(
                    id, workspace_id, document_id, base_revision, markdown, content_hash, author_user_id, client_conflict_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (document_id, client_conflict_id) DO NOTHING
                """, UUID.randomUUID(), workspaceId, documentId, request.baseRevision(), content.markdown(),
                content.contentHash(), userId, request.clientConflictId());
        if (inserted == 1) {
            log.info("[편집 충돌 등록] workspaceId={} documentId={} userId={} baseRevision={}",
                    workspaceId, documentId, userId, request.baseRevision());
        }
        return jdbc.queryForObject("SELECT " + CONFLICT_COLUMNS
                        + " FROM document_edit_conflicts c WHERE c.document_id = ? AND c.client_conflict_id = ?",
                CONFLICT_MAPPER, documentId, request.clientConflictId());
    }

    /** OWNER에게 미해결 충돌과 서버 현재 본문을 함께 보여준다. 휴지통 문서의 충돌은 뺀다. */
    @Transactional(readOnly = true)
    public DocumentEditConflictListResponse listOpen(String workspaceId, String userId) {
        requireOwner(workspaceId, userId);
        return new DocumentEditConflictListResponse(jdbc.query("SELECT " + CONFLICT_COLUMNS + """
                        , d.display_name, COALESCE(d.updated_by, d.user_id) AS server_updated_by,
                          s.markdown AS server_markdown, s.revision AS server_revision, s.updated_at AS server_updated_at
                        FROM document_edit_conflicts c
                        JOIN documents d ON d.id = c.document_id AND d.deleted_at IS NULL
                        JOIN document_edit_states s ON s.document_id = c.document_id
                        WHERE c.workspace_id = ? AND c.status = 'open'
                        ORDER BY c.created_at, c.id
                        """,
                (rs, rowNum) -> new DocumentEditConflictListResponse.Item(
                        conflict(rs),
                        rs.getString("display_name"),
                        new DocumentEditConflictListResponse.Server(
                                rs.getString("server_markdown"),
                                rs.getLong("server_revision"),
                                rs.getString("server_updated_by"),
                                rs.getTimestamp("server_updated_at").toInstant())),
                workspaceId));
    }

    /**
     * 충돌 행을 잠그고 해결한다. OWNER가 여럿이 동시에 해결해도 먼저 잠근 쪽만 반영되고 나중 요청은 409다.
     * conflict·merged는 본문 저장 경로로 새 revision을 만들고(OWNER는 항상 편집 가능), server는 본문을 바꾸지 않는다.
     */
    @Transactional
    public DocumentEditConflictResponse resolve(String workspaceId, String userId, UUID conflictId,
                                                DocumentEditConflictResolveRequest request) {
        requireOwner(workspaceId, userId);
        DocumentEditConflictResponse conflict = jdbc.query("SELECT " + CONFLICT_COLUMNS
                        + " FROM document_edit_conflicts c WHERE c.id = ? AND c.workspace_id = ? FOR UPDATE",
                CONFLICT_MAPPER, conflictId, workspaceId).stream().findFirst()
                .orElseThrow(EditConflictNotFoundException::new);
        if (!"open".equals(conflict.status())) {
            throw new ConflictAlreadyResolvedException();
        }

        long resolvedRevision;
        if ("server".equals(request.choice())) {
            resolvedRevision = jdbc.queryForObject("SELECT revision FROM document_edit_states WHERE document_id = ?",
                    Long.class, conflict.documentId());
        } else {
            String markdown = "merged".equals(request.choice()) ? request.markdown() : conflict.markdown();
            if (markdown == null) {
                throw new InvalidMarkdownContentException("merged를 고르면 markdown이 필요합니다.");
            }
            if (request.baseRevision() == null) {
                throw new InvalidMarkdownContentException("conflict·merged를 고르면 base_revision이 필요합니다.");
            }
            // 충돌마다 한 번만 해결되므로 충돌 ID로 write ID를 고정한다.
            resolvedRevision = documentService.saveContentInCurrentTransaction(workspaceId, userId,
                    conflict.documentId(), markdown, request.baseRevision(), "conflict-resolve:" + conflictId,
                    null).currentVersion();
        }

        jdbc.update("""
                UPDATE document_edit_conflicts
                SET status = 'resolved', resolution = ?, resolved_by = ?, resolved_revision = ?, resolved_at = ?
                WHERE id = ?
                """, request.choice(), userId, resolvedRevision, Timestamp.from(Instant.now()), conflictId);
        log.info("[편집 충돌 해결] workspaceId={} conflictId={} userId={} choice={} revision={}",
                workspaceId, conflictId, userId, request.choice(), resolvedRevision);
        return jdbc.queryForObject("SELECT " + CONFLICT_COLUMNS + " FROM document_edit_conflicts c WHERE c.id = ?",
                CONFLICT_MAPPER, conflictId);
    }

    private void requireOwner(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        if (!workspaceAccessGuard.isOwner(workspaceId, userId)) {
            throw new DocumentWriteForbiddenException("워크스페이스 OWNER만 편집 충돌을 보고 해결할 수 있습니다.");
        }
    }

    private static DocumentEditConflictResponse conflict(ResultSet rs) throws SQLException {
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return new DocumentEditConflictResponse(
                rs.getObject("id", UUID.class),
                rs.getString("document_id"),
                rs.getLong("base_revision"),
                rs.getString("markdown"),
                rs.getString("author_user_id"),
                rs.getString("status"),
                rs.getString("resolution"),
                rs.getString("resolved_by"),
                rs.getObject("resolved_revision", Long.class),
                rs.getTimestamp("created_at").toInstant(),
                resolvedAt == null ? null : resolvedAt.toInstant());
    }
}
