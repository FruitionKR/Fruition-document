package fruition.core.agent.security;

import fruition.TestcontainersConfiguration;
import fruition.core.DocumentApplication;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내부 토큰 필터가 실제 Tomcat이 디코딩한 경로로 보호 대상을 판정하는지 확인한다(#8).
 * MockMvc는 servletPath를 비워 두고 pathInfo를 따로 채우므로, 컨테이너가 servletPath를 만드는 실제 포트로 보낸다.
 * java.net.http.HttpClient는 퍼센트 인코딩을 그대로 전송한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        classes = DocumentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"AGENT_INTERNAL_TOKEN=" + InternalTokenFilterHttpIntegrationTest.TOKEN, "management.server.port=0"})
class InternalTokenFilterHttpIntegrationTest {

    static final String TOKEN = "http-test-agent-token";

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @ParameterizedTest
    @ValueSource(strings = {
            "/internal/agent/skill-authoring/references/read",
            "/internal/agent/skill-authoring/references/%72ead",
            "/internal/agent/skill%2Dauthoring/references/read",
            "/%69nternal/agent/skill-authoring/references/read",
            "/internal/agent/tools/execute/move_document",
            "/internal/agent/%74ools/execute/move_document",
            "/internal/agent/tools/%65xecute/move_document",
            "/%69nternal/agent/tools/execute/move_document"})
    void protectedInternalPathRejectsMissingOrWrongToken(String path) throws Exception {
        assertThat(post(path, null).statusCode()).isEqualTo(401);
        assertThat(post(path, "wrong-token").statusCode()).isEqualTo(401);
    }

    /**
     * 대조군: 같은 경로에 올바른 토큰이면 인증을 통과해 핸들러까지 간다. 본문이 깨져 400이지만 401이 아니다.
     * 인코딩한 경로가 실제로 같은 핸들러에 매핑된다는 것도 함께 보인다(아니면 404).
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/internal/agent/skill-authoring/references/read",
            "/internal/agent/skill-authoring/references/%72ead",
            "/internal/agent/tools/execute/move_document",
            "/internal/agent/%74ools/execute/move_document"})
    void protectedInternalPathWithCorrectTokenPassesAuthentication(String path) throws Exception {
        assertThat(post(path, TOKEN).statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                // 깨진 JSON: 인증을 통과하면 본문 해석에서 400으로 끝나 서비스·외부 호출에 닿지 않는다.
                .POST(HttpRequest.BodyPublishers.ofString("{"));
        if (token != null) {
            request.header("X-Agent-Service-Token", token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
