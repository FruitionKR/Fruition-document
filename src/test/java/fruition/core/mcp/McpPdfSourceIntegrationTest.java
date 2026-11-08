package fruition.core.mcp;

import fruition.TestcontainersConfiguration;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import fruition.core.document.repository.PostgresDocumentEditStore;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.DocumentEditStateInitializer;
import fruition.core.document.service.DocumentService;
import fruition.core.document.service.McpDocumentDataGateway;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** get_pdf_source 주소를 별도 local 서명 클라이언트로 만들어도 실제 MinIO에서 원본 PDF 바이트를 그대로 받는지 본다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class McpPdfSourceIntegrationTest {
    @Autowired DocumentService documentService;
    @Autowired DocumentRepository documentRepository;
    @Autowired FolderRepository folderRepository;
    @Autowired DocumentAccessPolicy accessPolicy;
    @Autowired PostgresDocumentEditStore editStore;
    @Autowired DocumentEditStateInitializer editStateInitializer;
    @Autowired MinioClient minio;
    @Autowired StorageProperties storage;
    @Autowired StringRedisTemplate redis;

    @Test
    void signedSourceUrlFetchesExactOriginalPdf() throws Exception {
        String workspace = "ws_" + UUID.randomUUID();
        String user = "user_" + UUID.randomUUID();
        redis.opsForValue().set("authz:role:v2:" + workspace + ":" + user, "{\"role\":\"OWNER\",\"permissions\":[]}");
        byte[] pdf = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        String pdfId = documentService.upload(workspace, user, "pdf-" + UUID.randomUUID(), null,
                new MockMultipartFile("file", "작은.pdf", "application/pdf", pdf)).id();
        var tools = new McpPdfTools(new McpDocumentDataGateway(documentRepository, folderRepository, accessPolicy,
                editStore, editStateInitializer), mock(McpPlanProposalService.class), minio, storage,
                storage.getEndpoint());
        McpIdentity identity = new McpIdentity(user, workspace, "https://client.example/mcp",
                "00000000-0000-4000-8000-000000000001", 1, Set.of("read"), "OWNER", Set.of());

        var source = (McpPdfTools.SourceResponse) tools.invoke(identity, "get_pdf_source", Map.of("document_id", pdfId));
        HttpResponse<byte[]> fetched = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(source.sourceUrl())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(pdf);
        assertThat(source.canConvert()).isTrue();
    }
}
