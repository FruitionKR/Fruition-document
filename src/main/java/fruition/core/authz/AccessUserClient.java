package fruition.core.authz;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * access 내부 사용자 조회 API({@code GET /internal/users/{id}}) client.
 * 표시명은 best-effort 정보라 조회 실패(404 포함) 시 null을 반환한다.
 */
@Component
public class AccessUserClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final RestClient restClient;

    @Autowired
    public AccessUserClient(@Value("${app.internal.access-base-url}") String accessBaseUrl,
                            @Value("${app.internal.callback-token}") String internalToken) {
        this(buildRestClient(accessBaseUrl, internalToken));
    }

    AccessUserClient(RestClient restClient) {
        this.restClient = restClient;
    }

    private static RestClient buildRestClient(String accessBaseUrl, String internalToken) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(accessBaseUrl)
                .requestFactory(factory)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    /** 사용자 표시명. 없거나 조회에 실패하면 null. */
    public String getDisplayName(String userId) {
        UserResponse response = getUser(userId);
        return response == null ? null : response.displayName();
    }

    private UserResponse getUser(String userId) {
        try {
            UserResponse response = restClient.get()
                    .uri("/internal/users/{userId}", userId)
                    .retrieve()
                    .body(UserResponse.class);
            return response;
        } catch (RestClientException e) {
            return null;
        }
    }

    record UserResponse(@JsonProperty("display_name") String displayName) {}
}
