package fruition.core.authz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 워크스페이스 접근 인가의 단일 지점(문서 서비스 소유).
 *
 * <p>access 서비스의 DB를 직접 조회하지 않고, access가 소유한 Redis 권한
 * projection({@code authz:role:{workspaceId}:{userId}})을 먼저 읽는다.
 * miss면 access의 내부 API({@code GET /internal/authz/...})로 폴백하고
 * 결과를 TTL과 함께 캐시한다. 내부 API 호출이 실패하면 워크스페이스 존재
 * 여부를 숨기는 {@link WorkspaceNotFoundException}으로 fail-closed 한다.
 */
@Component
public class WorkspaceAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceAccessGuard.class);

    private static final String KEY_PREFIX = "authz:role:";
    private static final Duration CACHE_TTL = Duration.ofSeconds(300);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    private static final String ROLE_OWNER = "OWNER";
    private static final String ROLE_NONE = "NONE";

    private final StringRedisTemplate redisTemplate;
    private final RestClient restClient;

    @Autowired
    public WorkspaceAccessGuard(StringRedisTemplate redisTemplate,
                                @Value("${app.internal.access-base-url}") String accessBaseUrl,
                                @Value("${app.internal.callback-token}") String internalToken) {
        this(redisTemplate, buildRestClient(accessBaseUrl, internalToken));
    }

    WorkspaceAccessGuard(StringRedisTemplate redisTemplate, RestClient restClient) {
        this.redisTemplate = redisTemplate;
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

    /** 멤버가 아니면 404 성격의 WorkspaceNotFoundException을 던진다(fail-closed). */
    public void requireMember(String workspaceId, String userId) {
        if (ROLE_NONE.equals(getRole(workspaceId, userId))) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
    }

    /** 워크스페이스 소유자 여부. 역할 조회가 실패하면 fail-closed로 예외가 전파된다. */
    public boolean isOwner(String workspaceId, String userId) {
        return ROLE_OWNER.equals(getRole(workspaceId, userId));
    }

    /** OWNER | MEMBER | NONE. Redis hit(NONE 포함)은 즉시, miss면 내부 API 폴백 후 캐시한다. */
    public String getRole(String workspaceId, String userId) {
        String key = KEY_PREFIX + workspaceId + ":" + userId;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            // 판정 근거 로그 — 인가 우회 의심 사례 추적용 (docs/backlog/issue/backend/2026-08-07.md)
            log.debug("[authz] cache hit workspace={} user={} role={}", workspaceId, userId, cached);
            return cached;
        }
        String role = fetchRole(workspaceId, userId);
        log.debug("[authz] cache miss → 내부 API 판정 workspace={} user={} role={}", workspaceId, userId, role);
        redisTemplate.opsForValue().set(key, role, CACHE_TTL);
        return role;
    }

    private String fetchRole(String workspaceId, String userId) {
        try {
            RoleResponse response = restClient.get()
                    .uri("/internal/authz/workspaces/{workspaceId}/users/{userId}", workspaceId, userId)
                    .retrieve()
                    .body(RoleResponse.class);
            if (response == null || response.role() == null) {
                throw new WorkspaceNotFoundException(workspaceId);
            }
            return response.role();
        } catch (RestClientException e) {
            // 내부 API 장애 시 열어주지 않는다(fail-closed).
            log.warn("[authz] 내부 API 실패 — fail-closed workspace={} user={} cause={}",
                    workspaceId, userId, e.toString());
            throw new WorkspaceNotFoundException(workspaceId);
        }
    }

    record RoleResponse(String role) {}
}
