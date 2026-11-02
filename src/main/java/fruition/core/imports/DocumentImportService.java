package fruition.core.imports;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.exception.DocumentAlreadyProcessingException;
import fruition.core.document.service.DocumentService;
import fruition.core.imports.DocumentImportDtos.CompleteRequest;
import fruition.core.imports.DocumentImportDtos.CompleteResponse;
import fruition.core.imports.DocumentImportDtos.CreateRequest;
import fruition.core.imports.DocumentImportDtos.CreateResponse;
import fruition.core.imports.DocumentImportDtos.ImportResponse;
import fruition.core.imports.DocumentImportDtos.IngestEstimateResponse;
import fruition.core.imports.DocumentImportDtos.IngestResponse;
import fruition.core.imports.DocumentImportDtos.Quota;
import fruition.core.upload.UploadLimitService;
import fruition.core.usage.limit.AiUsageLimiter;
import fruition.core.usage.service.CreditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 외부 내보내기(Notion "Markdown & CSV") 가져오기 세션. 파일 해석과 폴더 구조는 클라이언트가 맡고, 서버는 세션으로
 * 만든 문서를 표시하고 항목 수를 센다(상한 없음). 저장 용량(5GB)과 파일별 한도는 일반 업로드와 같다.
 * 가져온 문서는 자동 편입하지 않으며 사용자가 {@link #ingest}로 고른 뒤에만 편입한다.
 */
@Service
public class DocumentImportService {
    private static final Logger log = LoggerFactory.getLogger(DocumentImportService.class);
    static final String SOURCE_NOTION = "notion";
    /** 편입 요청 한 번에 큐에 넣는 문서 수. 많은 문서를 한꺼번에 넣어 AI 한도·크레딧을 몰아 쓰지 않게 한다. */
    static final int INGEST_BATCH = 20;
    private static final String INGEST_KIND = "document";
    private static final String NOT_INGESTED =
            "import_id = ? AND workspace_id = ? AND deleted_at IS NULL AND pipeline_run_id IS NULL";

    private final JdbcTemplate jdbc;
    private final WorkspaceAccessGuard access;
    private final UploadLimitService uploadLimits;
    private final DocumentService documents;
    private final CreditService credits;
    private final AiUsageLimiter aiUsageLimiter;

    public DocumentImportService(JdbcTemplate jdbc, WorkspaceAccessGuard access, UploadLimitService uploadLimits,
                                 DocumentService documents, CreditService credits, AiUsageLimiter aiUsageLimiter) {
        this.jdbc = jdbc;
        this.access = access;
        this.uploadLimits = uploadLimits;
        this.documents = documents;
        this.credits = credits;
        this.aiUsageLimiter = aiUsageLimiter;
    }

    /** 세션을 연다. 알린 바이트가 남은 저장 공간을 넘으면 파일을 하나도 올리기 전에 413으로 거절한다. */
    @Transactional
    public CreateResponse create(String workspaceId, String userId, CreateRequest request) {
        access.requireMember(workspaceId, userId);
        if (request == null || !SOURCE_NOTION.equals(request.source())) {
            throw DocumentImportException.badRequest("source는 notion만 지원합니다.");
        }
        if (request.itemCount() == null || request.itemCount() < 0
                || request.totalBytes() == null || request.totalBytes() < 0) {
            throw DocumentImportException.badRequest("item_count와 total_bytes는 0 이상이어야 합니다.");
        }
        // 저장 용량을 미리 본다. 실제 쓰기마다 잠금 아래에서 다시 확인한다.
        uploadLimits.precheck(userId, request.totalBytes());
        UUID id = UUID.randomUUID();
        Instant expiresAt = jdbc.queryForObject("""
                INSERT INTO document_imports (id, workspace_id, user_id, source, status, declared_items, declared_bytes,
                                              expires_at)
                VALUES (?, ?, ?, ?, 'open', ?, ?, now() + interval '24 hours')
                RETURNING expires_at
                """, (rs, n) -> rs.getTimestamp(1).toInstant(), id, workspaceId, userId, request.source(),
                request.itemCount(), request.totalBytes());
        return new CreateResponse(id, "open", expiresAt, null,
                new Quota(uploadLimits.usedBytes(userId), uploadLimits.limitBytes()));
    }

    /** 세션을 닫는다. 이후 이 세션으로 쓰면 400 IMPORT_INVALID다. 이미 완료했으면 같은 결과를 돌려준다. */
    @Transactional
    public CompleteResponse complete(String workspaceId, String userId, String importId, CompleteRequest request) {
        access.requireMember(workspaceId, userId);
        if (request != null && (negative(request.created()) || negative(request.failed()) || negative(request.skipped()))) {
            throw DocumentImportException.badRequest("created·failed·skipped는 0 이상이어야 합니다.");
        }
        UUID id = find(workspaceId, userId, importId, true).id();
        // 같은 세션의 진행 중인 쓰기(항목 수 갱신)가 끝난 뒤 닫히도록 행을 잠그고 바꾼다.
        jdbc.update("""
                UPDATE document_imports SET status = 'completed', completed_at = now(),
                       reported_created = ?, reported_failed = ?, reported_skipped = ?
                WHERE id = ? AND status = 'open'
                """, request == null ? null : request.created(), request == null ? null : request.failed(),
                request == null ? null : request.skipped(), id);
        return new CompleteResponse(id, "completed", documentCount(id));
    }

    @Transactional(readOnly = true)
    public ImportResponse get(String workspaceId, String userId, String importId) {
        access.requireMember(workspaceId, userId);
        Row row = find(workspaceId, userId, importId, false);
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM documents WHERE import_id = ? ORDER BY uploaded_at, id", String.class, row.id());
        return new ImportResponse(row.id(), row.source(), row.displayStatus(), row.createdAt(), row.expiresAt(),
                row.completedAt(), null, row.declaredItems(), row.itemsUsed(), ids.size(), ids,
                row.reportedCreated(), row.reportedFailed(), row.reportedSkipped());
    }

    /** 아직 편입하지 않은 가져온 문서를 모두 편입할 때의 예상 비용. 문서 편입 1건 예상치 × 문서 수다. */
    @Transactional(readOnly = true)
    public IngestEstimateResponse estimate(String workspaceId, String userId, String importId) {
        access.requireMember(workspaceId, userId);
        UUID id = find(workspaceId, userId, importId, false).id();
        int count = notIngestedCount(id, workspaceId);
        BigDecimal usd;
        try {
            usd = BigDecimal.valueOf(aiUsageLimiter.estimateUsdMicro(INGEST_KIND))
                    .multiply(BigDecimal.valueOf(count)).movePointLeft(6);
        } catch (RuntimeException e) {
            log.warn("[가져오기 편입 예상] USD 환산 실패 — null로 응답 importId={} cause={}", id, e.toString());
            usd = null;
        }
        return new IngestEstimateResponse(count, usd, credits.estimate(INGEST_KIND) * count);
    }

    /**
     * 가져온 문서를 편입 큐에 넣는다. 문서마다 수동 "위키 편입"과 같은 {@link DocumentService#ingest} 경로를 써서 권한·AI 한도·
     * 크레딧 처리가 같다. 한 번에 {@value #INGEST_BATCH}개까지만 넣는다. 첫 문서부터 거절되면(권한·한도·크레딧) 그 오류를
     * 그대로 돌려주고, 일부를 넣은 뒤 거절되면 거기서 멈추고 넣은 수를 돌려준다. 이미 처리 중인 문서는 건너뛴다.
     */
    public IngestResponse ingest(String workspaceId, String userId, String importId, List<String> documentIds) {
        access.requireMember(workspaceId, userId);
        UUID id = find(workspaceId, userId, importId, false).id();
        List<String> targets = documentIds == null ? nextNotIngested(id, workspaceId)
                : requireImportDocuments(id, workspaceId, documentIds);
        int queued = 0;
        for (String documentId : targets) {
            try {
                documents.ingest(workspaceId, userId, documentId);
                queued++;
            } catch (DocumentAlreadyProcessingException e) {
                log.info("[가져오기 편입] 이미 처리 중이라 건너뜀 importId={} documentId={}", id, documentId);
            } catch (RuntimeException e) {
                if (queued == 0) throw e;
                log.warn("[가져오기 편입] 일부만 등록하고 멈춤 importId={} queued={} cause={}", id, queued, e.toString());
                break;
            }
        }
        return new IngestResponse(queued, notIngestedCount(id, workspaceId));
    }

    private List<String> nextNotIngested(UUID id, String workspaceId) {
        return jdbc.queryForList("SELECT id FROM documents WHERE " + NOT_INGESTED + " ORDER BY uploaded_at, id LIMIT ?",
                String.class, id, workspaceId, INGEST_BATCH);
    }

    private List<String> requireImportDocuments(UUID id, String workspaceId, List<String> documentIds) {
        List<String> distinct = documentIds.stream().distinct().toList();
        if (distinct.isEmpty() || distinct.size() > INGEST_BATCH || distinct.contains(null)) {
            throw DocumentImportException.badRequest("document_ids는 1개 이상 " + INGEST_BATCH + "개 이하로 보내 주세요.");
        }
        Integer owned = jdbc.queryForObject("""
                SELECT count(*) FROM documents WHERE import_id = ? AND workspace_id = ? AND deleted_at IS NULL
                AND id = ANY(?)
                """, Integer.class, id, workspaceId, distinct.toArray(String[]::new));
        if (owned == null || owned != distinct.size()) {
            throw DocumentImportException.badRequest("이 가져오기에서 만든 문서만 편입할 수 있습니다.");
        }
        return distinct;
    }

    private int notIngestedCount(UUID id, String workspaceId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM documents WHERE " + NOT_INGESTED,
                Integer.class, id, workspaceId);
        return count == null ? 0 : count;
    }

    private int documentCount(UUID id) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM documents WHERE import_id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    private Row find(String workspaceId, String userId, String rawImportId, boolean forUpdate) {
        UUID id;
        try {
            id = UUID.fromString(rawImportId);
        } catch (IllegalArgumentException e) {
            throw DocumentImportException.notFound();
        }
        try {
            return jdbc.queryForObject("SELECT * FROM document_imports WHERE id = ? AND workspace_id = ? AND user_id = ?"
                    + (forUpdate ? " FOR UPDATE" : ""), DocumentImportService::row, id, workspaceId, userId);
        } catch (EmptyResultDataAccessException e) {
            throw DocumentImportException.notFound();
        }
    }

    private static boolean negative(Integer value) {
        return value != null && value < 0;
    }

    private record Row(UUID id, String source, String status, int declaredItems, int itemsUsed,
                       Integer reportedCreated, Integer reportedFailed, Integer reportedSkipped,
                       Instant createdAt, Instant expiresAt, Instant completedAt) {
        String displayStatus() {
            return "open".equals(status) && !expiresAt.isAfter(Instant.now()) ? "expired" : status;
        }
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        return new Row(rs.getObject("id", UUID.class), rs.getString("source"), rs.getString("status"),
                rs.getInt("declared_items"), rs.getInt("items_used"),
                (Integer) rs.getObject("reported_created"), (Integer) rs.getObject("reported_failed"),
                (Integer) rs.getObject("reported_skipped"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), completed == null ? null : completed.toInstant());
    }
}
