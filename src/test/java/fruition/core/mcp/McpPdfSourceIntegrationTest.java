package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fruition.TestcontainersConfiguration;
import fruition.core.authz.WorkspaceSettingsClient;
import fruition.core.document.domain.Document;
import fruition.core.document.service.McpDocumentDataGateway;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 창 이미지 주소를 별도 local 서명 클라이언트로 만들어도 실제 MinIO에서 전처리 묶음 PNG를 그대로 받는지 본다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class McpPdfSourceIntegrationTest {
    private static final String CONVERSION = "0f6f3d1e-2b7a-4c8e-9d5f-1a2b3c4d5e6f";
    @Autowired MinioClient minio;
    @Autowired StorageProperties storage;

    @Test
    void signedWindowImageUrlFetchesExactBundlePng() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2, 3};
        String key = "tmp/convert/mcp/ws_it/" + CONVERSION + "/0/external/images/00000.png";
        minio.putObject(PutObjectArgs.builder().bucket(storage.getBucket()).object(key)
                .stream(new ByteArrayInputStream(png), png.length, -1).build());
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode window = mapper.createObjectNode().put("schema_version", "mcp.pdf-conversion.v1")
                .put("conversion_id", CONVERSION).put("window_id", CONVERSION).put("window_ordinal", 1)
                .put("window_count", 1).put("window_hash", "sha256:" + "a".repeat(64)).put("kind", "repair")
                .put("lane", "table").put("page", 1).put("instructions", "prompt").put("submitted_attempts", 0)
                .put("max_attempts", 2).put("data_boundary", "UNTRUSTED_WORKSPACE_CONTENT");
        window.putObject("payload");
        window.putObject("result_schema");
        window.putArray("image_keys").add(key);
        McpWikiDraftClient ai = mock(McpWikiDraftClient.class);
        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/windows/read"), any())).thenReturn(window);
        when(ai.post(eq("/internal/mcp/pdf-conversions/" + CONVERSION + "/status"), any()))
                .thenReturn(mapper.createObjectNode().put("source_document_id", "doc_pdf")
                        .put("source_content_hash", "sha256:" + "a".repeat(64)));
        McpDocumentDataGateway documents = mock(McpDocumentDataGateway.class);
        var tools = new McpPdfTools(documents, mock(McpPlanProposalService.class),
                mock(McpAccessClient.class), ai, mock(TransactionTemplate.class),
                mock(WorkspaceSettingsClient.class), minio, storage, mapper, 10, storage.getEndpoint());
        McpIdentity identity = new McpIdentity("user_it", "ws_it", "https://client.example/mcp",
                "00000000-0000-4000-8000-000000000001", 1, Set.of("read"), "OWNER", Set.of());

        when(documents.convertSource(identity, "doc_pdf", "sha256:" + "a".repeat(64)))
                .thenReturn(new McpDocumentDataGateway.ConvertSource(mock(Document.class), true));

        @SuppressWarnings("unchecked")
        var view = (Map<String, Object>) tools.invoke(identity, "get_conversion_window",
                Map.of("conversion_id", CONVERSION, "window_ordinal", 1));
        String url = (String) ((List<Map<String, Object>>) view.get("images")).getFirst().get("url");
        HttpResponse<byte[]> fetched = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(png);
        assertThat(url).contains("X-Amz-Expires=600");
    }
}
