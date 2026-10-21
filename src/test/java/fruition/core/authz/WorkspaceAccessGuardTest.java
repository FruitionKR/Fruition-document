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
    private static final String KEY = "authz:role:v2:ws_1:user_1";
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
        redisData.put(KEY, "{\"role\":\"OWNER\",\"permissions\":[]}");

        assertThatCode(() -> guard.requireMember(WS, USER)).doesNotThrowAnyException();
        server.verify(); // 내부 API 호출이 없어야 한다
    }

    @Test
    void requireMember_redisHitNone_failsClosedWithoutInternalApiCall() {
        redisData.put(KEY, "{\"role\":\"NONE\",\"permissions\":[]}");

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
        assertThat(redisData).containsEntry(KEY, "{\"role\":\"MEMBER\",\"permissions\":[]}");
        assertThat(redisTtls.get(KEY)).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void requireMember_redisMiss_cachesNoneVerdictToo() {
        server.expect(requestTo(ROLE_URL))
                .andRespond(withSuccess("{\"role\":\"NONE\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> guard.requireMember(WS, USER))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThat(redisData).containsEntry(KEY, "{\"role\":\"NONE\",\"permissions\":[]}");
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
        redisData.put(KEY, "{\"role\":\"OWNER\",\"permissions\":[]}");
        assertThat(guard.isOwner(WS, USER)).isTrue();

        redisData.put(KEY, "{\"role\":\"MEMBER\",\"permissions\":[]}");
        assertThat(guard.isOwner(WS, USER)).isFalse();
    }

    @Test
    void hasPermission_memberWithGrantedItem_fromApi_cachesJsonWithPermissionsAndReadsOnce() {
        server.expect(requestTo(ROLE_URL)).andRespond(withSuccess(
                "{\"role\":\"MEMBER\",\"permissions\":[\"wiki.ingest\"]}", MediaType.APPLICATION_JSON));

        assertThat(guard.hasPermission(WS, USER, "wiki.ingest")).isTrue();
        // 두 번째는 캐시에서 읽는다(내부 API 호출은 한 번만 기대했다).
        assertThat(guard.hasPermission(WS, USER, "wiki.lint")).isFalse();

        server.verify();
        assertThat(redisData).containsEntry(KEY, "{\"role\":\"MEMBER\",\"permissions\":[\"wiki.ingest\"]}");
        assertThat(redisTtls.get(KEY)).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void access_returnsOwnerAndPermissionVerdictsFromOneFetch() {
        server.expect(org.springframework.test.web.client.ExpectedCount.once(), requestTo(ROLE_URL)).andRespond(withSuccess(
                "{\"role\":\"MEMBER\",\"permissions\":[\"document.convert\"]}", MediaType.APPLICATION_JSON));

        WorkspaceAccessGuard.Access access = guard.access(WS, USER);

        assertThat(access.isOwner()).isFalse();
        assertThat(access.hasPermission("document.convert")).isTrue();
        assertThat(access.hasPermission("wiki.ingest")).isFalse();
        server.verify();
    }

    @Test
    void access_ownerHasEveryPermissionAndNoneHasNone() {
        redisData.put(KEY, "{\"role\":\"OWNER\",\"permissions\":[]}");
        assertThat(guard.access(WS, USER).isOwner()).isTrue();
        assertThat(guard.access(WS, USER).hasPermission("document.convert")).isTrue();

        redisData.put(KEY, "{\"role\":\"NONE\",\"permissions\":[\"document.convert\"]}");
        assertThat(guard.access(WS, USER).hasPermission("document.convert")).isFalse();
        server.verify();
    }

    @Test
    void requirePermission_roleAndPermissionsComeFromOneFetch() {
        server.expect(org.springframework.test.web.client.ExpectedCount.once(), requestTo(ROLE_URL)).andRespond(withSuccess(
                "{\"role\":\"MEMBER\",\"permissions\":[\"skill.author\"]}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> guard.requirePermission(WS, USER, "skill.author")).doesNotThrowAnyException();
        assertThat(guard.getRole(WS, USER)).isEqualTo("MEMBER");

        server.verify();
    }

    @Test
    void cachedJsonValue_isParsed() {
        redisData.put(KEY, "{\"role\":\"MEMBER\",\"permissions\":[\"wiki.restore\"]}");

        assertThat(guard.getRole(WS, USER)).isEqualTo("MEMBER");
        assertThat(guard.hasPermission(WS, USER, "wiki.restore")).isTrue();
        assertThat(guard.hasPermission(WS, USER, "wiki.ingest")).isFalse();
        server.verify();
    }

    @Test
    void invalidCachedValues_areTreatedAsMissAndOverwrittenFromApi() {
        for (String bad : new String[]{"MEMBER", "not json", "{}", "{\"role\":null}", "{\"role\":\"ADMIN\"}"}) {
            redisData.put(KEY, bad);
            server.reset();
            server.expect(requestTo(ROLE_URL)).andRespond(withSuccess(
                    "{\"role\":\"MEMBER\",\"permissions\":[\"wiki.lint\"]}", MediaType.APPLICATION_JSON));

            assertThat(guard.hasPermission(WS, USER, "wiki.lint")).as(bad).isTrue();

            server.verify();
            assertThat(redisData).containsEntry(KEY, "{\"role\":\"MEMBER\",\"permissions\":[\"wiki.lint\"]}");
        }
    }

    @Test
    void invalidApiResponse_failsClosedWithoutCaching() {
        for (String bad : new String[]{"{}", "{\"role\":\"ADMIN\"}"}) {
            server.reset();
            server.expect(requestTo(ROLE_URL)).andRespond(withSuccess(bad, MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> guard.requireMember(WS, USER))
                    .isInstanceOf(WorkspaceNotFoundException.class);
            assertThat(redisData).doesNotContainKey(KEY);
        }
    }

    @Test
    void apiResponseWithoutPermissionsField_isTreatedAsEmpty() {
        server.expect(requestTo(ROLE_URL))
                .andRespond(withSuccess("{\"role\":\"MEMBER\"}", MediaType.APPLICATION_JSON));

        assertThat(guard.hasPermission(WS, USER, "wiki.ingest")).isFalse();
    }

    @Test
    void owner_hasEveryPermissionImplicitly() {
        redisData.put(KEY, "{\"role\":\"OWNER\",\"permissions\":[]}");

        assertThat(guard.hasPermission(WS, USER, "wiki.ingest")).isTrue();
        assertThat(guard.hasPermission(WS, USER, "document.convert")).isTrue();
        assertThatCode(() -> guard.requirePermission(WS, USER, "skill.author")).doesNotThrowAnyException();
    }

    @Test
    void none_hasNoPermission_andRequirePermissionIs404NotFound() {
        redisData.put(KEY, "{\"role\":\"NONE\",\"permissions\":[\"wiki.ingest\"]}");

        assertThat(guard.hasPermission(WS, USER, "wiki.ingest")).isFalse();
        assertThatThrownBy(() -> guard.requirePermission(WS, USER, "wiki.ingest"))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void requirePermission_memberWithoutItem_throws403ExceptionNamingTheItem() {
        redisData.put(KEY, "{\"role\":\"MEMBER\",\"permissions\":[]}");

        assertThatThrownBy(() -> guard.requirePermission(WS, USER, "wiki.ingest"))
                .isInstanceOf(WorkspacePermissionRequiredException.class)
                .hasMessage("이 작업에는 'wiki.ingest' 권한이 필요합니다. 워크스페이스 OWNER에게 요청하세요.");
    }
}
