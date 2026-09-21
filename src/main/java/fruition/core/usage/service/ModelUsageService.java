package fruition.core.usage.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;

/** AI DB를 직접 읽지 않고 내부 API에서 인증된 사용자의 집계만 조회한다. */
@Service
public class ModelUsageService {
    private final WorkspaceAccessGuard guard;
    private final RestClient client;
    private final String endpoint;

    public ModelUsageService(WorkspaceAccessGuard guard, PipelineClientFactory factory,
                             @Value("${app.model-usage.endpoint}") String endpoint) {
        this.guard = guard;
        this.client = factory.restClient(10);
        this.endpoint = endpoint;
    }

    public JsonNode read(String workspaceId, String userId, Instant from, Instant to) {
        guard.requireMember(workspaceId, userId);
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회 시작은 종료보다 앞서야 합니다.");
        }
        var uri = UriComponentsBuilder.fromUriString(endpoint)
                .queryParam("workspace_id", workspaceId).queryParam("user_id", userId);
        if (from != null) uri.queryParam("from_at", from.toString());
        if (to != null) uri.queryParam("to_at", to.toString());
        try {
            var result = client.get().uri(uri.build().encode().toUri()).retrieve()
                    .onStatus(status -> status.value() == 400,
                            (request, response) -> { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회 기간을 확인하세요."); })
                    .body(JsonNode.class);
            if (result == null || !result.path("models").isArray()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 사용량 응답이 올바르지 않습니다.");
            }
            return result;
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 사용량을 조회할 수 없습니다.");
        }
    }
}
