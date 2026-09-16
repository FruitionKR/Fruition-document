package fruition.core;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fruition.core.skill.exception.PipelineSkillException;
import fruition.core.wiki.exception.PipelineWikiPageException;
import fruition.shared.util.BaseExceptionHandler;
import fruition.shared.util.ErrorResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pipeline 응답을 그대로 중계하는 분기는 우리 ErrorResponse code를 쓰지 않는다.
 * 로그에 우리 code를 적으면 사용자가 알려준 code로 로그를 찾을 때 엉뚱한 줄이 걸린다.
 */
class CoreExceptionHandlerTest {

    private final CoreExceptionHandler handler = new CoreExceptionHandler();
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

    private String onlyLoggedMessage() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0).getFormattedMessage();
    }

    @Test
    @DisplayName("pipeline 응답을 중계하면 우리 code를 로그에 적지 않는다")
    void relayedPipelineResponseIsNotLoggedWithOurCode() {
        var response = handler.handlePipelineWikiPage(
                new PipelineWikiPageException("pipeline 거절", 422, "{\"error\":{\"code\":\"PIPELINE_SAYS_NO\"}}"));

        assertThat(response.getBody()).isEqualTo("{\"error\":{\"code\":\"PIPELINE_SAYS_NO\"}}");
        assertThat(onlyLoggedMessage())
                .contains("code=PIPELINE_RESPONSE_RELAYED")
                .contains("status=422")
                .doesNotContain("WIKI_PAGE_PIPELINE_UNAVAILABLE");
    }

    @Test
    @DisplayName("중계할 응답이 없으면 실제로 내보내는 code를 로그에 적는다")
    void fallbackResponseIsLoggedWithTheCodeItReturns() {
        var response = handler.handlePipelineWikiPage(
                new PipelineWikiPageException("pipeline 응답 없음", 503, null));

        assertThat(onlyLoggedMessage())
                .contains("code=WIKI_PAGE_PIPELINE_UNAVAILABLE")
                .contains("status=503");
        assertThat(((ErrorResponse) response.getBody()).error().code())
                .isEqualTo("WIKI_PAGE_PIPELINE_UNAVAILABLE");
    }

    @Test
    @DisplayName("Skill 413 중계 분기도 같은 규칙을 따른다")
    void relayedSkillPayloadTooLargeFollowsTheSameRule() {
        handler.handlePipelineSkill(
                new PipelineSkillException("너무 큼", 413, "{\"error\":{\"code\":\"SKILL_TOO_BIG\"}}"));

        assertThat(onlyLoggedMessage())
                .contains("code=PIPELINE_RESPONSE_RELAYED")
                .contains("status=413");
    }
}
