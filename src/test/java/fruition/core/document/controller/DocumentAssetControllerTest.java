package fruition.core.document.controller;

import fruition.core.document.service.DocumentAssetReadService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentAssetControllerTest {

    private final DocumentAssetReadService readService = mock(DocumentAssetReadService.class);
    private final DocumentAssetController controller = new DocumentAssetController(readService);

    @Test
    void getContent_setsPrivateSecurityAndEntityHeaders() {
        UUID assetId = UUID.randomUUID();
        var metadata = new DocumentAssetReadService.AssetMetadata(
                "image/png", 3, "\"hash\"", "assets/ws_1/content");
        when(readService.readMetadata("ws_1", "user_1", assetId)).thenReturn(metadata);
        when(readService.openStream(metadata))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        var response = controller.getContent("ws_1", "user_1", assetId, webRequest(null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(3);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"hash\"");
        assertThat(response.getHeaders().getCacheControl()).contains("private");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getBody()).isInstanceOf(InputStreamResource.class);
    }

    @Test
    void getContent_matchingEtagReturnsNotModifiedWithoutReadingObject() {
        UUID assetId = UUID.randomUUID();
        when(readService.readMetadata("ws_1", "user_1", assetId)).thenReturn(
                new DocumentAssetReadService.AssetMetadata(
                        "image/png", 3, "\"hash\"", "assets/ws_1/content"));

        var response = controller.getContent("ws_1", "user_1", assetId, webRequest("\"hash\""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"hash\"");
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("private");
        verify(readService, never()).openStream(any());
    }

    @Test
    void getContent_weakAndListedEtagsAlsoReturnNotModified() {
        UUID assetId = UUID.randomUUID();
        when(readService.readMetadata("ws_1", "user_1", assetId)).thenReturn(
                new DocumentAssetReadService.AssetMetadata(
                        "image/png", 3, "\"hash\"", "assets/ws_1/content"));

        assertThat(controller.getContent("ws_1", "user_1", assetId, webRequest("W/\"hash\""))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(controller.getContent("ws_1", "user_1", assetId, webRequest("\"other\", \"hash\""))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        verify(readService, never()).openStream(any());
    }

    private WebRequest webRequest(String ifNoneMatch) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/workspaces/ws_1/assets");
        if (ifNoneMatch != null) request.addHeader(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        return new ServletWebRequest(request, new MockHttpServletResponse());
    }
}
