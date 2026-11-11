package fruition.core.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.document.exception.DocumentVersionConflictException;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 외부 AI PDF 변환본이 승인 뒤 저장소의 조립 결과(key·hash)에서 웹 변환 batch와 같은 코드(이미지 asset 분리,
 * 변환본 sanitize, 쪽 번호 바꾸기, 크기 무관 저장)로 원본 PDF 폴더에 저장되고, 내부 변환을 시작하지 않는지 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DocumentConvertedMarkdownIntegrationTest {
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    @Autowired DocumentService documentService;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired TransactionTemplate transactions;
    @Autowired MinioClient minio;
    @Autowired StorageProperties storage;

    final String workspace = "ws_" + UUID.randomUUID();
    final String user = "user_" + UUID.randomUUID();

    @BeforeEach
    void member() {
        redis.opsForValue().set("authz:role:v2:" + workspace + ":" + user, "{\"role\":\"OWNER\",\"permissions\":[]}");
    }

    @Test
    void savesStoredConversionResultWithWebBatchRulesInTheSourceFolder() throws Exception {
        String pdfId = documentService.upload(workspace, user, "pdf-" + UUID.randomUUID(), null,
                new MockMultipartFile("file", "보고서.pdf", "application/pdf",
                        "%PDF-1.4 외부 변환".getBytes(StandardCharsets.UTF_8))).id();
        String hash = "sha256:" + jdbc.queryForObject("SELECT content_hash FROM documents WHERE id = ?", String.class, pdfId);
        // 묶음마다 쪽 번호는 1부터다. 둘째 묶음은 원본 3쪽부터이고, 웹 변환처럼 크기와 관계없이 모두 저장한다.
        String large = "가".repeat(2 * 1024 * 1024);
        byte[] result = new ObjectMapper().writeValueAsBytes(List.of(
                Map.of("page_start", 1, "markdown", "<!-- page 1 -->\n\n# 보고서\n\n![그림](data:image/png;base64," + PNG + ")\n"),
                Map.of("page_start", 3, "markdown", "<!-- page 1 -->\n\n둘째 묶음\n\n<!-- page 2 -->\n\n" + large + "\n")));
        String key = "tmp/mcp-convert/" + workspace + "/" + UUID.randomUUID() + "/final.json";
        minio.putObject(PutObjectArgs.builder().bucket(storage.getBucket()).object(key)
                .stream(new ByteArrayInputStream(result), result.length, -1).build());
        String resultHash = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(result));

        assertThatThrownBy(() -> transactions.execute(status -> documentService.createConvertedMarkdownInCurrentTransaction(
                workspace, user, pdfId, "sha256:" + "0".repeat(64), "보고서", null, key, resultHash)))
                .isInstanceOf(DocumentVersionConflictException.class);
        assertThatThrownBy(() -> transactions.execute(status -> documentService.createConvertedMarkdownInCurrentTransaction(
                workspace, user, pdfId, hash, "보고서", null, key, "sha256:" + "0".repeat(64))))
                .isInstanceOf(DocumentVersionConflictException.class);
        // 원본 PDF와 다른 폴더에는 만들지 않는다.
        assertThatThrownBy(() -> transactions.execute(status -> documentService.createConvertedMarkdownInCurrentTransaction(
                workspace, user, pdfId, hash, "보고서", UUID.randomUUID(), key, resultHash)))
                .isInstanceOf(DocumentVersionConflictException.class);

        String created = transactions.execute(status -> documentService.createConvertedMarkdownInCurrentTransaction(
                workspace, user, pdfId, hash, "보고서", null, key, resultHash)).id();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source_document_id, ai_origin, status, folder_id FROM documents WHERE id = ?", created);
        assertThat(row.get("source_document_id")).isEqualTo(pdfId);
        assertThat(row.get("ai_origin")).isEqualTo("agent");
        assertThat(row.get("status")).isEqualTo("completed");
        assertThat(row.get("folder_id")).isNull();
        String markdown = jdbc.queryForObject("SELECT markdown FROM document_edit_states WHERE document_id = ?",
                String.class, created);
        assertThat(markdown).contains("/api/workspaces/" + workspace + "/assets/").doesNotContain("data:image");
        assertThat(markdown).contains("<!-- page 1 -->", "<!-- page 3 -->", "<!-- page 4 -->", large)
                .doesNotContain("<!-- page 2 -->");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE workspace_id = ? AND source_document_id = ?",
                Integer.class, workspace, pdfId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_convert_queue WHERE source_document_id = ?",
                Integer.class, pdfId)).isZero();
    }
}
