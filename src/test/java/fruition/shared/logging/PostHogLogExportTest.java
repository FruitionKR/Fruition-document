package fruition.shared.logging;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PostHogLogExportTest {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger("fruition.test.PostHogLogExportTest");

    private static PostHogLogExport export(boolean enabled, String token, String host) {
        return new PostHogLogExport(enabled, token, host, "document-svc", "local", "1.2.3");
    }

    private static int rootAppenders() {
        var root = ((ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("ROOT");
        int count = 0;
        for (var it = root.iteratorForAppenders(); it.hasNext(); it.next()) count++;
        return count;
    }

    @Test void missingConfigurationLeavesAppRunningWithoutAppender() {
        int before = rootAppenders();
        for (PostHogLogExport export : List.of(export(false, "phc_real", "https://x"), export(true, "", "https://x"), export(true, "not-a-token", "https://x"))) {
            assertThatCode(export::start).doesNotThrowAnyException();
            assertThat(rootAppenders()).isEqualTo(before);
            assertThatCode(export::stop).doesNotThrowAnyException();
        }
    }

    @Test void flushesOnShutdownWithResourceAndCorrelationAttributes() {
        List<LogRecordData> sent = new CopyOnWriteArrayList<>();
        PostHogLogExport export = export(true, "phc_x", "https://x");
        export.install(new LogRecordExporter() {
            @Override public CompletableResultCode export(Collection<LogRecordData> logs) { sent.addAll(logs); return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        });
        MDC.put("requestId", "req-9");
        log.info("[문서 업로드 완료] operation=document_upload stage=completed documentId={} filename={}", "doc_9", "비밀.pdf");
        log.info("[HTTP 요청 완료] method=GET status=200");
        MDC.clear();
        export.stop();

        assertThat(sent).hasSize(1);
        LogRecordData record = sent.getFirst();
        assertThat(record.getBodyValue().asString()).contains("documentId=doc_9").doesNotContain("비밀.pdf");
        assertThat(record.getSeverityText()).isEqualTo("info");
        assertThat(record.getAttributes().get(AttributeKey.stringKey("requestId"))).isEqualTo("req-9");
        assertThat(record.getAttributes().get(AttributeKey.stringKey("operation"))).isEqualTo("document_upload");
        assertThat(record.getResource().getAttribute(AttributeKey.stringKey("service.name"))).isEqualTo("document-svc");
        assertThat(record.getResource().getAttribute(AttributeKey.stringKey("deployment.environment"))).isEqualTo("local");
        assertThat(record.getResource().getAttribute(AttributeKey.stringKey("service.version"))).isEqualTo("1.2.3");
    }

    @Test void deadEndpointDoesNotBlockLoggingOrThrow() {
        PostHogLogExport export = export(true, "phc_x", "http://127.0.0.1:1");
        export.start();
        long startedAt = System.nanoTime();
        assertThatCode(() -> {
            for (int i = 0; i < 5000; i++) log.error("[실패] operation=x stage=y attempt={}", i); // 큐(2048)를 넘겨도 버리기만 한다
        }).doesNotThrowAnyException();
        assertThat((System.nanoTime() - startedAt) / 1_000_000).isLessThan(5000);
        assertThatCode(export::stop).doesNotThrowAnyException();
    }
}
