package fruition.shared.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRequestLoggingFilterTest {

    private final HttpRequestLoggingFilter filter = new HttpRequestLoggingFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void suppliedRequestIdIsExposedDuringRequestAndReturnedInResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/documents");
        request.addHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER, "request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            observed.set(MDC.get("requestId"));
            MDC.put("userId", "user-1");
        });

        assertThat(observed.get()).isEqualTo("request-123");
        assertThat(response.getHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER)).isEqualTo("request-123");
        // 요청 MDC 범위는 가장 바깥인 이 필터가 닫는다. 안쪽 필터가 채운 userId도 함께 치운다.
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("userId")).isNull();
    }

    /**
     * 헬스 체크는 로그만 건너뛴다. 필터를 통째로 건너뛰면 안쪽 필터가 채운 MDC가
     * 워커 스레드에 남아 다음 요청 로그에 잘못 붙는다.
     */
    @Test
    void healthCheckIsNotLoggedButStillClearsMdc() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(HttpRequestLoggingFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        try {
            filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) ->
                    MDC.put("userId", "user-1"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).isEmpty();
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("requestId")).isNull();
    }

    /**
     * 완료 로그는 안쪽 필터가 채운 userId를 담아야 한다. 정리를 안쪽으로 옮기면
     * 바깥인 이 필터가 찍기 전에 값이 지워져 status·elapsedMs 줄이 주체를 잃는다.
     */
    @Test
    void completionLogCarriesUserIdFilledByInnerFilters() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(HttpRequestLoggingFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/documents");

        try {
            filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) ->
                    MDC.put("userId", "user-1"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        ILoggingEvent completion = appender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("[HTTP 요청 완료]"))
                .findFirst()
                .orElseThrow();
        assertThat(completion.getMDCPropertyMap()).containsEntry("userId", "user-1");
    }

    @Test
    void invalidRequestIdIsReplaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/documents");
        request.addHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER, "invalid request id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {});

        assertThat(response.getHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER))
                .isNotBlank()
                .isNotEqualTo("invalid request id");
        assertThat(MDC.get("requestId")).isNull();
    }
}
