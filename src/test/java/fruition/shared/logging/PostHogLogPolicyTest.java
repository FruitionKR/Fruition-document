package fruition.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class PostHogLogPolicyTest {
    private static final LoggerContext CONTEXT = new LoggerContext();

    private static LoggingEvent event(String logger, Level level, String template, Throwable failure, Object... args) {
        Logger target = CONTEXT.getLogger(logger);
        LoggingEvent event = new LoggingEvent("fqcn", target, level, template, failure, args);
        event.setMDCPropertyMap(Map.of("requestId", "req-1", "userId", "user_1"));
        return event;
    }

    @Test void sendsBusinessInfoAndFailuresButNotRoutineOrInternalLogs() {
        assertThat(PostHogLogPolicy.shouldSend(event("fruition.core.A", Level.INFO, "[완료] operation=document_upload stage=completed", null))).isTrue();
        assertThat(PostHogLogPolicy.shouldSend(event("fruition.shared.logging.HttpRequestLoggingFilter", Level.INFO, "[HTTP 요청 완료] method=GET", null))).isFalse();
        assertThat(PostHogLogPolicy.shouldSend(event("fruition.core.A", Level.DEBUG, "[조회] operation=x", null))).isFalse();
        assertThat(PostHogLogPolicy.shouldSend(event("fruition.core.A", Level.WARN, "[경고]", null))).isTrue();
        assertThat(PostHogLogPolicy.shouldSend(event("org.hibernate.X", Level.WARN, "noise", null))).isFalse();
        assertThat(PostHogLogPolicy.shouldSend(event("org.hibernate.X", Level.ERROR, "failure", null))).isTrue();
        // 전송 실패 로그가 다시 전송되면 무한 재귀가 된다.
        assertThat(PostHogLogPolicy.shouldSend(event("io.opentelemetry.exporter.X", Level.ERROR, "export failed", null))).isFalse();
        assertThat(PostHogLogPolicy.shouldSend(event("okhttp3.OkHttpClient", Level.ERROR, "leak", null))).isFalse();
        assertThat(PostHogLogPolicy.shouldSend(event(PostHogLogExport.class.getName(), Level.WARN, "disabled", null))).isFalse();
        // 감사 JSON은 요청 입력이 섞여 operation=이 들어 있어도 보내지 않는다. 오류는 보낸다.
        assertThat(PostHogLogPolicy.shouldSend(event("fruition.mcp.audit", Level.INFO, "{\"target_ids\":[\"x operation=y\"]}", null))).isFalse();
        assertThat(PostHogLogPolicy.shouldSend(event("fruition.mcp.audit", Level.ERROR, "MCP_AUDIT_SERIALIZATION_FAILED", null))).isTrue();
    }

    @Test void keepsAllowedFieldsAndDropsSensitiveValues() {
        var result = PostHogLogPolicy.sanitize(event("fruition.core.A", Level.WARN,
                "[문서 업로드 실패] operation=document_upload stage=storage errorCode=STORAGE_WRITE_FAILED documentId={} filename={} sourceUri={} email={} token={} message={} 원인=원본 파일을 S3에 저장하지 못했습니다.",
                null, "doc_1", "연봉 협상 자료.pdf", "sources/documents/doc_1/original", "a@b.com", "eyJhbGci.abc.def", "본문 내용 secret-text"));
        assertThat(result.body()).contains("[문서 업로드 실패]", "documentId=doc_1", "errorCode=STORAGE_WRITE_FAILED", "원인=원본 파일을 S3에 저장하지 못했습니다.")
                .doesNotContain("연봉", "sources/documents", "a@b.com", "eyJhbGci", "secret-text");
        assertThat(result.attributes()).containsEntry("operation", "document_upload").containsEntry("stage", "storage")
                .containsEntry("documentId", "doc_1").containsEntry("requestId", "req-1").containsEntry("userId", "user_1")
                .containsEntry("posthogDistinctId", "user_1").containsEntry("cause", "원본 파일을 S3에 저장하지 못했습니다.")
                .doesNotContainKeys("filename", "sourceUri", "email", "token", "message");
    }

    @Test void codeFieldsAcceptOnlyStableCodesAndIdentifierTailsAreDropped() {
        var result = PostHogLogPolicy.sanitize(event("fruition.core.A", Level.WARN,
                "[실패] reason={} errorCode={} runId={} 예약 내용 secret",
                null, "provider said: private text", "rate limit from provider", "run_1"));
        assertThat(result.attributes()).doesNotContainKeys("reason", "errorCode").containsEntry("runId", "run_1");
        assertThat(result.body()).doesNotContain("private text", "provider", "secret");
    }

    @Test void exceptionTextCannotOverrideTemplateFieldsOrAuthenticatedUser() {
        var result = PostHogLogPolicy.sanitize(event("fruition.core.A", Level.WARN,
                "[예외] runId={} code={} message={}", null, "run_real", "X", "사용자를 찾을 수 없습니다: userId=user_other runId=run_fake"));
        assertThat(result.attributes()).containsEntry("runId", "run_real").containsEntry("userId", "user_1")
                .containsEntry("posthogDistinctId", "user_1");
    }

    @Test void chatSessionIsNotMistakenForReplaySession() {
        var result = PostHogLogPolicy.sanitize(event("fruition.core.A", Level.INFO, "[접수] operation=query sessionId={}", null, "sess_1"));
        assertThat(result.attributes()).containsEntry("chatSessionId", "sess_1").doesNotContainKey("sessionId");
    }

    @Test void exceptionKeepsTypeAndFramesButNotMessage() {
        var failure = new IllegalStateException("문서 본문 secret", new RuntimeException("Bearer abc"));
        var result = PostHogLogPolicy.sanitize(event("fruition.core.A", Level.ERROR, "[실패] operation=x", failure));
        assertThat(result.attributes()).containsEntry("exception.type", "java.lang.IllegalStateException")
                .containsEntry("exception.root_type", "java.lang.RuntimeException");
        assertThat(result.attributes().get("exception.stacktrace")).contains("PostHogLogPolicyTest").doesNotContain("secret", "Bearer");
    }

    @Test void thirdPartyMessageIsNeverForwarded() {
        var result = PostHogLogPolicy.sanitize(event("org.postgresql.Driver", Level.ERROR, "connect jdbc:postgresql://u:pw@host failed", null));
        assertThat(result.body()).doesNotContain("pw@host", "jdbc");
        assertThat(result.attributes()).containsEntry("logger", "org.postgresql.Driver");
    }
}
