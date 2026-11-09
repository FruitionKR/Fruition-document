package fruition.core.agent.repository;

import com.sun.net.httpserver.HttpServer;
import fruition.core.agent.exception.AgentRunNotFoundException;
import fruition.core.agent.exception.PipelineAgentException;
import fruition.shared.http.PipelineClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineAgentRunStatusRequesterTest {

    private HttpServer server;
    private final AtomicReference<String> uri = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicBoolean slow = new AtomicBoolean();
    private final AtomicInteger failureStatus = new AtomicInteger();
    private final AtomicReference<String> failureBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/agent/runs", exchange -> {
            uri.set(exchange.getRequestURI().toString());
            token.set(exchange.getRequestHeaders().getFirst("X-Agent-Service-Token"));
            method.set(exchange.getRequestMethod());
            try (InputStream input = exchange.getRequestBody()) {
                body.set(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            if (slow.get()) {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
            if (exchange.getRequestURI().getQuery() != null
                    && exchange.getRequestURI().getQuery().contains("workspace_id=foreign")) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            if (failureStatus.get() != 0) {
                byte[] response = failureBody.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(failureStatus.get(), response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            if (exchange.getRequestURI().getPath().endsWith("/approve")) {
                exchange.sendResponseHeaders(409, 0);
                exchange.getResponseBody().write("{\"detail\":\"stale plan\"}".getBytes(StandardCharsets.UTF_8));
                exchange.close();
                return;
            }
            if (exchange.getRequestURI().getPath().endsWith("/mcp-owned")) {
                byte[] response = """
                        {"items":[{"run_id":"run-mcp-1","status":"awaiting_approval"}],
                         "next_cursor":"cursor-2"}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            byte[] response = """
                    {"id":"run-1","workspace_id":"ws-1","action":"workspace_workflow",
                     "status":"completed","request_summary":"정식 요청","error_code":null,
                     "plan":{"id":"plan-1","version":1,"summary":"정식 계획","operation_hash":"hash",
                     "status":"approved","operations":[{"id":"operation-1","sequence":1,
                     "tool_name":"move_document","target_type":"document","target_id":"doc-1",
                     "base_version":1,"source_parent_id":null,"destination_parent_id":"folder-1",
                     "arguments":{},"reason":"정식 이유","depends_on":[],"status":"succeeded",
                     "error_code":null}]}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static final String PREVIEW_MARKER = "미리보기본문표식";

    private void respondEditPreviews(int status, String body) {
        server.createContext("/agent/runs/run-1/edit-previews", exchange -> {
            uri.set(exchange.getRequestURI().toString());
            token.set(exchange.getRequestHeaders().getFirst("X-Agent-Service-Token"));
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
            if (response.length > 0) exchange.getResponseBody().write(response);
            exchange.close();
        });
    }

    @Test
    void getEditPreviewsSendsActorAndReadsContractShape() {
        respondEditPreviews(200, """
                {"expires_at":"2026-11-05T00:00:00Z","items":[{"operation_id":"op-1","document_id":"doc-1",
                 "display_name":"AI 제목","base_version":3,"summary":"요약","added_lines":1,"removed_lines":2,
                 "hunks":[{"before_start":4,"after_start":5,"lines":[{"type":"insert","text":"새 줄"}]}]}]}
                """);

        var previews = requester().getEditPreviews("ws-1", "user 1", "run-1");

        assertThat(uri.get()).isEqualTo("/agent/runs/run-1/edit-previews?workspace_id=ws-1&user_id=user%201");
        assertThat(token.get()).isEqualTo("agent-token");
        assertThat(previews.expiresAt()).isEqualTo("2026-11-05T00:00:00Z");
        var item = previews.items().getFirst();
        assertThat(item.operationId()).isEqualTo("op-1");
        assertThat(item.documentId()).isEqualTo("doc-1");
        assertThat(item.baseVersion()).isEqualTo(3);
        assertThat(item.addedLines()).isEqualTo(1);
        assertThat(item.removedLines()).isEqualTo(2);
        assertThat(item.hunks().getFirst().beforeStart()).isEqualTo(4);
        assertThat(item.hunks().getFirst().afterStart()).isEqualTo(5);
        assertThat(item.hunks().getFirst().lines().getFirst().text()).isEqualTo("새 줄");
    }

    /** 다른 사용자의 run이거나 AI가 아직 이 API를 모를 때. 그대로 404다. */
    @Test
    void getEditPreviewsTranslatesNotFound() {
        respondEditPreviews(404, "");

        assertThatThrownBy(() -> requester().getEditPreviews("ws-1", "user-1", "run-1"))
                .isInstanceOf(AgentRunNotFoundException.class);
    }

    /** 오류 본문이나 깨진 응답에 문서 내용이 섞여 있어도 예외(따라서 로그)로 새지 않는다. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "500|{\"detail\":\"미리보기본문표식\"}",
            "200|{\"items\":\"미리보기본문표식\"}",
            "200|미리보기본문표식"})
    void getEditPreviewsFailureCarriesNoDocumentText(int status, String body) {
        respondEditPreviews(status, body);

        assertThatThrownBy(() -> requester().getEditPreviews("ws-1", "user-1", "run-1"))
                .isInstanceOfSatisfying(PipelineAgentException.class, exception -> {
                    assertThat(exception.getHttpStatus()).isEqualTo(503);
                    assertThat(exception.getMessage()).doesNotContain(PREVIEW_MARKER);
                    assertThat(exception.getResponseBody()).isNull();
                    assertThat(exception.getCause()).isNull();
                });
    }

    /**
     * 문서를 열지 않은 턴은 편집 대상 셋이 모두 null로 온다. baseVersion이 원시 타입이면
     * 여기서 역직렬화가 깨져 run은 성공했는데 조회만 실패한다.
     */
    @Test
    void findReadsRunWithoutDocumentTarget() throws IOException {
        server.createContext("/internal/agent/runs", exchange -> {
            byte[] response = """
                    {"id":"run-1","document_id":null,"base_version":null,"apply_operation_id":null,
                     "status":"completed","result":{"action":"chat_answer"},"error_code":null}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        var run = requester().find("ws-1", "user-1", "run-1").orElseThrow();

        assertThat(run.id()).isEqualTo("run-1");
        assertThat(run.documentId()).isNull();
        assertThat(run.baseVersion()).isNull();
        assertThat(run.applyOperationId()).isNull();
        assertThat(run.status()).isEqualTo("completed");
    }

    @Test
    void findAutonomousReadsScopedCanonicalAgentRunWithAgentToken() {
        var run = requester().findAutonomous("ws-1", "user-1", "run-1").orElseThrow();

        assertThat(uri.get()).isEqualTo("/agent/runs/run-1?workspace_id=ws-1&user_id=user-1");
        assertThat(token.get()).isEqualTo("agent-token");
        assertThat(run.requestSummary()).isEqualTo("정식 요청");
        assertThat(run.plan().summary()).isEqualTo("정식 계획");
        assertThat(run.plan().operations().getFirst().toolName()).isEqualTo("move_document");
        assertThat(run.plan().operations().getFirst().reason()).isEqualTo("정식 이유");
    }

    @Test
    void findAutonomousReturnsEmptyForMissingOrForeignRun() {
        assertThat(requester().findAutonomous("foreign", "user-1", "run-1")).isEmpty();
    }

    @Test
    void mcpOwnedRunsUsesServiceTokenAndExactActorScope() {
        var result = requester().getMcpOwnedRuns("ws-1", "user 1",
                "00000000-0000-4000-8000-000000000001", "opaque cursor", 7);

        assertThat(uri.get()).isEqualTo("/agent/runs/mcp-owned?workspace_id=ws-1&user_id=user%201"
                + "&grant_id=00000000-0000-4000-8000-000000000001&cursor=opaque%20cursor&limit=7");
        assertThat(token.get()).isEqualTo("agent-token");
        assertThat(result.items()).containsExactly(
                new fruition.core.agent.dto.McpOwnedRunsResponse.Item("run-mcp-1", "awaiting_approval"));
        assertThat(result.nextCursor()).isEqualTo("cursor-2");
    }

    @Test
    void mcpOwnedRunsDoesNotExposeUpstreamFailureBody() {
        failureStatus.set(500);
        failureBody.set("{\"detail\":\"provider secret\"}");

        assertThatThrownBy(() -> requester().getMcpOwnedRuns("ws-1", "user-1",
                "00000000-0000-4000-8000-000000000001", null, 20))
                .isInstanceOfSatisfying(PipelineAgentException.class, exception -> {
                    assertThat(exception.getHttpStatus()).isEqualTo(503);
                    assertThat(exception.getResponseBody()).isNull();
                    assertThat(exception.getMessage()).doesNotContain("provider secret");
                });
    }

    @Test
    void lifecycleRequestsUseServiceTokenAndServerInjectedActorScope() {
        var client = requester();

        client.getAutonomousRun("ws-1", "user-1", "run-1");
        assertThat(method.get()).isEqualTo("GET");
        assertThat(uri.get()).isEqualTo("/agent/runs/run-1?workspace_id=ws-1&user_id=user-1");
        assertThat(token.get()).isEqualTo("agent-token");

        client.reject("ws-1", "user-1", "run-1");
        assertThat(method.get()).isEqualTo("POST");
        assertThat(uri.get()).isEqualTo("/agent/runs/run-1/reject");
        assertThat(body.get()).contains("\"workspace_id\":\"ws-1\"")
                .contains("\"user_id\":\"user-1\"");

        client.cancel("ws-1", "user-1", "run-1");
        assertThat(uri.get()).endsWith("/agent/runs/run-1/cancel");
        client.revise("ws-1", "user-1", "run-1", "계획을 좁혀줘");
        assertThat(uri.get()).endsWith("/agent/runs/run-1/revise");
        assertThat(body.get()).contains("계획을 좁혀줘");
    }

    /** 부분 승인만 operation_ids를 싣는다. 전체 승인은 키 없이 기존 요청 모양 그대로다. */
    @Test
    void approveSendsOperationIdsOnlyForPartialApproval() {
        var client = requester();

        assertThatThrownBy(() -> client.approve("ws-1", "user-1", "run-1", 1, "hash", java.util.List.of("op_1")))
                .isInstanceOf(fruition.core.agent.exception.PipelineAgentException.class);
        assertThat(body.get()).contains("\"operation_ids\":[\"op_1\"]").contains("\"plan_version\":1");

        assertThatThrownBy(() -> client.approve("ws-1", "user-1", "run-1", 1, "hash"))
                .isInstanceOf(fruition.core.agent.exception.PipelineAgentException.class);
        assertThat(body.get()).doesNotContain("operation_ids");
    }

    /** 자동 승인만 approved_by를 싣는다. 사용자 승인은 기존 요청 모양 그대로다. */
    @Test
    void automaticApprovalCarriesApproverOnlyThere() {
        var client = requester();

        assertThatThrownBy(() -> client.approveAutomatically("ws-1", "user-1", "run-1", 1, "hash", "auto:user-setting"))
                .isInstanceOf(fruition.core.agent.exception.PipelineAgentException.class);
        assertThat(body.get()).contains("\"approved_by\":\"auto:user-setting\"").doesNotContain("operation_ids");

        assertThatThrownBy(() -> client.approve("ws-1", "user-1", "run-1", 1, "hash"))
                .isInstanceOf(fruition.core.agent.exception.PipelineAgentException.class);
        assertThat(body.get()).doesNotContain("approved_by");
    }

    @Test
    void mcpApprovalSendsExactPriceFreeContract() {
        var client = requester();
        assertThatThrownBy(() -> client.approveMcp("ws-1", "user-1", "run-1", 1, "h".repeat(64),
                java.util.List.of("op-1"), "00000000-0000-4000-8000-000000000001",
                "00000000-0000-4000-8000-000000000002", "https://client.example/metadata", 3))
                .isInstanceOf(fruition.core.agent.exception.PipelineAgentException.class);
        assertThat(body.get()).contains("\"schema_version\":\"mcp.agent-approval.v1\"")
                .contains("\"approval_mode\":\"EXPLICIT_WEB\"")
                .contains("\"operation_ids\":[\"op-1\"]")
                .contains("\"policy_generation\":3")
                .doesNotContain("price", "maximum_charge", "unit_price");
    }

    @Test
    void lifecycleMapsNotFoundAndConflict() {
        assertThatThrownBy(() -> requester().getAutonomousRun("foreign", "user-1", "run-1"))
                .isInstanceOf(fruition.core.agent.exception.AgentRunNotFoundException.class);

        assertThatThrownBy(() -> requester().approve("ws-1", "user-1", "run-1", 1, "hash"))
                .isInstanceOfSatisfying(fruition.core.agent.exception.PipelineAgentException.class,
                        exception -> {
                            assertThat(exception.getHttpStatus()).isEqualTo(409);
                            assertThat(exception.getResponseBody()).isNull();
                        });
    }

    @Test
    void lifecycleMapsClientErrorsWithoutProviderBodyForEveryAction() {
        failureStatus.set(409);
        failureBody.set("{\"detail\":\"stale plan\"}");
        var client = requester();

        assertClientRejected(() -> client.getAutonomousRun("ws-1", "user-1", "run-1"));
        assertClientRejected(() -> client.approve("ws-1", "user-1", "run-1", 1, "hash"));
        assertClientRejected(() -> client.reject("ws-1", "user-1", "run-1"));
        assertClientRejected(() -> client.cancel("ws-1", "user-1", "run-1"));
        assertClientRejected(() -> client.revise("ws-1", "user-1", "run-1", "계획을 좁혀줘"));
    }

    @Test
    void lifecycleMapsServerErrorToUnavailableWithoutProviderBody() {
        failureStatus.set(500);
        failureBody.set("{\"detail\":\"provider secret\"}");

        assertThatThrownBy(() -> requester().getAutonomousRun("ws-1", "user-1", "run-1"))
                .isInstanceOfSatisfying(fruition.core.agent.exception.PipelineAgentException.class,
                        exception -> {
                            assertThat(exception.getHttpStatus()).isEqualTo(503);
                            assertThat(exception.getResponseBody()).isNull();
                        });
    }

    @Test
    void lifecycleMapsTimeoutToServiceUnavailable() {
        slow.set(true);

        assertThatThrownBy(() -> requester(1).getAutonomousRun("ws-1", "user-1", "run-1"))
                .isInstanceOfSatisfying(fruition.core.agent.exception.PipelineAgentException.class,
                        exception -> {
                            assertThat(exception.getHttpStatus()).isEqualTo(503);
                            assertThat(exception.getResponseBody()).isNull();
                        });
    }

    private PipelineAgentRunStatusRequester requester() {
        return requester(5);
    }

    private void assertClientRejected(Runnable request) {
        assertThatThrownBy(request::run)
                .isInstanceOfSatisfying(PipelineAgentException.class,
                        exception -> {
                            assertThat(exception.getHttpStatus()).isEqualTo(409);
                            assertThat(exception.getResponseBody()).isNull();
                        });
    }

    private PipelineAgentRunStatusRequester requester(int timeoutSeconds) {
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        return new PipelineAgentRunStatusRequester(
                new PipelineClientFactory("internal-token"), baseUrl + "/internal/agent/runs",
                baseUrl + "/agent/runs", "agent-token", timeoutSeconds);
    }
}
