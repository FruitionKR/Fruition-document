package fruition.core.skill.repository;

import com.sun.net.httpserver.HttpServer;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.skill.dto.SkillAuthoringRequest;
import fruition.core.skill.dto.SkillPublishRequest;
import fruition.core.skill.dto.SkillUpdateRequest;
import fruition.shared.http.PipelineClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineSkillRequesterTest {

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> uri = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<String> requestId = new AtomicReference<>();
    private int responseStatus = 200;
    private long responseDelayMillis = 0;
    private String responseBody = "{\"status\":\"proposal_ready\"}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/skills", exchange -> {
            method.set(exchange.getRequestMethod());
            uri.set(exchange.getRequestURI().toString());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            token.set(exchange.getRequestHeaders().getFirst("X-Agent-Service-Token"));
            requestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            if (responseDelayMillis > 0) {
                try { Thread.sleep(responseDelayMillis); } catch (InterruptedException ignored) { }
            }
            if ("DELETE".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void author_usesServerControlledWorkspaceUserAndAgentToken() {
        requester().author("ws_1", "user_1", new SkillAuthoringRequest(
                "personal", "meeting-notes", null,
                "회의록을 작성해줘", "enhance", List.of("doc_1")),
                new WorkspaceAiModelClient.AiModelSelection("gemini", "gemini-3.5-flash-lite"), "run-author");

        assertThat(method.get()).isEqualTo("POST");
        assertThat(uri.get()).isEqualTo("/skills/tasks");
        assertThat(token.get()).isEqualTo("agent-token");
        assertThat(body.get())
                .contains("\"workspace_id\":\"ws_1\"")
                .contains("\"user_id\":\"user_1\"")
                .contains("\"reference_document_ids\":[\"doc_1\"]")
                .contains("\"provider\":\"gemini\"")
                .contains("\"model\":\"gemini-3.5-flash-lite\"")
                .doesNotContain("capabilities", "allowed_tools");
    }

    @Test
    void publish_usesWorkspaceModelSnapshot() {
        requester().publish("ws_1", "user_1",
                new SkillPublishRequest("team", "meeting-notes", "회의록 작성", "# 작성 절차",
                        List.of("document-create"),
                        List.of("list_root_items", "list_folder_children", "create_document"), null, null, null),
                new WorkspaceAiModelClient.AiModelSelection("claude", "claude-sonnet-5"), "run-publish");

        assertThat(method.get()).isEqualTo("POST");
        assertThat(uri.get()).isEqualTo("/skills/tasks");
        assertThat(body.get())
                .contains("\"provider\":\"claude\"")
                .contains("\"model\":\"claude-sonnet-5\"")
                .contains("\"capabilities\":[\"document-create\"]")
                .contains("\"allowed_tools\":[\"list_root_items\",\"list_folder_children\",\"create_document\"]")
                .doesNotContain("api_key", "base_url");
    }

    @Test
    void publish_omitsAllowedToolsWhenMissingOrEmptySoAiComputesThem() {
        java.util.Arrays.asList(null, List.<String>of()).forEach(allowedTools -> {
            requester().publish("ws_1", "user_1",
                    new SkillPublishRequest("personal", "meeting-notes", "회의록 작성", "# 작성 절차",
                            List.of("document-create"), allowedTools, null, null, null),
                    new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "run-publish");

            assertThat(body.get()).as(String.valueOf(allowedTools))
                    .contains("\"capabilities\":[\"document-create\"]")
                    .doesNotContain("allowed_tools");
        });
    }

    @Test
    void publishAndUpdate_forwardTargetsResultModeAndRequiresSource() {
        requester().publish("ws_1", "user_1",
                new SkillPublishRequest("personal", "research-summary", "연구 요약", "# 절차",
                        List.of("document-create", "document-edit"), null,
                        List.of("current_document", "selection"), "auto", true),
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "run-publish");
        assertThat(body.get())
                .contains("\"targets\":[\"current_document\",\"selection\"]")
                .contains("\"result_mode\":\"auto\"")
                .contains("\"requires_source\":true");

        requester().update("ws_1", "user_1", "skill_1",
                new SkillUpdateRequest("research-summary", "연구 요약", "# 절차", List.of("selection"), "edit", false,
                        List.of("document-edit")),
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "run-update");
        assertThat(body.get())
                .contains("\"targets\":[\"selection\"]")
                .contains("\"result_mode\":\"edit\"")
                .contains("\"requires_source\":false")
                .contains("\"capabilities\":[\"document-edit\"]");
    }

    @Test
    void publishAndUpdate_omitTargetsResultModeAndRequiresSourceWhenMissingSoAiKeepsExistingBehavior() {
        requester().publish("ws_1", "user_1",
                new SkillPublishRequest("personal", "meeting-notes", "회의록 작성", "# 작성 절차",
                        List.of("document-create"), List.of("create_document"), null, null, null),
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "run-publish");
        assertThat(body.get()).doesNotContain("targets", "result_mode", "requires_source");

        requester().update("ws_1", "user_1", "skill_1",
                new SkillUpdateRequest("meeting-notes", "회의록 작성", "# 작성 절차", null, null, null, null),
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "run-update");
        assertThat(body.get()).doesNotContain("targets", "result_mode", "requires_source", "capabilities");
    }

    @Test
    void testRun_postsDraftDefinitionAndDocumentWithRequestIdAndReturnsRunId() throws Exception {
        var cancellation = org.mockito.Mockito.mock(fruition.core.aitask.service.AiTaskCancellationService.class);
        responseBody = "{\"status\":\"completed\",\"changes\":[],\"untouched_sections\":[],\"issues\":[]}";

        var result = requester(org.mockito.Mockito.mock(fruition.core.usage.service.UsageChargeService.class),
                cancellation, 5).testRun("ws_1", "user_1", testRunRequest(), 7, "# 연구 개요\n본문",
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "skill_test_1");

        assertThat(uri.get()).isEqualTo("/skills/test-run");
        assertThat(token.get()).isEqualTo("agent-token");
        assertThat(requestId.get()).isEqualTo("skill_test_1");
        var sent = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body.get());
        assertThat(sent.path("workspace_id").asText()).isEqualTo("ws_1");
        assertThat(sent.path("user_id").asText()).isEqualTo("user_1");
        assertThat(sent.path("provider").asText()).isEqualTo("openai");
        assertThat(sent.path("model").asText()).isEqualTo("gpt-6-luna");
        assertThat(sent.path("definition").path("instructions_markdown").asText()).isEqualTo("'연구 개요' 섹션만 고친다.");
        assertThat(sent.path("definition").path("capabilities").get(0).asText()).isEqualTo("document-edit");
        assertThat(sent.path("definition").has("allowed_tools")).isFalse();
        assertThat(sent.path("document").path("document_id").asText()).isEqualTo("doc_1");
        assertThat(sent.path("document").path("base_version").asLong()).isEqualTo(7);
        assertThat(sent.path("document").path("markdown").asText()).isEqualTo("# 연구 개요\n본문");
        assertThat(sent.path("target").path("type").asText()).isEqualTo("selection");
        assertThat(sent.path("target").path("start_line").asInt()).isEqualTo(1);
        assertThat(sent.path("target").path("end_line").asInt()).isEqualTo(2);
        assertThat(result.path("run_id").asText()).isEqualTo("skill_test_1");
        assertThat(result.path("status").asText()).isEqualTo("completed");

        // 실행 기록(크레딧 예약·취소용)에는 문서 본문 대신 해시만 남긴다.
        var command = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        var order = org.mockito.Mockito.inOrder(cancellation);
        order.verify(cancellation).registerNew(command.capture());
        order.verify(cancellation).finish("skill_test_1");
        assertThat(command.getValue().path("kind").asText()).isEqualTo("skill_test_run");
        assertThat(command.getValue().path("payload").path("document").has("markdown")).isFalse();
        assertThat(command.getValue().path("payload").path("document").path("markdown_sha256").asText()).hasSize(64);
    }

    @Test
    void testRun_reusedRunIdIsRejectedBeforeCallingAiOrClosingTheEarlierRun() {
        var cancellation = org.mockito.Mockito.mock(fruition.core.aitask.service.AiTaskCancellationService.class);
        var usageCharges = org.mockito.Mockito.mock(fruition.core.usage.service.UsageChargeService.class);
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "이미 사용한 작업 ID입니다."))
                .when(cancellation).registerNew(org.mockito.ArgumentMatchers.any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> requester(usageCharges, cancellation, 5)
                        .testRun("ws_1", "user_1", testRunRequest(), 1, "본문\n둘",
                                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "skill_test_1"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        assertThat(uri.get()).isNull();
        org.mockito.Mockito.verifyNoInteractions(usageCharges);
    }

    @Test
    void tasksKeepIdempotentRegistrationBecauseAiDeduplicatesByRunId() {
        var cancellation = org.mockito.Mockito.mock(fruition.core.aitask.service.AiTaskCancellationService.class);
        requester(org.mockito.Mockito.mock(fruition.core.usage.service.UsageChargeService.class), cancellation, 5)
                .author("ws_1", "user_1", new SkillAuthoringRequest(
                        "personal", "meeting-notes", null, "회의록을 작성해줘", "enhance", List.of()),
                        new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "run-author");

        org.mockito.Mockito.verify(cancellation).register(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(cancellation, org.mockito.Mockito.never()).registerNew(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void testRun_omitsTargetWhenWholeDocument() {
        requester().testRun("ws_1", "user_1", new fruition.core.skill.dto.SkillTestRunRequest("research-summary",
                        "연구 요약", "지침", List.of("document-edit"), "doc_1", null, null), 1, "본문",
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "skill_test_1");

        assertThat(body.get()).doesNotContain("target");
    }

    @Test
    void testRun_mapsMissingAiEndpointToUnavailableAndUnsupportedCapabilityToClientCode() {
        var usageCharges = org.mockito.Mockito.mock(fruition.core.usage.service.UsageChargeService.class);
        responseStatus = 404;
        responseBody = "{\"detail\":\"Not Found\"}";
        var missing = org.assertj.core.api.Assertions.catchThrowableOfType(
                fruition.core.skill.exception.PipelineSkillException.class,
                () -> requester(usageCharges).testRun("ws_1", "user_1", testRunRequest(), 1, "본문\n둘",
                        new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "skill_test_404"));
        assertThat(missing.getHttpStatus()).isEqualTo(503);
        assertThat(missing.getCode()).isEqualTo("SKILL_TEST_RUN_UNAVAILABLE");
        org.mockito.Mockito.verify(usageCharges).failRun(org.mockito.ArgumentMatchers.eq("skill_test_404"), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(usageCharges).enqueue("skill_test_404");

        responseStatus = 400;
        responseBody = "{\"detail\":{\"code\":\"test_run_unsupported\",\"message\":\"x\"}}";
        var unsupported = org.assertj.core.api.Assertions.catchThrowableOfType(
                fruition.core.skill.exception.PipelineSkillException.class,
                () -> requester().testRun("ws_1", "user_1", testRunRequest(), 1, "본문\n둘",
                        new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "skill_test_400"));
        assertThat(unsupported.getHttpStatus()).isEqualTo(400);
        assertThat(unsupported.getCode()).isEqualTo("SKILL_TEST_RUN_UNSUPPORTED");
    }

    @Test
    void testRun_timeoutRequestsAiCancellationForTheRun() {
        var cancellation = org.mockito.Mockito.mock(fruition.core.aitask.service.AiTaskCancellationService.class);
        responseDelayMillis = 2500;

        var exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                fruition.core.skill.exception.PipelineSkillException.class,
                () -> requester(org.mockito.Mockito.mock(fruition.core.usage.service.UsageChargeService.class),
                        cancellation, 1).testRun("ws_1", "user_1", testRunRequest(), 1, "본문\n둘",
                        new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "skill_test_timeout"));

        assertThat(exception.getHttpStatus()).isEqualTo(503);
        org.mockito.Mockito.verify(cancellation).cancel("skill_test_timeout", "ws_1", "user_1");
    }

    private fruition.core.skill.dto.SkillTestRunRequest testRunRequest() {
        return new fruition.core.skill.dto.SkillTestRunRequest("research-summary", "연구 요약",
                "'연구 개요' 섹션만 고친다.", List.of("document-edit"), "doc_1", null,
                new fruition.core.skill.dto.SkillTestRunRequest.Target("selection", 1, 2));
    }

    @Test
    void list_usesWorkspaceAndUserQueryParameters() {
        requester().list("ws_1", "user_1");

        assertThat(method.get()).isEqualTo("GET");
        assertThat(uri.get()).contains("workspace_id=ws_1").contains("user_id=user_1");
    }

    @Test
    void update_usesSkillPathAndScopePayload() {
        requester().update("ws_1", "user_1", "skill_1",
                new SkillUpdateRequest("meeting-notes", "회의록 작성", "# 작성 절차", null, null, null, null),
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "run-update");

        assertThat(method.get()).isEqualTo("POST");
        assertThat(uri.get()).isEqualTo("/skills/tasks");
        assertThat(body.get())
                .contains("\"workspace_id\":\"ws_1\"")
                .contains("\"user_id\":\"user_1\"")
                .contains("\"instructions_markdown\":\"# 작성 절차\"")
                .contains("\"provider\":\"openai\"")
                .contains("\"model\":\"gpt-6-luna\"")
                .doesNotContain("api_key", "base_url");
    }

    @Test
    void delete_acceptsNoContentAndForwardsTrustedActor() {
        requester().delete("ws_1", "user_1", "skill_1");
        assertThat(method.get()).isEqualTo("DELETE");
        assertThat(uri.get()).isEqualTo("/skills/skill_1?workspace_id=ws_1&user_id=user_1");
        assertThat(token.get()).isEqualTo("agent-token");
        assertThat(body.get()).isEmpty();
    }

    @Test
    void author_mapsAiRejectionCodeToClientCodeAndMessage() {
        java.util.Map<String, String> expected = java.util.Map.of(
                "{\"detail\":{\"code\":\"intent_ambiguous\",\"message\":\"Skill request could not be classified.\"}}",
                "SKILL_INTENT_AMBIGUOUS",
                "{\"detail\":{\"code\":\"intent_unsupported\",\"message\":\"x\"}}", "SKILL_INTENT_UNSUPPORTED",
                "{\"detail\":{\"code\":\"invalid_instruction_length\",\"message\":\"x\"}}", "SKILL_INSTRUCTION_INVALID",
                "{\"detail\":{\"code\":\"invalid_name\",\"message\":\"x\"}}", "SKILL_INSTRUCTION_INVALID",
                "{\"detail\":{\"code\":\"invalid_reference\",\"message\":\"x\"}}", "SKILL_INSTRUCTION_INVALID",
                "{\"detail\":{\"code\":\"skill_request_invalid\",\"message\":\"x\"}}", "SKILL_REQUEST_REJECTED",
                "{\"detail\":\"skill_id is required.\"}", "SKILL_REQUEST_REJECTED",
                "not json", "SKILL_REQUEST_REJECTED");
        responseStatus = 400;
        expected.forEach((aiBody, code) -> {
            responseBody = aiBody;
            var exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                    fruition.core.skill.exception.PipelineSkillException.class, this::author);
            assertThat(exception.getHttpStatus()).as(aiBody).isEqualTo(400);
            assertThat(exception.getCode()).as(aiBody).isEqualTo(code);
            assertThat(exception.getMessage()).as(aiBody).doesNotContain("classified").matches(".*[가-힣].*");
        });
    }

    @Test
    void publishAndUpdate_mapExecutionOptionRejectionsToDedicatedCodes() {
        java.util.Map<String, String> expected = java.util.Map.of(
                "invalid_result_mode", "SKILL_RESULT_MODE_INVALID",
                "invalid_targets", "SKILL_TARGETS_INVALID",
                "invalid_capabilities", "SKILL_CAPABILITIES_INVALID");
        var aiModel = new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna");
        responseStatus = 400;
        expected.forEach((aiCode, code) -> {
            responseBody = "{\"detail\":{\"code\":\"" + aiCode + "\",\"message\":\"result_mode edit does not match capabilities.\"}}";
            var published = org.assertj.core.api.Assertions.catchThrowableOfType(
                    fruition.core.skill.exception.PipelineSkillException.class,
                    () -> requester().publish("ws_1", "user_1", new SkillPublishRequest("personal", "meeting-notes",
                            "회의록 작성", "# 작성 절차", List.of("document-create"), List.of(), null, "edit", null),
                            aiModel, "run-publish-" + aiCode));
            var updated = org.assertj.core.api.Assertions.catchThrowableOfType(
                    fruition.core.skill.exception.PipelineSkillException.class,
                    () -> requester().update("ws_1", "user_1", "skill_1",
                            new SkillUpdateRequest("meeting-notes", "회의록 작성", "# 작성 절차", null, null, null, null),
                            aiModel, "run-update-" + aiCode));
            for (var exception : List.of(published, updated)) {
                assertThat(exception.getHttpStatus()).as(aiCode).isEqualTo(400);
                assertThat(exception.getCode()).as(aiCode).isEqualTo(code);
                assertThat(exception.getMessage()).as(aiCode).doesNotContain("capabilities").matches(".*[가-힣].*");
            }
        });
    }

    @Test
    void author_keepsDefaultCodeForNonRejectionStatus() {
        responseStatus = 409;
        responseBody = "{\"detail\":{\"code\":\"intent_ambiguous\",\"message\":\"x\"}}";
        var exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                fruition.core.skill.exception.PipelineSkillException.class, this::author);
        assertThat(exception.getCode()).isEqualTo("SKILL_REQUEST_REJECTED");

        responseStatus = 500;
        exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                fruition.core.skill.exception.PipelineSkillException.class, this::author);
        assertThat(exception.getHttpStatus()).isEqualTo(503);
    }

    @Test
    void failedTaskClosesRunAndStillQueuesUsageCollection() {
        var usageCharges = org.mockito.Mockito.mock(fruition.core.usage.service.UsageChargeService.class);
        responseStatus = 500;
        responseBody = "{}";

        org.assertj.core.api.Assertions.catchThrowableOfType(fruition.core.skill.exception.PipelineSkillException.class,
                () -> requester(usageCharges).author("ws_1", "user_1", new SkillAuthoringRequest(
                        "personal", "meeting-notes", null, "회의록을 작성해줘", "enhance", List.of()),
                        new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "run-failed"));

        var order = org.mockito.Mockito.inOrder(usageCharges);
        order.verify(usageCharges).failRun(org.mockito.ArgumentMatchers.eq("run-failed"), org.mockito.ArgumentMatchers.any());
        order.verify(usageCharges).enqueue("run-failed");
    }

    @Test
    void timeoutRequestsAiCancellationForTheRun() {
        var cancellation = org.mockito.Mockito.mock(fruition.core.aitask.service.AiTaskCancellationService.class);
        responseDelayMillis = 2500;

        var exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                fruition.core.skill.exception.PipelineSkillException.class,
                () -> requester(org.mockito.Mockito.mock(fruition.core.usage.service.UsageChargeService.class),
                        cancellation, 1).author("ws_1", "user_1", new SkillAuthoringRequest(
                        "personal", "meeting-notes", null, "회의록을 작성해줘", "enhance", List.of()),
                        new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "run-timeout"));

        assertThat(exception.getHttpStatus()).isEqualTo(503);
        org.mockito.Mockito.verify(cancellation).cancel("run-timeout", "ws_1", "user_1");
    }

    private void author() {
        requester().author("ws_1", "user_1", new SkillAuthoringRequest(
                "personal", "meeting-notes", null, "ㅁㄴㅇㅁㄴㅇㅁㄴ", "enhance", List.of()),
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna"), "run-author");
    }

    private PipelineSkillRequester requester() {
        return requester(org.mockito.Mockito.mock(fruition.core.usage.service.UsageChargeService.class));
    }

    private PipelineSkillRequester requester(fruition.core.usage.service.UsageChargeService usageCharges) {
        return requester(usageCharges,
                org.mockito.Mockito.mock(fruition.core.aitask.service.AiTaskCancellationService.class), 5);
    }

    private PipelineSkillRequester requester(fruition.core.usage.service.UsageChargeService usageCharges,
            fruition.core.aitask.service.AiTaskCancellationService cancellation, int timeoutSeconds) {
        return new PipelineSkillRequester(
                new PipelineClientFactory("unused-internal-token"),
                "http://localhost:" + server.getAddress().getPort() + "/skills", "agent-token", timeoutSeconds,
                cancellation,
                new com.fasterxml.jackson.databind.ObjectMapper(), usageCharges);
    }
}
