package fruition.core.analytics;

import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class AnalyticsSkillMetadataClientTest {
    @Test void metadataReadsOnlyCanonicalScopeAndCreatorAndHasBoundedTimeout() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var settings = new AnalyticsSettings(true, "", "http://localhost", "local", "test");
            var client = new AnalyticsSkillMetadataClient(settings, server.url("/skills").toString(), "test-internal-token");
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"scope_type\":\"team\",\"created_by\":\"creator\",\"name\":\"private\"}"));
            assertThat(client.find("workspace", "actor", "skill")).contains(new AnalyticsSkillMetadataClient.Metadata("team", "creator"));
            var request = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getPath()).isEqualTo("/skills/skill?workspace_id=workspace&user_id=actor");
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            long started = System.nanoTime();
            assertThat(client.find("workspace", "actor", "skill")).isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(3));
        }
    }
}
