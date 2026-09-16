package fruition.core.wikischema.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
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

    public PipelineWikiSchemaRequester(
            PipelineClientFactory clientFactory,
            @Value("${app.wiki-schema.endpoint}") String endpoint,
            @Value("${app.wiki-schema.timeout-seconds:60}") int timeoutSeconds) {
        this.endpoint = endpoint;
        this.restClient = clientFactory.restClient(timeoutSeconds);
    }

    public JsonNode preview(String rawMarkdown) {
        return requireBody(post(endpoint + "/preview", new PreviewPayload(rawMarkdown)));
    }

    public JsonNode createDraft(String rawMarkdown, String name, String workspaceId, String userId) {
        return requireBody(post(endpoint + "/drafts", new DraftPayload(rawMarkdown, name, workspaceId, userId)));
    }

    public JsonNode activate(String schemaId) {
        return requireBody(post(endpoint + "/" + schemaId + "/activate", null));
    }

    /** 활성 스키마가 없으면 pipeline이 null을 반환하므로 그대로 전달한다. */
    public JsonNode getActive(String workspaceId, String userId) {
        String uri = UriComponentsBuilder.fromHttpUrl(endpoint + "/active")
                .queryParam("workspace_id", workspaceId)
                .queryParam("user_id", userId)
                .toUriString();
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

    private record PreviewPayload(@JsonProperty("raw_markdown") String rawMarkdown) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DraftPayload(
            @JsonProperty("raw_markdown") String rawMarkdown,
            @JsonProperty("name") String name,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId
    ) {}
}
