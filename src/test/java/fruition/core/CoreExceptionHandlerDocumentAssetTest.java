package fruition.core;

import fruition.core.document.exception.DocumentAssetTooLargeException;
import fruition.core.document.exception.MarkdownContentTooLargeException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import static org.assertj.core.api.Assertions.assertThat;

class CoreExceptionHandlerDocumentAssetTest {

    private final CoreExceptionHandler handler = new CoreExceptionHandler();

    @Test
    void payloadLimits_useDistinctErrorCodes() {
        var markdown = handler.handleMarkdownContentTooLarge(
                new MarkdownContentTooLargeException("Markdown 5MB 초과"));
        var assets = handler.handleDocumentAssetTooLarge(
                new DocumentAssetTooLargeException("이미지 100MB 초과"));

        assertThat(markdown.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(markdown.getBody().error().code()).isEqualTo("MARKDOWN_CONTENT_TOO_LARGE");
        assertThat(assets.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(assets.getBody().error().code()).isEqualTo("DOCUMENT_ASSET_TOO_LARGE");
    }

    @Test
    void multipartLimit_isReportedAsPayloadTooLargeNotEmptyFile() {
        var oversized = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(110L * 1024 * 1024));
        var empty = handler.handleMultipartException(new MultipartException("파일 없음"));

        assertThat(oversized.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(oversized.getBody().error().code()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(empty.getBody().error().code()).isEqualTo("INVALID_REQUEST");
    }
}
