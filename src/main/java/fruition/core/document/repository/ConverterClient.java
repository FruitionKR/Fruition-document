package fruition.core.document.repository;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import fruition.core.document.exception.DocumentConvertException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * PDF → Markdown 변환기(converter) 내부 HTTP client.
 *
 * <p>converter는 ClusterIP/내부 전용이라 인증 헤더 없이 호출한다(NetworkPolicy로 격리).
 * OCR이 오래 걸릴 수 있어 read timeout을 길게(900초) 둔다.
 */
@Component
public class ConverterClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(900);

    private final RestClient restClient;

    @Autowired
    public ConverterClient(@Value("${app.internal.converter-base-url}") String converterBaseUrl) {
        this(buildRestClient(converterBaseUrl));
    }

    ConverterClient(RestClient restClient) {
        this.restClient = restClient;
    }

    private static RestClient buildRestClient(String converterBaseUrl) {
        // uvicorn이 JDK HttpClient의 h2c 업그레이드 요청을 처리하지 못해 body가 유실되므로 HTTP/1.1로 고정한다.
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(converterBaseUrl)
                .requestFactory(factory)
                .build();
    }

    /** PDF 원본을 변환기에 보내 Markdown 본문을 받는다. 실패는 상태 코드를 담아 DocumentConvertException으로 알린다. */
    public String convertPdf(String filename, byte[] pdfBytes, String provider, String model) {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_PDF);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new HttpEntity<>(new ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, fileHeaders));
        parts.add("provider", provider);
        parts.add("model", model);

        try {
            ConvertResponse response = restClient.post()
                    .uri("/convert")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(ConvertResponse.class);
            if (response == null || response.markdown() == null) {
                throw new DocumentConvertException("변환기 응답에 markdown이 없습니다.");
            }
            return response.markdown();
        } catch (RestClientResponseException e) {
            // 422=변환 실패, 504=OCR timeout, 503=변환기 의존성 결손, 413/415=입력 거부. 상태 코드로 원인을 구분한다.
            throw new DocumentConvertException(
                    "변환기 호출이 실패했습니다. status=" + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            // 연결 실패·read timeout 등 응답 없는 실패.
            throw new DocumentConvertException("변환기에 연결하지 못했습니다: " + e.getMessage(), e);
        }
    }

    public String convertPdf(String filename, byte[] pdfBytes, String provider, String model,
                             java.util.function.BooleanSupplier active) {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var request = executor.submit(() -> convertPdf(filename, pdfBytes, provider, model));
            try {
                while (active.getAsBoolean()) {
                    try {
                        return request.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (java.util.concurrent.TimeoutException pending) {
                        // 취소 확인을 위해 응답을 짧게 기다린다.
                    }
                }
                throw new java.util.concurrent.CancellationException("변환 작업이 취소되었습니다.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DocumentConvertException("변환 대기가 중단되었습니다.", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new DocumentConvertException("변환에 실패했습니다.", e.getCause());
            } finally {
                request.cancel(true);
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConvertResponse(String markdown) {}
}
