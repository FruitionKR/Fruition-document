package fruition.core.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/** Optional analytics enrichment; never changes product authorization or skill execution acceptance. */
@Component
public class AnalyticsSkillMetadataClient {
    private final AnalyticsSettings settings;
    private final RestClient client;
    private final String endpoint;
    public AnalyticsSkillMetadataClient(AnalyticsSettings settings,
            @Value("${app.skill.endpoint}") String endpoint,
            @Value("${app.skill.agent-token}") String token) {
        this.settings = settings;
        this.endpoint = endpoint;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofMillis(500)).build());
        factory.setReadTimeout(Duration.ofSeconds(1));
        client = RestClient.builder().requestFactory(factory).defaultHeader("X-Agent-Service-Token", token).build();
    }
    public Optional<Metadata> find(String workspace, String actor, String skill) {
        if (!settings.enabled()) return Optional.empty();
        try {
            var uri = UriComponentsBuilder.fromUriString(endpoint).pathSegment("{skill}")
                    .queryParam("workspace_id", "{workspace}").queryParam("user_id", "{actor}")
                    .encode().buildAndExpand(skill, workspace, actor).toUri();
            JsonNode response = client.get().uri(uri).retrieve().body(JsonNode.class);
            if (response != null && Set.of("personal", "team").contains(response.path("scope_type").asText())
                    && !response.path("created_by").asText().isBlank())
                return Optional.of(new Metadata(response.path("scope_type").asText(), response.path("created_by").asText()));
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("analytics skill_run skipped: missing canonical metadata");
        } catch (RuntimeException e) {
            // Do not log URLs, response bodies, tokens or error messages.
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("analytics skill_run skipped: metadata lookup unavailable");
        }
        return Optional.empty();
    }
    public record Metadata(String scope, String creator) {}
}
