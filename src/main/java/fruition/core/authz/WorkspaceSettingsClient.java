package fruition.core.authz;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

/** access의 워크스페이스 설정 내부 API({@code GET /internal/workspaces/{ws}/settings}) 클라이언트. */
@Component
public class WorkspaceSettingsClient {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSettingsClient.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final RestClient restClient;
    // 업로드마다 access를 부르지 않도록 프로세스 안에서 60초 캐시한다. 실패는 캐시하지 않는다.
    private final Map<String, CachedSetting> cache = new ConcurrentHashMap<>();

    @Autowired
    public WorkspaceSettingsClient(
            @Value("${app.internal.access-base-url}") String accessBaseUrl,
            @Value("${app.internal.callback-token}") String internalToken) {
        this(WorkspaceAiModelClient.buildRestClient(accessBaseUrl, internalToken));
    }

    WorkspaceSettingsClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** 업로드 뒤 자동 편입 설정. 조회에 실패하면 업로드·변환을 막지 않고 꺼진 것으로 본다. */
    public boolean autoIngestOnUpload(String workspaceId) {
        CachedSetting hit = cache.get(workspaceId);
        if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
            return hit.value();
        }
        try {
            WorkspaceSettingsResponse response = restClient.get()
                    .uri("/internal/workspaces/{workspaceId}/settings", workspaceId)
                    .retrieve()
                    .body(WorkspaceSettingsResponse.class);
            boolean value = response != null && response.autoIngestOnUpload();
            cache.put(workspaceId, new CachedSetting(value, Instant.now().plus(CACHE_TTL)));
            return value;
        } catch (Exception e) {
            log.warn("[워크스페이스 설정] 자동 편입 설정 조회 실패 — 꺼진 것으로 처리 workspace={} cause={}",
                    workspaceId, e.toString());
            return false;
        }
    }

    private record CachedSetting(boolean value, Instant expiresAt) {}

    record WorkspaceSettingsResponse(@JsonProperty("auto_ingest_on_upload") boolean autoIngestOnUpload) {}
}
