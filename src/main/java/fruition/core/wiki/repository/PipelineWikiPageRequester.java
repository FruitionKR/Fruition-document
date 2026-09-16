package fruition.core.wiki.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.wiki.dto.WikiPageRenameRequest;
import fruition.core.wiki.dto.WikiPageRenameResponse;
import fruition.core.wiki.exception.PipelineWikiPageException;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class PipelineWikiPageRequester {

    private final RestClient restClient;
    private final String endpoint;

    public PipelineWikiPageRequester(
            PipelineClientFactory clientFactory,
            @Value("${app.wiki-page.endpoint}") String endpoint,
            @Value("${app.wiki-page.timeout-seconds:30}") int timeoutSeconds) {
        this.endpoint = endpoint;
        this.restClient = clientFactory.restClient(timeoutSeconds);
    }

    public WikiPageRenameResponse rename(
            String workspaceId,
            String userId,
            String wikiPageId,
            WikiPageRenameRequest request
    ) {
        try {
            WikiPageRenameResponse response = restClient.patch()
                    .uri(endpoint + "/" + wikiPageId + "/rename")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new RenamePayload(userId, workspaceId, request.title(), request.updateSlug()))
                    .retrieve()
                    .body(WikiPageRenameResponse.class);
            if (response == null) {
                throw new PipelineWikiPageException("Wiki 페이지 이름 변경 응답이 비어 있습니다.", 503, null);
            }
            return response;
        } catch (ResourceAccessException e) {
            throw new PipelineWikiPageException("Wiki 페이지 파이프라인 응답 시간이 초과되었습니다.", 503, null);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 400 || status == 404 || status == 409 || status == 422) {
                throw new PipelineWikiPageException(
                        "Wiki 페이지 이름 변경 요청이 거부되었습니다.", status, e.getResponseBodyAsString());
            }
            throw new PipelineWikiPageException("Wiki 페이지 파이프라인을 사용할 수 없습니다.", 503, null);
        }
    }

    private record RenamePayload(
            @JsonProperty("user_id") String userId,
            @JsonProperty("workspace_id") String workspaceId,
            String title,
            @JsonProperty("update_slug") Boolean updateSlug
    ) {}
}
