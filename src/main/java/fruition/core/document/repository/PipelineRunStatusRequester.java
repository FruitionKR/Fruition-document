package fruition.core.document.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Component
public class PipelineRunStatusRequester {

    private final RestClient restClient;
    private final String endpoint;

    public PipelineRunStatusRequester(
            PipelineClientFactory clientFactory,
            @Value("${app.pipeline-run.endpoint}") String endpoint,
            @Value("${app.pipeline-run.timeout-seconds:5}") int timeoutSeconds) {
        this.restClient = clientFactory.restClient(timeoutSeconds);
        this.endpoint = endpoint;
    }

    public Optional<PipelineRunStatus> find(String runId) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri(endpoint + "/" + runId)
                    .retrieve()
                    .body(PipelineRunStatus.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public record PipelineRunStatus(
            String id,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            String mode,
            String status,
            String error,
            JsonNode manifest
    ) {
        /** 편입 저장 성공과 사용자에게 알릴 검색 준비 완료는 다르다. 품질 평가 통과 여부와는 분리한다. */
        public String documentStatus() {
            if (!"succeeded".equals(status)) return status;
            JsonNode post = manifest == null ? null : manifest.get("post_ingest");
            if (post == null || !post.isObject()) return status;
            if ("cancelled".equals(post.path("status").asText())) return "failed";
            JsonNode embedding = post.hasNonNull("embedding") ? post.path("embedding") : post.path("result").path("embedding");
            if (embeddingReady(embedding)) return "succeeded";
            return java.util.Set.of("ready", "needs_review", "failed", "cancelled").contains(post.path("status").asText())
                    ? "failed" : "indexing";
        }

        public String documentError() {
            return "succeeded".equals(status) && "failed".equals(documentStatus())
                    ? "wiki_index_preparation_failed" : error;
        }

        private static boolean embeddingReady(JsonNode embedding) {
            for (String field : java.util.List.of("target_count", "embedded_count", "skipped_count", "failed_count")) {
                if (!embedding.path(field).isIntegralNumber() || embedding.path(field).asLong(-1) < 0) return false;
            }
            return embedding.path("failed_count").asLong() == 0
                    && embedding.path("embedded_count").asLong() + embedding.path("skipped_count").asLong()
                    == embedding.path("target_count").asLong();
        }
    }
}
