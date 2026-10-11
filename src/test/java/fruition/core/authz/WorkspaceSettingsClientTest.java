package fruition.core.authz;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WorkspaceSettingsClientTest {

    private static final String URL = "http://access/internal/workspaces/ws_1/settings";

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://access");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final WorkspaceSettingsClient client = new WorkspaceSettingsClient(builder.build());

    @Test
    void returnsSettingValue() {
        server.expect(requestTo(URL)).andRespond(
                withSuccess("{\"auto_ingest_on_upload\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.autoIngestOnUpload("ws_1")).isTrue();
    }

    @Test
    void falseSettingIsFalse() {
        server.expect(requestTo(URL)).andRespond(
                withSuccess("{\"auto_ingest_on_upload\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.autoIngestOnUpload("ws_1")).isFalse();
    }

    @Test
    void failureIsTreatedAsOff() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(client.autoIngestOnUpload("ws_1")).isFalse();
    }

    @Test
    void successIsCachedButFailureIsNot() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(
                withSuccess("{\"auto_ingest_on_upload\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.autoIngestOnUpload("ws_1")).isFalse();
        assertThat(client.autoIngestOnUpload("ws_1")).isTrue();
        // 세 번째는 캐시에서 읽는다(요청 두 번만 기대했다).
        assertThat(client.autoIngestOnUpload("ws_1")).isTrue();
        server.verify();
    }
}
