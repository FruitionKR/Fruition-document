package fruition.shared.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * llmPipeline 호출용 HTTP client 공용 팩토리.
 *
 * <p>connect timeout은 5초로 고정하고, read timeout만 호출별 설정을 받는다.
 * timeout 없는 client가 무한 대기하는 것을 막기 위해 모든 pipeline requester가 이 팩토리를 쓴다.
 * pipeline 내부 인증용 {@code X-Internal-Token} 기본 헤더를 모든 client에 부착한다.
 */
@Component
public class PipelineClientFactory {

    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    private final String internalToken;

    public PipelineClientFactory(@Value("${app.internal.callback-token}") String internalToken) {
        this.internalToken = internalToken;
    }

    public ClientHttpRequestFactory requestFactory(int readTimeoutSeconds) {
        // HttpURLConnection 기반 SimpleClientHttpRequestFactory는 PATCH를 보낼 수 없어
        // (wiki 페이지 rename 등) JDK HttpClient 기반 팩토리를 쓴다.
        // uvicorn이 JDK HttpClient의 h2c 업그레이드 요청을 처리하지 못해 body가 유실되므로 HTTP/1.1로 고정한다.
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MILLIS))
                .build());
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        return factory;
    }

    public RestClient restClient(int readTimeoutSeconds) {
        return RestClient.builder()
                .requestFactory(requestFactory(readTimeoutSeconds))
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }
}
