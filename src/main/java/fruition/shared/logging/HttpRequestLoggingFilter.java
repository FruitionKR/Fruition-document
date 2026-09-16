package fruition.shared.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** HTTP 요청 하나의 로그를 같은 requestId로 묶는다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpRequestLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    private static final Logger log = LoggerFactory.getLogger(HttpRequestLoggingFilter.class);
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final String HEALTH_PATH = "/actuator/health";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 헬스 체크는 주기적으로 반복되고 진단 가치가 없어 로그를 남기지 않는다. 필터 자체는
        // 건너뛰지 않는다 — 안쪽 필터가 채운 MDC를 여기서 치우므로, 건너뛰면 값이 스레드에 남는다.
        boolean logged = !HEALTH_PATH.equals(request.getRequestURI());
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
        long startedAt = System.nanoTime();
        MDC.put("requestId", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        if (logged) {
            log.info("[HTTP 요청 시작] method={} uri={}", request.getMethod(), LoggableUri.mask(request.getRequestURI()));
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (logged) {
                long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
                log.info("[HTTP 요청 완료] method={} uri={} status={} elapsedMs={}",
                        request.getMethod(), LoggableUri.mask(request.getRequestURI()), response.getStatus(), elapsedMs);
            }
            // 요청 MDC 범위는 가장 바깥인 이 필터가 닫는다. userId는 JwtAuthenticationFilter가 채운다.
            MDC.remove("userId");
            MDC.remove("requestId");
        }
    }

    private String resolveRequestId(String candidate) {
        if (candidate != null && VALID_REQUEST_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
