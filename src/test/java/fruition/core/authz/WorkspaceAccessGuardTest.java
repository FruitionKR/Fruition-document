package fruition.core.authz;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WorkspaceAccessGuardTest {

    private static final String WS = "ws_1";
    private static final String USER = "user_1";
    private static final String KEY = "authz:role:ws_1:user_1";
    private static final String ROLE_URL = "http://access/internal/authz/workspaces/ws_1/users/user_1";

    // Redis를 in-memory Map으로 흉내 낸다(값·TTL 기록).
    private final Map<String, String> redisData = new LinkedHashMap<>();
    private final Map<String, Duration> redisTtls = new HashMap<>();

    private final RestClient.Builder restClientBuilder = RestClient.builder().baseUrl("http://access");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
    private final WorkspaceAccessGuard guard =
            new WorkspaceAccessGuard(fakeRedisTemplate(), restClientBuilder.build());

    @SuppressWarnings("unchecked")
    private StringRedisTemplate fakeRedisTemplate() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> redisData.get(invocation.getArgument(0, String.class)));
        doAnswer(invocation -> {
            redisData.put(invocation.getArgument(0), invocation.getArgument(1));
            redisTtls.put(invocation.getArgument(0), invocation.getArgument(2));
            return null;
        }).when(valueOperations).set(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class));
        return redisTemplate;
    }

    @Test
    void requireMember_redisHitOwner_passesWithoutInternalApiCall() {
        redisData.put(KEY, "OWNER");

        assertThatCode(() -> guard.requireMember(WS, USER)).doesNotThrowAnyException();
        server.verify(); // 내부 API 호출이 없어야 한다
    }

    @Test
    void requireMember_redisHitNone_failsClosedWithoutInternalApiCall() {
        redisData.put(KEY, "NONE");

        assertThatThrownBy(() -> guard.requireMember(WS, USER))
                .isInstanceOf(WorkspaceNotFoundException.class);
        server.verify();
    }

    @Test
    void requireMember_redisMiss_fallsBackToInternalApiAndCachesResult() {
        server.expect(requestTo(ROLE_URL))
                .andRespond(withSuccess("{\"role\":\"MEMBER\"}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> guard.requireMember(WS, USER)).doesNotThrowAnyException();

        server.verify();
        assertThat(redisData).containsEntry(KEY, "MEMBER");
        assertThat(redisTtls.get(KEY)).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void requireMember_redisMiss_cachesNoneVerdictToo() {
        server.expect(requestTo(ROLE_URL))
                .andRespond(withSuccess("{\"role\":\"NONE\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> guard.requireMember(WS, USER))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThat(redisData).containsEntry(KEY, "NONE");
    }

    @Test
    void requireMember_internalApiFailure_failsClosedWithoutCaching() {
        server.expect(requestTo(ROLE_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> guard.requireMember(WS, USER))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThat(redisData).doesNotContainKey(KEY);
    }

    @Test
    void isOwner_distinguishesOwnerFromMember() {
        redisData.put(KEY, "OWNER");
        assertThat(guard.isOwner(WS, USER)).isTrue();

        redisData.put(KEY, "MEMBER");
        assertThat(guard.isOwner(WS, USER)).isFalse();
    }
}
