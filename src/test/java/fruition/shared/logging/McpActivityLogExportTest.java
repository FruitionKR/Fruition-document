package fruition.shared.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.mcp.McpAuditLogger;
import fruition.core.mcp.McpIdentity;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 McpAuditLogger → PostHogLogExport 어댑터 → 기록용 전송기. 네트워크를 쓰지 않는다. */
class McpActivityLogExportTest {
    private static String attr(LogRecordData record, String key) {
        return record.getAttributes().get(AttributeKey.stringKey(key));
    }

    @Test void exportsSafeMcpToolFieldsAndRejectsInjectedBodiesAndSecrets() {
        List<LogRecordData> sent = new CopyOnWriteArrayList<>();
        PostHogLogExport export = new PostHogLogExport(true, "phc_x", "https://x", "document-svc", "local", "1");
        export.install(new LogRecordExporter() {
            @Override public CompletableResultCode export(Collection<LogRecordData> logs) { sent.addAll(logs); return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        });
        var id = new McpIdentity("user_1", "ws_1", "https://client.example/meta.json", "grant-1", 1,
                Set.of("wiki.read"), "OWNER", Set.of());
        var audit = new McpAuditLogger(new ObjectMapper().findAndRegisterModules());
        MDC.put("requestId", "req-7");
        audit.log(id, "get_wiki_page", List.of("page-a", "doc_2"), "SUCCESS", null);
        // 요청 입력에서 온 대상에 본문·토큰·key=value 주입을 섞는다.
        audit.log(id, "propose_document_edit", List.of("doc_3", "doc_9 documentId=evil operation=spoof",
                "# 비밀 본문 Bearer abc.def eyJhbGci.x.y phc_secret"), "ERROR", "INVALID_INPUT");
        MDC.clear();
        // 전역 전송기는 다른 요청의 라이브러리 오류도 함께 수집한다.
        LoggerFactory.getLogger("org.springframework.kafka.support.LoggingProducerListener")
                .error("Unrelated producer failure", new org.apache.kafka.common.errors.TimeoutException("test timeout"));
        export.stop();

        // 감사 JSON(fruition.mcp.audit)은 전송하지 않고 정화된 업무 로그만 나간다.
        assertThat(sent).noneSatisfy(r -> assertThat(attr(r, "logger")).isEqualTo("fruition.mcp.audit"));
        assertThat(sent).anySatisfy(r -> {
            assertThat(attr(r, "logger")).isEqualTo("org.springframework.kafka.support.LoggingProducerListener");
            assertThat(attr(r, "exception.type")).isEqualTo("org.apache.kafka.common.errors.TimeoutException");
        });
        List<LogRecordData> activity = sent.stream()
                .filter(r -> "fruition.mcp.activity".equals(attr(r, "logger")) && "req-7".equals(attr(r, "requestId")))
                .toList();
        assertThat(activity).hasSize(2);
        LogRecordData ok = activity.get(0);
        assertThat(ok.getSeverityText()).isEqualTo("info");
        assertThat(attr(ok, "operation")).isEqualTo("mcp_tool");
        assertThat(attr(ok, "status")).isEqualTo("SUCCESS");
        assertThat(attr(ok, "toolName")).isEqualTo("get_wiki_page");
        assertThat(attr(ok, "userId")).isEqualTo("user_1");
        assertThat(attr(ok, "posthogDistinctId")).isEqualTo("user_1");
        assertThat(attr(ok, "workspaceId")).isEqualTo("ws_1");
        assertThat(attr(ok, "grantId")).isEqualTo("grant-1");
        assertThat(attr(ok, "clientIdHash")).matches("[0-9a-f]{16}");
        assertThat(attr(ok, "targetIds")).isEqualTo("page-a,doc_2");
        assertThat(attr(ok, "targetCount")).isEqualTo("2");
        assertThat(attr(ok, "requestId")).isEqualTo("req-7");
        assertThat(attr(ok, "auditId")).matches("[0-9a-f-]{36}");
        assertThat(ok.getTimestampEpochNanos()).isPositive();

        LogRecordData failed = activity.get(1);
        assertThat(failed.getSeverityText()).isEqualTo("warn");
        assertThat(attr(failed, "errorCode")).isEqualTo("INVALID_INPUT");
        assertThat(attr(failed, "status")).isEqualTo("ERROR");
        assertThat(attr(failed, "operation")).isEqualTo("mcp_tool");
        assertThat(attr(failed, "targetIds")).isEqualTo("doc_3");
        assertThat(attr(failed, "targetCount")).isEqualTo("3");
        assertThat(attr(failed, "documentId")).isNull();
        assertThat(attr(failed, "clientIdHash")).isEqualTo(attr(ok, "clientIdHash"));
        for (LogRecordData record : sent) {
            String all = record.getBodyValue().asString() + record.getAttributes().asMap().values();
            assertThat(all).doesNotContain("evil", "spoof", "비밀", "Bearer", "eyJhbGci", "phc_secret", "client.example");
        }
    }
}
