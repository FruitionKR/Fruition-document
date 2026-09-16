package fruition.shared.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class BaseExceptionHandlerTest {

    /** BaseExceptionHandler는 추상이라 앱별 매핑을 더하지 않는 최소 구현으로 공통 동작만 검증한다. */
    private static class TestExceptionHandler extends BaseExceptionHandler {}

    private final TestExceptionHandler handler = new TestExceptionHandler();
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(BaseExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private ILoggingEvent onlyEvent() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "uq_documents_active_name", "uq_folders_active_name", "uq_workspaces_owner_active_name"})
    void duplicateNamesReturnConflictWithActionableMessage(String constraint) {
        var violation = new org.hibernate.exception.ConstraintViolationException(
                "duplicate", new java.sql.SQLException("duplicate", "23505"), constraint);
        var response = handler.handleUnexpected(new org.springframework.dao.DataIntegrityViolationException("write failed", violation));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error().code()).isEqualTo("DUPLICATE_NAME");
        assertThat(response.getBody().error().message()).contains("다른 이름을 사용");
        assertThat(onlyEvent().getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void unrelatedConstraintIsNotReportedAsDuplicateName() {
        var violation = new org.hibernate.exception.ConstraintViolationException(
                "foreign key", new java.sql.SQLException("foreign key", "23503"), "fk_document");
        assertThat(handler.handleUnexpected(violation).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void clientError_isLoggedAsWarnWithoutStackTrace() {
        var response = handler.handleMultipartException(new MultipartException("파일 없음"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        // 4xx는 정상 운영 중에도 생기므로 스택트레이스를 남기지 않는다.
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage())
                .contains("type=MultipartException")
                .contains("code=INVALID_REQUEST")
                .contains("status=400");
    }

    @Test
    void unmappedException_becomesInternalErrorAndIsLoggedWithStackTrace() {
        var response = handler.handleUnexpected(new IllegalStateException("예상 못 한 오류"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        // 5xx는 원인 추적이 필요하므로 예외를 함께 남긴다.
        assertThat(event.getThrowableProxy()).isNotNull();
        assertThat(event.getFormattedMessage()).contains("status=500");
    }

    /**
     * Spring이 상태 코드를 담아 던지는 예외는 최종 처리기가 그 상태를 보존해야 한다.
     * 보존하지 않으면 없는 경로 요청의 404가 500으로 바뀐다.
     */
    @Test
    void springStatusCarryingException_keepsItsOwnStatus() {
        var response = handler.handleUnexpected(
                new NoResourceFoundException(HttpMethod.GET, "/internal/nope"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error().code()).isEqualTo("REQUEST_FAILED");

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("status=404");
    }

    /** 직접 상태를 지정해 던진 예외도 최종 처리기에서 500으로 뭉개지지 않아야 한다. */
    @Test
    void responseStatusException_keepsItsOwnStatusAndReason() {
        var response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent를 확인할 수 없습니다."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error().code()).isEqualTo("REQUEST_FAILED");
        assertThat(response.getBody().error().message()).isEqualTo("Agent를 확인할 수 없습니다.");

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).contains("status=503");
    }

    /** 요청 컨텍스트 밖에서 호출돼도 로깅이 예외로 끊기지 않아야 한다. */
    @Test
    void logging_worksOutsideRequestContext() {
        handler.handleMultipartException(new MultipartException("파일 없음"));

        assertThat(onlyEvent().getFormattedMessage()).contains("method=- uri=-");
    }
}
