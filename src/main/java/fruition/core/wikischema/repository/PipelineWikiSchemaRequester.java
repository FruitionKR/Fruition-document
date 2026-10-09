package fruition.core.wikischema.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.usage.service.UsageChargeService;
import fruition.core.wikischema.exception.PipelineWikiSchemaException;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class PipelineWikiSchemaRequester {

    private final RestClient restClient;
    private final String endpoint;
    private final UsageChargeService usageCharges;

    public PipelineWikiSchemaRequester(
            PipelineClientFactory clientFactory,
            @Value("${app.wiki-schema.endpoint}") String endpoint,
            @Value("${app.wiki-schema.timeout-seconds:60}") int timeoutSeconds,
            UsageChargeService usageCharges) {
        this.endpoint = endpoint;
        this.restClient = clientFactory.restClient(timeoutSeconds);
        this.usageCharges = usageCharges;
    }

    /** 미리보기·초안은 AI 모델을 부른다. AI가 사용량을 남기도록 run_id와 사용자를 함께 보낸다. */
    public JsonNode preview(String rawMarkdown, String workspaceId, String userId) {
        return usageCharges.track("wiki_schema_preview", workspaceId, userId, runId ->
                requireBody(post(endpoint + "/preview", new PreviewPayload(rawMarkdown, workspaceId, userId, runId))));
    }

    public JsonNode createDraft(String rawMarkdown, String name, String workspaceId, String userId) {
        return usageCharges.track("wiki_schema_draft", workspaceId, userId, runId ->
                requireBody(post(endpoint + "/drafts", new DraftPayload(rawMarkdown, name, workspaceId, userId, runId))));
    }

    public JsonNode activate(String schemaId) {
        return requireBody(post(endpoint + "/" + schemaId + "/activate", null));
    }

    /** 초안 목록은 pipeline이 wiki_schemas 봉투로 감싸 주므로 그대로 전달한다. */
    public JsonNode listDrafts(String workspaceId, String userId) {
        String uri = UriComponentsBuilder.fromHttpUrl(endpoint + "/drafts")
                .queryParam("workspace_id", workspaceId)
                .queryParam("user_id", userId)
                .toUriString();
        return requireBody(get(uri));
    }

    /** 활성 스키마가 없으면 pipeline이 null을 반환하므로 그대로 전달한다. */
    public JsonNode getActive(String workspaceId, String userId) {
        String uri = UriComponentsBuilder.fromHttpUrl(endpoint + "/active")
                .queryParam("workspace_id", workspaceId)
                .queryParam("user_id", userId)
                .toUriString();
        return get(uri);
    }

    private JsonNode get(String uri) {
        try {
            return restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {
            throw timeout();
        } catch (RestClientResponseException e) {
            throw mapError(e);
        }
    }

    private JsonNode post(String uri, Object body) {
        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON);
            return (body == null ? spec : spec.body(body))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {
            throw timeout();
        } catch (RestClientResponseException e) {
            throw mapError(e);
        }
    }

    private JsonNode requireBody(JsonNode response) {
        if (response == null || response.isNull()) {
            throw new PipelineWikiSchemaException("Wiki 스키마 파이프라인 응답이 비어 있습니다.", 503, null);
        }
        return response;
    }

    private PipelineWikiSchemaException timeout() {
        return new PipelineWikiSchemaException("Wiki 스키마 파이프라인 응답 시간이 초과되었습니다.", 503, null);
    }

    private PipelineWikiSchemaException mapError(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 400 || status == 422) {
            return new PipelineWikiSchemaException("Wiki 스키마 요청이 거부되었습니다.", status, e.getResponseBodyAsString());
        }
        if (status == 404) {
            return new PipelineWikiSchemaException("Wiki 스키마를 찾을 수 없습니다.", status, e.getResponseBodyAsString());
        }
        return new PipelineWikiSchemaException("Wiki 스키마 파이프라인을 사용할 수 없습니다.", 503, null);
    }

    private record PreviewPayload(
            @JsonProperty("raw_markdown") String rawMarkdown,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("run_id") String runId
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DraftPayload(
            @JsonProperty("raw_markdown") String rawMarkdown,
            @JsonProperty("name") String name,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("run_id") String runId
    ) {}
}
