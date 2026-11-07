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
    void readsMarkdownAndPdfSettingsSeparately() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"auto_ingest_on_upload\":false,\"auto_ingest_markdown_on_upload\":true,"
                        + "\"auto_ingest_pdf_on_convert\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.autoIngestMarkdownOnUpload("ws_1")).isTrue();
        assertThat(client.autoIngestPdfOnConvert("ws_1")).isFalse();
    }

    @Test
    void newFieldsWinOverLegacyField() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"auto_ingest_on_upload\":true,\"auto_ingest_markdown_on_upload\":false,"
                        + "\"auto_ingest_pdf_on_convert\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.autoIngestMarkdownOnUpload("ws_1")).isFalse();
        assertThat(client.autoIngestPdfOnConvert("ws_1")).isTrue();
    }

    @Test
    void legacyAccessFallsBackToCombinedSetting() {
        // 새 필드를 모르는 옛 access(배포 순서 차이)는 통합 설정만 준다. 두 경로 모두 그 값을 따른다.
        server.expect(requestTo(URL)).andRespond(
                withSuccess("{\"auto_ingest_on_upload\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.autoIngestMarkdownOnUpload("ws_1")).isTrue();
        assertThat(client.autoIngestPdfOnConvert("ws_1")).isTrue();
    }

    @Test
    void legacyAccessFalseSettingIsFalse() {
        server.expect(requestTo(URL)).andRespond(
                withSuccess("{\"auto_ingest_on_upload\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.autoIngestMarkdownOnUpload("ws_1")).isFalse();
        assertThat(client.autoIngestPdfOnConvert("ws_1")).isFalse();
    }

    @Test
    void failureIsTreatedAsOff() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(client.autoIngestMarkdownOnUpload("ws_1")).isFalse();
    }

    @Test
    void successIsCachedButFailureIsNot() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(
                withSuccess("{\"auto_ingest_on_upload\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.autoIngestPdfOnConvert("ws_1")).isFalse();
        assertThat(client.autoIngestPdfOnConvert("ws_1")).isTrue();
        // 세 번째는 캐시에서 읽는다(요청 두 번만 기대했다).
        assertThat(client.autoIngestMarkdownOnUpload("ws_1")).isTrue();
        server.verify();
    }

    @Test
    void describePdfFiguresReadsSettingAndSharesCache() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"auto_ingest_on_upload\":false,\"describe_pdf_figures\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.describePdfFigures("ws_1")).isTrue();
        // 같은 응답을 캐시해 두 설정을 한 번만 조회한다.
        assertThat(client.autoIngestPdfOnConvert("ws_1")).isFalse();
        server.verify();
    }

    @Test
    void describePdfFiguresMissingFieldIsOff() {
        // 설정을 아직 모르는 access(배포 순서 차이)는 필드를 주지 않는다. 외부 전송은 기본 꺼짐이다.
        server.expect(requestTo(URL)).andRespond(
                withSuccess("{\"auto_ingest_on_upload\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.describePdfFigures("ws_1")).isFalse();
    }

    @Test
    void describePdfFiguresFailureIsOff() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(client.describePdfFigures("ws_1")).isFalse();
    }
}
