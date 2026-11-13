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
    /**
     * 아직 변환하지 않은 가져온 PDF 원본: 휴지통 밖이고, 변환 중이거나 끝난 변환본이 없다(변환 실패만 있으면 다시 변환할 수 있다).
     * 구조를 읽을 수 없는 PDF로 실패한 원본은 다시 해도 같아 빼서, 뒤의 PDF가 계속 밀리지 않게 한다.
     * 가져온 PDF는 업로드만 하고 사용자가 고른 뒤에만 변환한다.
     */
    private static final String UNCONVERTED_PDF =
            "import_id = ? AND workspace_id = ? AND deleted_at IS NULL AND document_role = 'ORIGINAL'"
                    + " AND mime_type = 'application/pdf' AND NOT EXISTS (SELECT 1 FROM documents c"
                    + " WHERE c.source_document_id = documents.id AND c.deleted_at IS NULL"
                    + " AND (c.status <> 'failed' OR c.error_code = '" + DocumentService.UNREADABLE_PDF_CODE + "'))";
    /**
     * 편입 대상: 이 세션의 휴지통 밖 편집 가능 Markdown 중 편입 요청한 적 없는 문서. PDF 원본(ORIGINAL)은 편입 대상이 아니라
     * 빼고, PDF 변환본은 변환이 끝난 뒤(completed, 변환 run만 남음)에만 넣는다. 변환 중·변환 실패는 빠진다.
     */
    private static final String NOT_INGESTED =
            "import_id = ? AND workspace_id = ? AND deleted_at IS NULL AND document_role = 'EDITABLE'"
                    + " AND (pipeline_run_id IS NULL OR (pipeline_run_id LIKE 'convert:%' AND status = 'completed'))";
    /** 아직 변환 중이라 편입 대상에서 빠진 가져온 PDF 변환본. */
    private static final String CONVERTING =
            "import_id = ? AND workspace_id = ? AND deleted_at IS NULL AND document_role = 'EDITABLE'"
                    + " AND pipeline_run_id LIKE 'convert:%' AND status = 'processing'";

    private final JdbcTemplate jdbc;
    private final WorkspaceAccessGuard access;
    private final UploadLimitService uploadLimits;
    private final DocumentService documents;
    private final CreditService credits;

    public DocumentImportService(JdbcTemplate jdbc, WorkspaceAccessGuard access, UploadLimitService uploadLimits,
                                 DocumentService documents, CreditService credits) {
        this.jdbc = jdbc;
        this.access = access;
        this.uploadLimits = uploadLimits;
        this.documents = documents;
        this.credits = credits;
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
        log.info("[노션 가져오기 시작] operation=document_import stage=started importId={} workspaceId={} userId={} itemCount={} totalBytes={}",
                id, workspaceId, userId, request.itemCount(), request.totalBytes());
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
        int closed = jdbc.update("""
                UPDATE document_imports SET status = 'completed', completed_at = now(),
                       reported_created = ?, reported_failed = ?, reported_skipped = ?
                WHERE id = ? AND status = 'open'
                """, request == null ? null : request.created(), request == null ? null : request.failed(),
                request == null ? null : request.skipped(), id);
        var documentCount = documentCount(id);
        // 이미 닫힌 세션의 재요청은 같은 결과만 돌려준다.
        if (closed == 1) {
            fruition.shared.logging.CommittedLog.info(log, "[노션 가져오기 완료] operation=document_import stage=completed importId={} workspaceId={} userId={} documents={} reportedCreated={} reportedFailed={} reportedSkipped={}",
                    id, workspaceId, userId, documentCount, request == null ? null : request.created(),
                    request == null ? null : request.failed(), request == null ? null : request.skipped());
        }
        return new CompleteResponse(id, "completed", documentCount);
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

    /**
     * 아직 편입하지 않은 Markdown과 아직 변환하지 않은 PDF의 견적을 합산한다. PDF 구조 확인(HTTP)이 DB 연결을 붙잡지 않게
     * 트랜잭션을 열지 않는다.
     */
    public IngestEstimateResponse estimate(String workspaceId, String userId, String importId) {
        access.requireMember(workspaceId, userId);
        UUID id = find(workspaceId, userId, importId, false).id();
        List<Long> sizes = jdbc.queryForList("SELECT coalesce((SELECT octet_length(markdown) FROM document_edit_states "
                + "WHERE document_id = documents.id), byte_size) FROM documents WHERE " + NOT_INGESTED,
                Long.class, id, workspaceId);
        List<String> pdfKeys = jdbc.queryForList("SELECT source_uri FROM documents WHERE " + UNCONVERTED_PDF,
                String.class, id, workspaceId);
        var estimates = documents.estimateImportCosts(workspaceId, sizes, pdfKeys);
        return new IngestEstimateResponse(sizes.size(), usd(estimates.ingest(), id), estimates.ingest(),
                count(CONVERTING, id, workspaceId), pdfKeys.size(), usd(estimates.convert(), id), estimates.convert(),
                estimates.unreadablePdfs());
    }

    private BigDecimal usd(long krwMilli, UUID id) {
        try {
            return BigDecimal.valueOf(credits.estimateUsdMicro(krwMilli)).movePointLeft(6);
        } catch (RuntimeException e) {
            log.warn("[가져오기 예상] USD 환산 실패 — null로 응답 importId={} cause={}", id, e.toString());
            return null;
        }
    }

    /**
     * 아직 변환하지 않은 가져온 PDF를 변환 큐에 넣는다. 문서마다 수동 변환(convert-markdown)과 같은 경로라 권한·크레딧 처리가 같고,
     * 변환본은 같은 가져오기 문서로 표시되어 자동 편입하지 않는다. 한 번에 {@value #INGEST_BATCH}개까지 넣고, 첫 PDF부터
     * 거절되면 그 오류를, 일부를 넣은 뒤 거절되면 거기서 멈추고 넣은 수를 돌려준다. remaining이 0이 될 때까지 다시 부른다.
     */
    public IngestResponse convert(String workspaceId, String userId, String importId) {
        access.requireMember(workspaceId, userId);
        UUID id = find(workspaceId, userId, importId, false).id();
        List<String> targets = jdbc.queryForList("SELECT id FROM documents WHERE " + UNCONVERTED_PDF
                + " ORDER BY uploaded_at, id LIMIT ?", String.class, id, workspaceId, INGEST_BATCH);
        int queued = 0;
        for (String documentId : targets) {
            try {
                // 요청마다 새 키: 변환에 실패한 PDF를 다시 고르면 새 변환을 시작해야 한다. 중복은 대상 조회가 막는다.
                documents.convertToMarkdown(workspaceId, userId, documentId, "import-convert:" + UUID.randomUUID());
                queued++;
            } catch (RuntimeException e) {
                if (queued == 0) throw e;
                log.warn("[가져오기 변환] 일부만 등록하고 멈춤 importId={} queued={} cause={}", id, queued, e.toString());
                break;
            }
        }
        var remaining = count(UNCONVERTED_PDF, id, workspaceId);
        log.info("[노션 가져오기 변환 요청 접수] operation=document_import stage=convert_accepted importId={} workspaceId={} userId={} queued={} remaining={}",
                id, workspaceId, userId, queued, remaining);
        return new IngestResponse(queued, remaining);
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
        log.info("[노션 가져오기 편입 요청 접수] operation=document_import stage=ingest_accepted importId={} workspaceId={} userId={} queued={}",
                id, workspaceId, userId, queued);
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
        return count(NOT_INGESTED, id, workspaceId);
    }

    private int count(String where, UUID id, String workspaceId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM documents WHERE " + where,
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
