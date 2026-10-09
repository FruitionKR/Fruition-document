package fruition.core.document.service;

import fruition.TestcontainersConfiguration;
import fruition.core.document.dto.DocumentListResponse;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 목록의 file_sha256이 로컬 파일의 SHA-256과 같아 데스크톱 앱이 중복 업로드를 판별할 수 있는지 확인한다. */
@SpringBootTest(properties = "app.document-original-hash.delay-ms=3600000")
@Import(TestcontainersConfiguration.class)
class DocumentFileHashIntegrationTest {

    @Autowired DocumentService documentService;
    @Autowired DocumentOriginalHashWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired MinioClient minio;
    @Autowired StorageProperties storage;
    @Autowired StringRedisTemplate redis;

    final String workspace = "ws_" + UUID.randomUUID();
    final String user = "user_" + UUID.randomUUID();

    @BeforeEach
    void member() {
        redis.opsForValue().set("authz:role:" + workspace + ":" + user, "OWNER");
    }

    @Test
    void uploadedPdfAndMarkdownExposeLocalFileHash() throws Exception {
        byte[] pdf = "%PDF-1.4 같은 파일".getBytes(StandardCharsets.UTF_8);
        byte[] markdown = "# 메모\n본문".getBytes(StandardCharsets.UTF_8);

        documentService.upload(workspace, user, "pdf-key", null,
                new MockMultipartFile("file", "보고서.pdf", "application/pdf", pdf));
        documentService.upload(workspace, user, "md-key", null,
                new MockMultipartFile("file", "메모.md", "text/markdown", markdown));

        assertThat(hashOf("보고서.pdf")).isEqualTo(sha256(pdf));
        assertThat(hashOf("메모.md")).isEqualTo(sha256(markdown));
    }

    @Test
    void workerFillsDirectUploadHashAndMarksMissingOriginals() throws Exception {
        byte[] pdf = "%PDF-1.7 직접 업로드".getBytes(StandardCharsets.UTF_8);
        // 실제 업로드처럼 객체를 먼저 올리고 행을 넣는다. 반대 순서면 그 사이에 다른 컨텍스트의 worker가
        // 객체 없음으로 보고 빈 문자열을 기록해, 다시는 계산하지 않는다.
        String stored = UUID.randomUUID().toString();
        minio.putObject(PutObjectArgs.builder().bucket(storage.getBucket()).object(sourceKey(stored))
                .stream(new ByteArrayInputStream(pdf), pdf.length, -1).build());
        insertOriginal(stored, "큰파일.pdf");
        String missing = insertOriginal(UUID.randomUUID().toString(), "없는파일.pdf");
        // 계산 전 null은 확인하지 않는다. 같은 JVM에 캐시된 다른 테스트 컨텍스트의 worker(기본 30초)가 먼저 채울 수 있다.

        // 반환값(이번에 채운 수)으로 멈추면 안 된다. 다른 컨텍스트의 worker가 같은 문서를 먼저 채우면 0이 나와
        // 업로드 시각이 늦은 이 테스트의 문서까지 가기 전에 끝난다. 이 테스트의 문서가 채워질 때까지 돌린다.
        for (int attempt = 0; attempt < 100 && (pending(stored) || pending(missing)); attempt++) {
            worker.fillPending();
        }

        assertThat(hashOf("큰파일.pdf")).isEqualTo(sha256(pdf));
        assertThat(hashOf("없는파일.pdf")).isNull();
        assertThat(jdbc.queryForObject("SELECT original_sha256 FROM documents WHERE id = ?", String.class, missing))
                .isEmpty();
    }

    private boolean pending(String documentId) {
        return jdbc.queryForObject("SELECT original_sha256 IS NULL FROM documents WHERE id = ?", Boolean.class, documentId);
    }

    private String insertOriginal(String id, String filename) {
        jdbc.update("""
                INSERT INTO documents(
                    id, byte_size, content_hash, filename, display_name, normalized_filename,
                    mime_type, source_uri, status, uploaded_at, updated_at, user_id, workspace_id,
                    current_content_hash, current_version, document_role, sort_order
                ) VALUES (?, 1, 'etag-hash', ?, ?, ?, 'application/pdf', ?, 'uploaded', now(), now(), ?, ?,
                          'etag-hash', 1, 'ORIGINAL', 0)
                """, id, filename, filename, filename.toLowerCase(), sourceKey(id), user, workspace);
        return id;
    }

    private String hashOf(String filename) {
        DocumentListResponse list = documentService.findAll(workspace, user, null);
        return list.documents().stream()
                .filter(item -> item.filename().equals(filename))
                .findFirst().orElseThrow()
                .fileSha256();
    }

    private static String sourceKey(String id) {
        return "sources/documents/" + id + "/original";
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
