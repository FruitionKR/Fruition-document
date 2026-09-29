package fruition.core.document.repository;

import com.sun.net.httpserver.HttpServer;
import fruition.core.document.exception.DocumentConvertException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConverterClientTest {

    private HttpServer server;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedContentType = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>(
            "{\"filename\":\"보고서.pdf\",\"content_type\":\"application/pdf\","
                    + "\"markdown\":\"# 변환 결과\\n\",\"pdfinfo\":\"info\",\"pdffonts\":\"fonts\",\"process_log\":\"log\"}");

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/convert", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/convert-source-batch", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ConverterClient client() {
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new ConverterClient(RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build());
    }

    @Test
    void convertPdf_sendsMultipartFilePartAndReturnsMarkdown() {
        String markdown = client().convertPdf(
                "보고서.pdf", "%PDF-1.4".getBytes(StandardCharsets.US_ASCII),
                "gemini", "gemini-3.1-flash-lite");

        assertThat(markdown).isEqualTo("# 변환 결과\n");
        assertThat(capturedContentType.get()).startsWith("multipart/form-data");
        assertThat(capturedBody.get())
                .contains("name=\"file\"")
                .containsPattern("(?s)name=\"provider\"\\r\\n.*?\\r\\n\\r\\ngemini\\r\\n--")
                .containsPattern("(?s)name=\"model\"\\r\\n.*?\\r\\n\\r\\ngemini-3\\.1-flash-lite\\r\\n--")
                .contains("Content-Type: application/pdf")
                .contains("%PDF-1.4");
    }

    @Test
    void convertPdf_converterFailure_throwsWithStatusCode() {
        responseStatus.set(422);
        responseBody.set("{\"detail\":\"Command failed: ocrmypdf\"}");

        assertThatThrownBy(() -> client().convertPdf(
                "보고서.pdf", new byte[]{1}, "openai", "gpt-5-nano"))
                .isInstanceOf(DocumentConvertException.class)
                .hasMessageContaining("status=422");
    }

    @Test
    void convertPdf_ocrTimeout_throwsWithStatusCode() {
        responseStatus.set(504);
        responseBody.set("{\"detail\":\"Command timeout: ocrmypdf\"}");

        assertThatThrownBy(() -> client().convertPdf(
                "보고서.pdf", new byte[]{1}, "openai", "gpt-5-nano"))
                .isInstanceOf(DocumentConvertException.class)
                .hasMessageContaining("status=504");
    }

    @Test
    void convertPdf_responseWithoutMarkdown_throws() {
        responseBody.set("{\"filename\":\"보고서.pdf\"}");

        assertThatThrownBy(() -> client().convertPdf(
                "보고서.pdf", new byte[]{1}, "openai", "gpt-5-nano"))
                .isInstanceOf(DocumentConvertException.class)
                .hasMessageContaining("markdown");
    }

    @Test
    void convertPdf_unreachableConverter_throwsConnectFailure() {
        server.stop(0);

        assertThatThrownBy(() -> client().convertPdf(
                "보고서.pdf", new byte[]{1}, "openai", "gpt-5-nano"))
                .isInstanceOf(DocumentConvertException.class);
    }

    @Test
    void convertSourceBatch_422_includesConverterDetailWithoutSourceUrl() {
        responseStatus.set(422);
        responseBody.set("{\"detail\":\"Could not read the PDF storage range: HTTP 403 AccessDenied\"}");
        String sourceUrl = "https://bucket.s3.ap-northeast-2.amazonaws.com/o?X-Amz-Credential=SECRETKEY&X-Amz-Signature=sig";

        assertThatThrownBy(() -> client().convertSourceBatch(sourceUrl, 1234, "gemini", "m", 0, () -> true))
                .isInstanceOf(DocumentConvertException.class)
                .hasMessageContaining("페이지 묶음 변환 실패")
                .hasMessageContaining("status=422")
                .hasMessageContaining("HTTP 403 AccessDenied")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("SECRETKEY").doesNotContain("X-Amz"));
    }

    @Test
    void convertSourceBatch_success_returnsBatch() {
        responseBody.set("{\"page_start\":1,\"page_end\":10,\"total_pages\":25,\"markdown\":\"# p\",\"done\":false}");

        var batch = client().convertSourceBatch("https://example.invalid/o", 1234, "gemini", "m", 0, () -> true);

        assertThat(batch.page_end()).isEqualTo(10);
        assertThat(batch.done()).isFalse();
        assertThat(capturedBody.get()).contains("\"start_page\":0").contains("\"byte_size\":1234");
    }
}
