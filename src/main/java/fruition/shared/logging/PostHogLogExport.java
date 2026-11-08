package fruition.shared.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.sdk.common.export.RetryPolicy;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.resources.Resource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 서버 로그를 콘솔과 함께 PostHog Logs(OTLP)로 보낸다. POSTHOG_LOGS_ENABLED=true이고 프로젝트 토큰이 있을 때만 켠다.
 * 호출 스레드는 제한된 큐에 넣기만 해서 네트워크 장애가 요청·트랜잭션을 막지 않고, 큐가 차면 전송만 버린다.
 */
@Component
public class PostHogLogExport {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(PostHogLogExport.class);
    private static final String APPENDER_NAME = "POSTHOG_LOGS";

    private final boolean enabled;
    private final String token;
    private final String host;
    private final String service;
    private final String environment;
    private final String version;
    private SdkLoggerProvider provider;
    private Appender appender;

    public PostHogLogExport(@Value("${POSTHOG_LOGS_ENABLED:false}") boolean enabled,
                            @Value("${POSTHOG_PROJECT_TOKEN:}") String token,
                            @Value("${POSTHOG_HOST:https://us.i.posthog.com}") String host,
                            @Value("${spring.application.name:unknown}") String service,
                            @Value("${ANALYTICS_ENVIRONMENT:local}") String environment,
                            @Value("${APP_VERSION:unknown}") String version) {
        this.enabled = enabled;
        this.token = token;
        this.host = host;
        this.service = service;
        this.environment = environment;
        this.version = version;
    }

    @PostConstruct
    void start() {
        if (!enabled) return;
        if (token == null || !token.matches("phc_[A-Za-z0-9_-]+")) {
            log.warn("[PostHog 로그 전송 비활성] POSTHOG_LOGS_ENABLED가 켜졌지만 프로젝트 토큰이 없거나 형식이 올바르지 않습니다.");
            return;
        }
        try {
            install(OtlpHttpLogRecordExporter.builder()
                    .setEndpoint(host.replaceAll("/+$", "") + "/i/v1/logs")
                    .addHeader("Authorization", "Bearer " + token)
                    .setTimeout(Duration.ofSeconds(5))
                    .setConnectTimeout(Duration.ofSeconds(3))
                    .setRetryPolicy(RetryPolicy.builder().setMaxAttempts(3).build())
                    .build());
            log.info("[PostHog 로그 전송 활성] service={} environment={} version={}", service, environment, version);
        } catch (RuntimeException e) {
            // 설정 오류가 앱 기동을 막지 않는다. 메시지에 설정값이 섞일 수 있어 타입만 남긴다.
            log.warn("[PostHog 로그 전송 비활성] errorType={} 원인=전송기를 초기화하지 못했습니다.", e.getClass().getSimpleName());
        }
    }

    void install(LogRecordExporter exporter) {
        provider = SdkLoggerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), service,
                        AttributeKey.stringKey("deployment.environment"), environment,
                        AttributeKey.stringKey("service.version"), version))))
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(exporter)
                        .setMaxQueueSize(2048)
                        .setMaxExportBatchSize(256)
                        .setScheduleDelay(Duration.ofSeconds(5))
                        .setExporterTimeout(Duration.ofSeconds(10))
                        .build())
                .build();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        appender = new Appender(provider.get("fruition-server"));
        appender.setContext(context);
        appender.setName(APPENDER_NAME);
        appender.start();
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    /** 종료 시 큐를 비운다. 전송처가 죽어 있어도 종료를 오래 막지 않는다. */
    @PreDestroy
    void stop() {
        if (provider == null) return;
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        appender.stop();
        provider.shutdown().join(10, TimeUnit.SECONDS);
        provider = null;
    }

    private static final class Appender extends AppenderBase<ILoggingEvent> {
        private final Logger logger;

        Appender(Logger logger) { this.logger = logger; }

        @Override
        protected void append(ILoggingEvent event) {
            if (!PostHogLogPolicy.shouldSend(event)) return;
            PostHogLogPolicy.Sanitized record = PostHogLogPolicy.sanitize(event);
            LogRecordBuilder builder = logger.logRecordBuilder()
                    .setTimestamp(event.getInstant())
                    .setSeverity(severity(event))
                    .setSeverityText(event.getLevel().toString().toLowerCase(java.util.Locale.ROOT)) // PostHog 레벨 필터는 소문자 값과 정확히 일치해야 한다
                    .setBody(record.body());
            record.attributes().forEach((key, value) -> builder.setAttribute(AttributeKey.stringKey(key), value));
            builder.emit();
        }

        private static Severity severity(ILoggingEvent event) {
            return switch (event.getLevel().toInt()) {
                case ch.qos.logback.classic.Level.ERROR_INT -> Severity.ERROR;
                case ch.qos.logback.classic.Level.WARN_INT -> Severity.WARN;
                default -> Severity.INFO;
            };
        }
    }
}
