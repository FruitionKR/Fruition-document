package fruition.shared.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** userId 제거는 요청 MDC 범위를 여닫는 HttpRequestLoggingFilter가 맡는다. 여기서는 채우는 것만 본다. */
class JwtAuthenticationFilterTest {

    private final JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtTokenProvider);

    @AfterEach
    void clearContext() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenPutsUserIdInMdcForTheRestOfTheRequest() throws Exception {
        when(jwtTokenProvider.isValid("token-1")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("token-1")).thenReturn("user-1");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/documents");
        request.addHeader("Authorization", "Bearer token-1");
        AtomicReference<String> observed = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) ->
                observed.set(MDC.get("userId")));

        assertThat(observed.get()).isEqualTo("user-1");
    }

    @Test
    void invalidTokenDoesNotSetUserId() throws Exception {
        when(jwtTokenProvider.isValid("token-1")).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/documents");
        request.addHeader("Authorization", "Bearer token-1");
        AtomicReference<String> observed = new AtomicReference<>("설정되지 않음");

        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) ->
                observed.set(MDC.get("userId")));

        assertThat(observed.get()).isNull();
        assertThat(MDC.get("userId")).isNull();
    }
}
