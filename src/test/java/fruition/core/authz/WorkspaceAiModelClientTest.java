package fruition.core.authz;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WorkspaceAiModelClientTest {

    private static final String WS = "ws_1";
    private static final String URL = "http://access/internal/workspaces/ws_1/ai-model-settings";
    private static final String BODY = "{\"ingest_lint\":{\"provider\":\"openai\",\"model\":\"gpt-6-luna\"}}";

    private final RestClient.Builder restClientBuilder = RestClient.builder()
            .baseUrl("http://access")
            .defaultHeader("X-Internal-Token", "token_1");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
    private final WorkspaceAiModelClient client = new WorkspaceAiModelClient(restClientBuilder.build());

    @Test
    void get_readsIngestLintFromAccessService() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Token", "token_1"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        WorkspaceAiModelClient.AiModelSelection selection = client.get(WS);

        assertThat(selection.provider()).isEqualTo("openai");
        assertThat(selection.model()).isEqualTo("gpt-6-luna");
        server.verify();
    }

    @Test
    void update_sendsIngestLintAndReadsResponse() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-Internal-Token", "token_1"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(BODY))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        WorkspaceAiModelClient.AiModelSelection selection = client.update(WS, "openai", "gpt-6-luna");

        assertThat(selection.provider()).isEqualTo("openai");
        server.verify();
    }

    // 카탈로그에서 빠진 모델이나 채팅 전용 claude가 저장돼 있어도 워크스페이스 기본 모델로 실행한다(저가 모델 정책).
    @Test
    void get_fallsBackToWorkspaceDefaultWhenStoredModelIsNotAllowed() {
        for (String stored : new String[]{
                "{\"ingest_lint\":{\"provider\":\"claude\",\"model\":\"claude-haiku-4-5-20251001\"}}",
                "{\"ingest_lint\":{\"provider\":\"openai\",\"model\":\"gpt-6-sol\"}}",
                "{\"ingest_lint\":{\"provider\":\"gemini\",\"model\":\"gemini-3.8-flash\"}}"}) {
            server.expect(requestTo(URL)).andRespond(withSuccess(stored, MediaType.APPLICATION_JSON));
        }

        for (int i = 0; i < 3; i++) {
            assertThat(client.get(WS))
                    .isEqualTo(new WorkspaceAiModelClient.AiModelSelection("gemini", "gemini-3.5-flash-lite"));
        }
        server.verify();
    }

    // 빈 응답을 성공으로 오해하면 provider/model이 null인 설정이 흘러간다.
    @Test
    void get_rejectsEmptyResponse() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.get(WS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("워크스페이스 AI 모델 설정 응답이 비어 있습니다");
    }
}
