package fruition.core.wiki.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.wiki.dto.WikiGraphResponse;
import fruition.core.wiki.dto.WikiPageDetailResponse;
import fruition.core.wiki.exception.PipelineWikiPageException;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class PipelineWikiStateRequester {

    private final RestClient restClient;
    private final String endpoint;

    public PipelineWikiStateRequester(
            PipelineClientFactory clientFactory,
            @Value("${app.wiki-state.endpoint}") String endpoint,
            @Value("${app.wiki-state.timeout-seconds:30}") int timeoutSeconds) {
        this.restClient = clientFactory.restClient(timeoutSeconds);
        this.endpoint = endpoint;
    }

    public WikiGraphResponse graph(String workspaceId) {
        return requireBody(restClient.get()
                .uri(uri("/graph", "workspace_id", workspaceId))
                .retrieve()
                .body(WikiGraphResponse.class));
    }

    public Optional<WikiPageDetailResponse> page(String workspaceId, String pageId) {
        try {
            return Optional.of(requireBody(restClient.get()
                    .uri(uri("/pages/" + pageId, "workspace_id", workspaceId))
                    .retrieve()
                    .body(WikiPageDetailResponse.class)));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public List<WikiPageSnapshot> lookup(List<String> pageIds, String workspaceId) {
        if (pageIds.isEmpty()) return List.of();
        List<WikiPageSnapshot> response = restClient.post()
                .uri(endpoint + "/pages/lookup")
                .body(new LookupRequest(pageIds, workspaceId))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return response == null ? List.of() : response;
    }

    public DocumentWikiContext documentContext(String workspaceId, String documentId) {
        return requireBody(restClient.get()
                .uri(uri("/documents/" + documentId + "/context", "workspace_id", workspaceId))
                .retrieve()
                .body(DocumentWikiContext.class));
    }

    public void deleteDocument(String workspaceId, String documentId) {
        restClient.delete()
                .uri(uri("/workspaces/" + workspaceId + "/documents/" + documentId, null, null))
                .retrieve()
                .toBodilessEntity();
    }

    public Instant lastUpdatedAt(String workspaceId) {
        LastUpdated response = requireBody(restClient.get()
                .uri(uri("/workspaces/" + workspaceId + "/last-updated", null, null))
                .retrieve()
                .body(LastUpdated.class));
        return response.updatedAt();
    }

    private java.net.URI uri(String path, String queryName, String queryValue) {
        var builder = UriComponentsBuilder.fromUriString(endpoint).path(path);
        if (queryName != null) builder.queryParam(queryName, queryValue);
        return builder.build().encode().toUri();
    }

    private <T> T requireBody(T body) {
        if (body == null) {
            throw new PipelineWikiPageException("Wiki 상태 응답이 비어 있습니다.", 503, null);
        }
        return body;
    }

    private record LookupRequest(
            @JsonProperty("page_ids") List<String> pageIds,
            @JsonProperty("workspace_id") String workspaceId
    ) {}

    private record LastUpdated(@JsonProperty("updated_at") Instant updatedAt) {}

    public record WikiPageSnapshot(
            String id,
            @JsonProperty("page_type") String pageType,
            String title,
            String slug,
            @JsonProperty("workspace_id") String workspaceId,
            String status
    ) {}

    public record DocumentWikiContext(
            List<DocumentPage> pages,
            @JsonProperty("source_blocks") List<SourceBlock> sourceBlocks
    ) {}

    public record DocumentPage(
            String id,
            @JsonProperty("page_type") String pageType,
            String title,
            String slug,
            @JsonProperty("relation_type") String relationType,
            double confidence
    ) {}

    public record SourceBlock(
            @JsonProperty("block_id") String blockId,
            String text
    ) {}
}
