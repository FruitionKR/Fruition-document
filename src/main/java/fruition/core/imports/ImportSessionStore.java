package fruition.core.imports;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 가져오기 세션 행(document_imports)을 읽고 항목 수를 센다. 문서 쓰기 경로(DocumentService)가 쓰므로
 * DocumentService에 의존하지 않는다(API 쪽은 {@link DocumentImportService}).
 *
 * <p>X-Fruition-Import-Id가 붙은 쓰기는 세션이 열려 있고(완료·만료 아님), 호출자 본인의 것이며, 같은 워크스페이스일 때만
 * 받는다. 그렇지 않으면 일반 한도로 대신 처리하지 않고 400 IMPORT_INVALID로 거절한다.
 */
@Component
public class ImportSessionStore {
    public static final String HEADER = "X-Fruition-Import-Id";

    private final JdbcTemplate jdbc;

    public ImportSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 쓰기를 받을 수 있는 세션. */
    public record OpenSession(UUID id, String workspaceId, String userId) {}

    /** 헤더 값이 없으면 null(일반 쓰기). 있으면 열린 세션이어야 한다. */
    public OpenSession resolve(String workspaceId, String userId, String rawImportId) {
        if (rawImportId == null) return null;
        return requireOpen(workspaceId, userId, rawImportId);
    }

    public OpenSession requireOpen(String workspaceId, String userId, String rawImportId) {
        UUID id = parse(rawImportId);
        Boolean open;
        try {
            open = jdbc.queryForObject("""
                    SELECT status = 'open' AND expires_at > now() FROM document_imports
                    WHERE id = ? AND workspace_id = ? AND user_id = ?
                    """, Boolean.class, id, workspaceId, userId);
        } catch (EmptyResultDataAccessException e) {
            throw DocumentImportException.invalid();
        }
        if (!Boolean.TRUE.equals(open)) throw DocumentImportException.invalid();
        return new OpenSession(id, workspaceId, userId);
    }

    /** 가져오기 세션의 이미지 저장 등 기존 문서 쓰기는 그 세션이 만든 문서에만 허용한다. */
    public void requireDocument(OpenSession session, String documentId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE id = ? AND workspace_id = ? AND import_id = ?",
                Integer.class, documentId, session.workspaceId(), session.id());
        if (count == null || count == 0) {
            throw new DocumentImportException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    DocumentImportException.IMPORT_INVALID, "이 가져오기 세션에서 만든 문서가 아닙니다.");
        }
    }

    /**
     * 세션 항목 수(문서+이미지)를 늘린다. 상한은 없고 조회용이다. 행 갱신이 열림·만료를 함께 확인하므로 세션이 그 사이
     * 완료·만료됐으면 400 IMPORT_INVALID로 쓰기를 되돌리고, 완료 요청은 진행 중인 쓰기가 커밋된 뒤에 닫힌다.
     * 호출자 트랜잭션이 롤백되면 증가도 함께 되돌아간다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void countItems(OpenSession session, int items) {
        if (items <= 0) return;
        int updated = jdbc.update("""
                UPDATE document_imports SET items_used = items_used + ?
                WHERE id = ? AND status = 'open' AND expires_at > now()
                """, items, session.id());
        if (updated != 1) throw DocumentImportException.invalid();
    }

    private static UUID parse(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw DocumentImportException.invalid();
        }
    }
}
