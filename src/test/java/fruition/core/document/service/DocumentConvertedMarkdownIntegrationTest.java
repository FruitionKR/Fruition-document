package fruition.core.document.service;

import fruition.TestcontainersConfiguration;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.exception.DocumentVersionConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MCP 외부 AI PDF 변환본이 원본 PDF에 연결되고, 웹 변환과 같은 이미지 규칙으로 저장되며, 내부 변환을 시작하지 않는지 본다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DocumentConvertedMarkdownIntegrationTest {
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    @Autowired DocumentService documentService;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired TransactionTemplate transactions;

    final String workspace = "ws_" + UUID.randomUUID();
    final String user = "user_" + UUID.randomUUID();

    @BeforeEach
    void member() {
        redis.opsForValue().set("authz:role:v2:" + workspace + ":" + user, "{\"role\":\"OWNER\",\"permissions\":[]}");
    }

    @Test
    void createsLinkedConvertedDocumentOnlyForUnchangedSourcePdf() {
        String pdfId = documentService.upload(workspace, user, "pdf-" + UUID.randomUUID(), null,
                new MockMultipartFile("file", "보고서.pdf", "application/pdf",
                        "%PDF-1.4 외부 변환".getBytes(StandardCharsets.UTF_8))).id();
        String hash = "sha256:" + jdbc.queryForObject("SELECT content_hash FROM documents WHERE id = ?", String.class, pdfId);
        var request = new MarkdownDocumentCreateRequest("보고서", "# 보고서\n\n![그림](data:image/png;base64," + PNG + ")\n", null);

        assertThatThrownBy(() -> transactions.execute(status -> documentService.createConvertedMarkdownInCurrentTransaction(
                workspace, user, pdfId, "sha256:" + "0".repeat(64), request)))
                .isInstanceOf(DocumentVersionConflictException.class);

        String created = transactions.execute(status -> documentService.createConvertedMarkdownInCurrentTransaction(
                workspace, user, pdfId, hash, request)).id();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source_document_id, ai_origin, status FROM documents WHERE id = ?", created);
        assertThat(row.get("source_document_id")).isEqualTo(pdfId);
        assertThat(row.get("ai_origin")).isEqualTo("agent");
        assertThat(row.get("status")).isEqualTo("completed");
        String markdown = jdbc.queryForObject("SELECT markdown FROM document_edit_states WHERE document_id = ?",
                String.class, created);
        assertThat(markdown).contains("/api/workspaces/" + workspace + "/assets/").doesNotContain("data:image");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE workspace_id = ? AND source_document_id = ?",
                Integer.class, workspace, pdfId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_convert_queue WHERE source_document_id = ?",
                Integer.class, pdfId)).isZero();
    }
}
