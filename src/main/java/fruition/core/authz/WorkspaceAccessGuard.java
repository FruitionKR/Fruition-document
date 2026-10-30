package fruition.core.authz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * 워크스페이스 접근 인가의 단일 지점(문서 서비스 소유).
 *
 * <p>access 서비스의 DB를 직접 조회하지 않고, access가 소유한 Redis 권한
 * projection({@code authz:role:v2:{workspaceId}:{userId}}, JSON 값)을 먼저 읽는다.
 * 옛 키({@code authz:role:{ws}:{uid}}, 평문 역할)는 구버전 pod가 쓰므로 읽지도 쓰지도 않는다.
 * miss면 access의 내부 API({@code GET /internal/authz/...})로 폴백하고
 * 결과를 TTL과 함께 캐시한다. 내부 API 호출이 실패하면 워크스페이스 존재
 * 여부를 숨기는 {@link WorkspaceNotFoundException}으로 fail-closed 한다.
 */
@Component
public class WorkspaceAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceAccessGuard.class);

    private static final String KEY_PREFIX = "authz:role:v2:";
    private static final Duration CACHE_TTL = Duration.ofSeconds(300);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    private static final String ROLE_OWNER = "OWNER";
    private static final String ROLE_NONE = "NONE";
    private static final java.util.Set<String> VALID_ROLES = java.util.Set.of(ROLE_OWNER, "MEMBER", ROLE_NONE);

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        return resolve(workspaceId, userId).role();
    }

    /**
     * 권한 항목 보유 여부. OWNER는 항상 true, NONE은 false, MEMBER는 부여된 항목만 true.
     * 역할과 권한을 한 번의 캐시 조회(또는 내부 API 호출)로 읽는다.
     */
    public boolean hasPermission(String workspaceId, String userId, String permission) {
        return resolve(workspaceId, userId).hasPermission(permission);
    }

    /**
     * 역할과 권한 항목을 한 번의 캐시 조회(또는 내부 API 호출)로 읽어 돌려준다. 한 요청에서 소유자 여부와
     * 권한 항목을 함께 판정할 때 {@link #isOwner}·{@link #hasPermission}을 따로 불러 두 번 읽지 않도록 쓴다.
     */
    public Access access(String workspaceId, String userId) {
        return resolve(workspaceId, userId);
    }

    /** 멤버가 아니면 404(WorkspaceNotFoundException), 멤버인데 항목이 없으면 403으로 거절한다. */
    public void requirePermission(String workspaceId, String userId, String permission) {
        Access access = resolve(workspaceId, userId);
        if (ROLE_NONE.equals(access.role())) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
        if (!ROLE_OWNER.equals(access.role()) && !access.permissions().contains(permission)) {
            throw new WorkspacePermissionRequiredException(permission);
        }
    }

    private Access resolve(String workspaceId, String userId) {
        String key = KEY_PREFIX + workspaceId + ":" + userId;
        String cached;
        try {
            cached = redisTemplate.opsForValue().get(key);
        } catch (DataAccessException e) {
            // Redis는 캐시일 뿐이다. 장애면 miss로 보고 내부 API로 판정한다(내부 API도 실패하면 fail-closed).
            log.warn("[authz] Redis 조회 실패 — 내부 API로 판정 workspace={} user={} cause={}",
                    workspaceId, userId, e.toString());
            return fetchAccess(workspaceId, userId);
        }
        if (cached != null) {
            // 판정 근거 로그 — 인가 우회 의심 사례 추적용 (docs/backlog/issue/backend/2026-08-07.md)
            log.debug("[authz] cache hit workspace={} user={} value={}", workspaceId, userId, cached);
            Access access = parseCached(cached);
            if (access != null) {
                return access;
            }
            // 읽을 수 없는 값은 miss로 보고 내부 API 결과로 덮어쓴다(del 권한이 없어 set으로 교체).
            log.warn("[authz] 캐시 값이 올바르지 않아 재조회 workspace={} user={}", workspaceId, userId);
        }
        Access access = fetchAccess(workspaceId, userId);
        log.debug("[authz] cache miss → 내부 API 판정 workspace={} user={} role={} permissions={}",
                workspaceId, userId, access.role(), access.permissions());
        try {
            redisTemplate.opsForValue().set(key, serialize(access), CACHE_TTL);
        } catch (DataAccessException e) {
            log.warn("[authz] Redis 캐시 저장 실패 workspace={} user={} cause={}", workspaceId, userId, e.toString());
        }
        return access;
    }

    /** 읽을 수 없거나 역할이 OWNER/MEMBER/NONE이 아니면 null(fail-closed: 캐시 miss로 취급). */
    private static Access parseCached(String cached) {
        try {
            RoleResponse parsed = MAPPER.readValue(cached, RoleResponse.class);
            return toAccess(parsed);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static Access toAccess(RoleResponse response) {
        if (response == null || response.role() == null || !VALID_ROLES.contains(response.role())) {
            return null;
        }
        // 권한 필드가 없는 옛 access 응답은 권한 없음으로 읽는다.
        return new Access(response.role(), response.permissions() == null ? List.of() : response.permissions());
    }

    private static String serialize(Access access) {
        try {
            return MAPPER.writeValueAsString(new RoleResponse(access.role(), access.permissions()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Access fetchAccess(String workspaceId, String userId) {
        try {
            RoleResponse response = restClient.get()
                    .uri("/internal/authz/workspaces/{workspaceId}/users/{userId}", workspaceId, userId)
                    .retrieve()
                    .body(RoleResponse.class);
            Access access = toAccess(response);
            if (access == null) {
                throw new WorkspaceNotFoundException(workspaceId);
            }
            return access;
        } catch (RestClientException e) {
            // 내부 API 장애 시 열어주지 않는다(fail-closed).
            log.warn("[authz] 내부 API 실패 — fail-closed workspace={} user={} cause={}",
                    workspaceId, userId, e.toString());
            throw new WorkspaceNotFoundException(workspaceId);
        }
    }

    /** 역할(OWNER | MEMBER | NONE)과 부여된 권한 항목. */
    public record Access(String role, List<String> permissions) {

        public boolean isOwner() {
            return ROLE_OWNER.equals(role);
        }

        /** OWNER는 항상 true, NONE은 false, MEMBER는 부여된 항목만 true. */
        public boolean hasPermission(String permission) {
            return isOwner() || (!ROLE_NONE.equals(role) && permissions.contains(permission));
        }
    }

    record RoleResponse(String role, List<String> permissions) {}
}
