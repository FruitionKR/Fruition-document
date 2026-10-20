package fruition.core.skill.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.skill.dto.SkillAuthoringRequest;
import fruition.core.skill.dto.SkillPublishRequest;
import fruition.core.skill.dto.SkillTestRunRequest;
import fruition.core.skill.dto.SkillUpdateRequest;
import fruition.core.skill.exception.PipelineSkillException;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class PipelineSkillRequester {

    private final RestClient restClient;
    private final String endpoint;
    private final fruition.core.aitask.service.AiTaskCancellationService cancellation;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final fruition.core.usage.service.UsageChargeService usageCharges;

    private static final Rejection DEFAULT_REJECTION = new Rejection("SKILL_REQUEST_REJECTED", "Skill 요청이 거부되었습니다.");

    /** AI 거절 사유 code → 클라이언트 code와 화면에 그대로 보여 줄 문장. AI 문장(영문)은 내보내지 않는다. */
    private static final java.util.Map<String, Rejection> REJECTIONS = java.util.Map.of(
            "intent_ambiguous", new Rejection("SKILL_INTENT_AMBIGUOUS",
                    "어떤 작업을 반복할지 구체적으로 적어 주세요."),
            "intent_unsupported", new Rejection("SKILL_INTENT_UNSUPPORTED",
                    "지원하지 않는 작업입니다. 문서 작성·수정·폴더 정리·템플릿 중에서 골라 주세요."),
            "invalid_instruction_length", new Rejection("SKILL_INSTRUCTION_INVALID",
                    "지침이 비어 있거나 너무 깁니다. 길이를 확인해 주세요."),
            "invalid_name", new Rejection("SKILL_INSTRUCTION_INVALID",
                    "Skill 이름 형식이 올바르지 않습니다."),
            "invalid_reference", new Rejection("SKILL_INSTRUCTION_INVALID",
                    "참조 문서를 확인해 주세요. 접근할 수 없거나 비어 있거나 너무 긴 문서가 있습니다."),
            // 게시·수정의 targets·result_mode가 capabilities와 어긋남. 수정에서는 생략해 이어받은 기존 값일 수도 있다.
            "invalid_result_mode", new Rejection("SKILL_RESULT_MODE_INVALID",
                    "결과 방식이 Skill 기능과 맞지 않습니다. 새 문서는 문서 작성, 현재 문서 수정은 문서 수정, "
                            + "자동은 두 기능이 모두 있어야 합니다. 결과 방식을 다시 골라 주세요."),
            "invalid_targets", new Rejection("SKILL_TARGETS_INVALID",
                    "적용 대상이 올바르지 않습니다. 대상은 문서 작성·수정 Skill에서만 고를 수 있고, 같은 대상을 두 번 고를 수 없습니다."),
            "test_run_unsupported", new Rejection("SKILL_TEST_RUN_UNSUPPORTED",
                    "이 종류의 Skill은 테스트 실행을 지원하지 않습니다. 문서 작성·수정·템플릿 Skill만 테스트할 수 있습니다."));

    private record Rejection(String code, String message) {
    }

    public PipelineSkillRequester(
            PipelineClientFactory clientFactory,
            @Value("${app.skill.endpoint}") String endpoint,
            @Value("${app.skill.agent-token}") String agentToken,
            @Value("${app.skill.timeout-seconds:60}") int timeoutSeconds,
            fruition.core.aitask.service.AiTaskCancellationService cancellation, com.fasterxml.jackson.databind.ObjectMapper mapper,
            fruition.core.usage.service.UsageChargeService usageCharges
    ) {
        this.endpoint = endpoint;
        this.usageCharges = usageCharges;
        this.cancellation = cancellation;
        this.mapper = mapper;
        this.restClient = RestClient.builder()
                .requestFactory(clientFactory.requestFactory(timeoutSeconds))
                .defaultHeader("X-Agent-Service-Token", agentToken)
                .build();
    }

    public JsonNode author(String workspaceId, String userId, SkillAuthoringRequest request,
                           WorkspaceAiModelClient.AiModelSelection aiModel, String runId) {
        return task(runId, "skill_author", workspaceId, userId, null, new AuthorPayload(workspaceId, userId, request.scopeType(), request.name(),
                request.description(), request.instruction(), request.authoringMode(), request.referenceDocumentIds(),
                aiModel.provider(), aiModel.model()));
    }

    public JsonNode publish(String workspaceId, String userId, SkillPublishRequest request,
                            WorkspaceAiModelClient.AiModelSelection aiModel, String runId) {
        return task(runId, "skill_publish", workspaceId, userId, null, new PublishPayload(workspaceId, userId, request.scopeType(), request.name(),
                request.description(), request.instructionsMarkdown(), request.capabilities(), request.allowedTools(),
                request.targets(), request.resultMode(), request.requiresSource(), aiModel.provider(), aiModel.model()));
    }

    public JsonNode list(String workspaceId, String userId) {
        String uri = UriComponentsBuilder.fromUriString(endpoint)
                .queryParam("workspace_id", workspaceId)
                .queryParam("user_id", userId)
                .toUriString();
        return get(uri);
    }

    public JsonNode get(String workspaceId, String userId, String skillId) {
        String uri = UriComponentsBuilder.fromUriString(endpoint + "/" + skillId)
                .queryParam("workspace_id", workspaceId)
                .queryParam("user_id", userId)
                .toUriString();
        return get(uri);
    }

    public JsonNode update(String workspaceId, String userId, String skillId, SkillUpdateRequest request,
                           WorkspaceAiModelClient.AiModelSelection aiModel, String runId) {
        return task(runId, "skill_update", workspaceId, userId, skillId, new UpdatePayload(workspaceId, userId, request.name(),
                request.description(), request.instructionsMarkdown(), request.targets(), request.resultMode(),
                request.requiresSource(), aiModel.provider(), aiModel.model()));
    }

    /**
     * 저장하지 않는 Skill 테스트 실행(#143). AI {@code POST /skills/test-run}에 {@code X-Request-Id}=runId로 보내
     * 사용량을 run에 기록하게 하고, 다른 Skill 작업처럼 실행 등록(크레딧 예약)·취소·정산을 거친다.
     * 실행 기록에는 문서 본문 대신 해시를 남긴다(같은 run_id 재요청 비교용).
     */
    public JsonNode testRun(String workspaceId, String userId, SkillTestRunRequest request, long baseVersion,
                            String markdown, WorkspaceAiModelClient.AiModelSelection aiModel, String runId) {
        ObjectNode body = mapper.valueToTree(new TestRunPayload(workspaceId, userId, aiModel.provider(), aiModel.model(),
                new TestRunDefinition(request.name(), request.description(), request.instructionsMarkdown(),
                        request.capabilities()),
                new TestRunDocument(request.documentId(), baseVersion, markdown), request.target()));
        ObjectNode recorded = body.deepCopy();
        ((ObjectNode) recorded.path("document")).remove("markdown");
        ((ObjectNode) recorded.path("document")).put("markdown_sha256", sha256(markdown));
        // AI 테스트 실행은 run_id로 중복을 막지 않아 같은 run_id 재요청은 등록 단계에서 거절한다.
        return tracked(runId, command(runId, "skill_test_run", workspaceId, userId, recorded), true, () -> {
            try {
                JsonNode result = post("/test-run", body, runId, true);
                return result.isObject() ? ((ObjectNode) result.deepCopy()).put("run_id", runId) : result;
            } catch (PipelineSkillException e) {
                // 테스트 실행 API가 없는 AI(ai#114 배포 전)는 404를 낸다. 문서·Skill이 없다는 뜻으로 읽히지 않게 바꾼다.
                if (e.getHttpStatus() == 404) {
                    throw new PipelineSkillException("Skill 테스트 실행을 아직 사용할 수 없습니다.", 503,
                            null, "SKILL_TEST_RUN_UNAVAILABLE");
                }
                throw e;
            }
        });
    }

    public JsonNode setEnabled(String workspaceId, String userId, String skillId, boolean enabled) {
        return post("/" + skillId + (enabled ? "/enable" : "/disable"),
                new ActorPayload(workspaceId, userId));
    }

    public void delete(String workspaceId, String userId, String skillId) {
        String uri = UriComponentsBuilder.fromUriString(endpoint)
                .pathSegment("{skillId}")
                .queryParam("workspace_id", "{workspaceId}")
                .queryParam("user_id", "{userId}")
                .encode()
                .buildAndExpand(skillId, workspaceId, userId)
                .toUriString();
        try {
            restClient.delete().uri(java.net.URI.create(uri)).retrieve().toBodilessEntity();
        } catch (ResourceAccessException exception) {
            throw unavailable("Skill 파이프라인 응답 시간이 초과되었습니다.");
        } catch (RestClientResponseException exception) {
            throw translate(exception);
        }
    }

    private JsonNode task(String runId, String kind, String workspaceId, String userId, String skillId, Object payload) {
        var command = command(runId, kind, workspaceId, userId, mapper.valueToTree(payload));
        if (skillId != null) command.put("skill_id", skillId);
        return tracked(runId, command, false, () -> post("/tasks", command, runId, false));
    }

    private ObjectNode command(String runId, String kind, String workspaceId, String userId, JsonNode payload) {
        var command = mapper.createObjectNode().put("run_id", runId).put("kind", kind)
                .put("workspace_id", workspaceId).put("user_id", userId);
        command.set("payload", payload);
        return command;
    }

    /**
     * 실행을 등록(크레딧 예약)하고 AI를 호출한 뒤 완료·실패를 기록한다. 결과와 관계없이 사용 금액을 수집한다.
     * requireNewRun이면 이미 등록된 run_id를 거절한다. 거절은 실행 전이라 실패·정산 기록을 남기지 않는다.
     */
    private JsonNode tracked(String runId, ObjectNode command, boolean requireNewRun,
                             java.util.function.Supplier<JsonNode> call) {
        if (requireNewRun) cancellation.registerNew(command); else cancellation.register(command);
        try {
            JsonNode result = call.get();
            cancellation.finish(runId);
            return result;
        } catch (RuntimeException e) {
            usageCharges.failRun(runId);
            throw e;
        } finally {
            // 실패해도 공급사 호출은 일어났을 수 있어 사용 금액을 수집한다(#78).
            usageCharges.enqueue(runId);
        }
    }

    private JsonNode get(String uri) {
        try {
            return requireBody(restClient.get().uri(uri).retrieve().body(JsonNode.class));
        } catch (ResourceAccessException exception) {
            throw unavailable("Skill 파이프라인 응답 시간이 초과되었습니다.");
        } catch (RestClientResponseException exception) {
            throw translate(exception);
        }
    }

    private JsonNode post(String path, Object body) {
        return post(path, body, null, false);
    }

    /**
     * runId가 있으면 타임아웃 시 AI에 해당 작업의 취소(journal 복구)를 요청한다. AI도 55초에 자체 중단한다(ai ADR 0032).
     * sendRequestId면 runId를 {@code X-Request-Id}로 보낸다(본문에 run_id가 없는 API의 사용량 원장 키).
     */
    private JsonNode post(String path, Object body, String runId, boolean sendRequestId) {
        try {
            var request = restClient.post().uri(endpoint + path).contentType(MediaType.APPLICATION_JSON);
            if (sendRequestId) request = request.header("X-Request-Id", runId);
            return requireBody(request.body(body).retrieve().body(JsonNode.class));
        } catch (ResourceAccessException exception) {
            if (runId != null) cancelOnTimeout(runId, body);
            throw unavailable("Skill 파이프라인 응답 시간이 초과되었습니다.");
        } catch (RestClientResponseException exception) {
            throw translate(exception);
        }
    }

    private void cancelOnTimeout(String runId, Object command) {
        JsonNode node = (JsonNode) command;
        cancellation.cancel(runId, node.path("workspace_id").asText(), node.path("user_id").asText());
    }

    private JsonNode requireBody(JsonNode response) {
        if (response == null || response.isNull()) {
            throw unavailable("Skill 파이프라인 응답이 비어 있습니다.");
        }
        return response;
    }

    private PipelineSkillException translate(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        if (status == 400 || status == 404 || status == 409 || status == 410 || status == 413 || status == 422) {
            String body = exception.getResponseBodyAsString();
            Rejection rejection = status == 400
                    ? REJECTIONS.getOrDefault(rejectionCode(body), DEFAULT_REJECTION) : DEFAULT_REJECTION;
            return new PipelineSkillException(rejection.message(), status, body, rejection.code());
        }
        return unavailable("Skill 파이프라인을 사용할 수 없습니다.");
    }

    /** AI 거절 응답 {@code 400 {"detail": {"code", "message"}}}의 code. 형식이 다르면 빈 문자열이다. */
    private String rejectionCode(String body) {
        try {
            return mapper.readTree(body).path("detail").path("code").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private PipelineSkillException unavailable(String message) {
        return new PipelineSkillException(message, 503, null);
    }

    private record AuthorPayload(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("scope_type") String scopeType,
            String name,
            String description,
            String instruction,
            @JsonProperty("authoring_mode") String authoringMode,
            @JsonProperty("reference_document_ids") java.util.List<String> referenceDocumentIds,
            String provider,
            String model) {}

    private record PublishPayload(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("scope_type") String scopeType,
            String name,
            String description,
            @JsonProperty("instructions_markdown") String instructionsMarkdown,
            java.util.List<String> capabilities,
            // 비어 있으면 키를 빼서 AI가 capabilities로 계산하게 한다(#145).
            @JsonProperty("allowed_tools") @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) java.util.List<String> allowedTools,
            // 생략하면 키를 빼서 targets·result_mode를 모르는 AI에도 기존 요청과 같게 보낸다(#144).
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            java.util.List<String> targets,
            @JsonProperty("result_mode")
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String resultMode,
            // 생략하면 키를 빼서 AI가 원문 필요 여부를 판단하게 한다.
            @JsonProperty("requires_source")
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            Boolean requiresSource,
            String provider,
            String model) {}

    private record UpdatePayload(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            String name,
            String description,
            @JsonProperty("instructions_markdown") String instructionsMarkdown,
            // 생략하면 키를 빼서 targets·result_mode를 모르는 AI에도 기존 요청과 같게 보낸다(#144).
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            java.util.List<String> targets,
            @JsonProperty("result_mode")
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String resultMode,
            // 생략하면 키를 빼서 AI가 원문 필요 여부를 판단하게 한다.
            @JsonProperty("requires_source")
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            Boolean requiresSource,
            String provider,
            String model) {}

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record TestRunPayload(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            String provider,
            String model,
            TestRunDefinition definition,
            TestRunDocument document,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            SkillTestRunRequest.Target target) {}

    private record TestRunDefinition(
            String name,
            String description,
            @JsonProperty("instructions_markdown") String instructionsMarkdown,
            java.util.List<String> capabilities) {}

    private record TestRunDocument(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("base_version") long baseVersion,
            String markdown) {}

    private record ActorPayload(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId) {}
}
