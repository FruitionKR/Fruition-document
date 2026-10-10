package fruition.core.document.service;

import fruition.shared.util.StorageProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 원본(PDF) 문서의 파일 전체 SHA-256을 채운다. 서버를 거친 업로드는 업로드할 때 채우고, 대용량 직접 업로드와
 * 이 컬럼 이전에 올린 문서는 여기서 저장소 객체를 읽어 계산한다. 직접 업로드는 크기가 커서 업로드 응답을 붙잡지 않는다.
 *
 * <p>행을 잠그지 않는다. 여러 replica가 같은 문서를 동시에 계산해도 값은 같고, 비어 있을 때만 쓴다.
 */
@Service
public class DocumentOriginalHashWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentOriginalHashWorker.class);
    private static final int BATCH_SIZE = 5;
    private static final Set<String> MISSING = Set.of("NoSuchKey", "NoSuchObject", "NotFound");

    private final JdbcTemplate jdbc;
    private final MinioClient minio;
    private final StorageProperties storage;

    public DocumentOriginalHashWorker(JdbcTemplate jdbc, MinioClient minio, StorageProperties storage) {
        this.jdbc = jdbc;
        this.minio = minio;
        this.storage = storage;
    }

    @Scheduled(fixedDelayString = "${app.document-original-hash.delay-ms:30000}")
    public void runScheduled() {
        fillPending();
    }

    /** 계산한 문서 수를 돌려준다. */
    public int fillPending() {
        List<Pending> pending = jdbc.query("""
                SELECT id, source_uri FROM documents
                WHERE original_sha256 IS NULL AND document_role = 'ORIGINAL'
                  AND source_uri IS NOT NULL AND deleted_at IS NULL
                ORDER BY uploaded_at LIMIT ?
                """, (rs, n) -> new Pending(rs.getString("id"), rs.getString("source_uri")), BATCH_SIZE);
        // ponytail: 계속 실패하는 문서가 BATCH_SIZE개 쌓이면 뒤 문서가 밀린다. 그런 일이 보이면 실패 횟수 컬럼을 둔다.
        int filled = 0;
        for (Pending document : pending) {
            String sha256;
            try {
                sha256 = sha256(document.sourceUri());
            } catch (ErrorResponseException e) {
                if (!MISSING.contains(e.errorResponse().code())) {
                    log.warn("[원본 해시 계산 실패, 다음 실행에서 다시 시도] documentId={}", document.id(), e);
                    continue;
                }
                // 원본이 없으면 다시 시도해도 계산할 수 없다. 빈 문자열로 표시해 대기열에서 뺀다.
                log.warn("[원본 해시 계산 불가] documentId={} reason=object_missing", document.id());
                sha256 = "";
            } catch (Exception e) {
                log.warn("[원본 해시 계산 실패, 다음 실행에서 다시 시도] documentId={}", document.id(), e);
                continue;
            }
            filled += jdbc.update("UPDATE documents SET original_sha256 = ? WHERE id = ? AND original_sha256 IS NULL",
                    sha256, document.id());
        }
        return filled;
    }

    private String sha256(String sourceUri) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = minio.getObject(GetObjectArgs.builder()
                .bucket(storage.getBucket()).object(objectKey(sourceUri)).build())) {
            byte[] buffer = new byte[1024 * 1024];
            for (int length; (length = input.read(buffer)) != -1;) {
                digest.update(buffer, 0, length);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 예전 행에는 s3://bucket/key 형태가 남아 있을 수 있다. */
    private static String objectKey(String uri) {
        return uri.startsWith("s3://") ? uri.substring(uri.indexOf('/', "s3://".length()) + 1) : uri;
    }

    private record Pending(String id, String sourceUri) {}
}
