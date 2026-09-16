package fruition.core.document.service;

import fruition.core.document.exception.DocumentAssetTooLargeException;
import fruition.core.document.exception.InvalidDocumentAssetException;
import fruition.core.document.exception.UnsupportedDocumentAssetException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentAssetValidatorTest {

    private final DocumentAssetValidator validator = new DocumentAssetValidator();

    @Test
    void validate_detectsPngFromBytesAndPreservesBytes() throws Exception {
        byte[] bytes = imageBytes("png", 2, 3);
        MockMultipartFile file = new MockMultipartFile(
                "attachment", "folder/diagram.jpg", "image/jpeg", bytes);

        var result = validator.validate(file);

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.originalFilename()).isEqualTo("diagram.jpg");
        assertThat(result.width()).isEqualTo(2);
        assertThat(result.height()).isEqualTo(3);
        assertThat(result.bytes()).containsExactly(bytes);
        assertThat(result.contentHash()).hasSize(64);
    }

    @Test
    void validate_rejectsSvgAndSpoofedMime() {
        MockMultipartFile svg = new MockMultipartFile(
                "attachment", "image.png", "image/png", "<svg/>".getBytes());

        assertThatThrownBy(() -> validator.validate(svg))
                .isInstanceOf(UnsupportedDocumentAssetException.class);
    }

    @Test
    void validate_decodesJpegAndPreservesGifBytes() throws Exception {
        var jpeg = validator.validate(new MockMultipartFile(
                "attachment", "photo.jpeg", "image/jpeg", imageBytes("jpg", 3, 2)));
        byte[] gifBytes = imageBytes("gif", 2, 2);
        var gif = validator.validate(new MockMultipartFile(
                "attachment", "animation.gif", "image/gif", gifBytes));

        assertThat(jpeg.contentType()).isEqualTo("image/jpeg");
        assertThat(jpeg.width()).isEqualTo(3);
        assertThat(gif.contentType()).isEqualTo("image/gif");
        assertThat(gif.bytes()).containsExactly(gifBytes);
    }

    @Test
    void validate_rejectsCorruptedPng() {
        byte[] corrupted = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

        assertThatThrownBy(() -> validator.validate(new MockMultipartFile(
                "attachment", "broken.png", "image/png", corrupted)))
                .isInstanceOf(InvalidDocumentAssetException.class);
    }

    @Test
    void validate_readsWebpCanvasDimensions() {
        byte[] webp = new byte[30];
        writeAscii(webp, 0, "RIFF");
        webp[4] = 22;
        writeAscii(webp, 8, "WEBP");
        writeAscii(webp, 12, "VP8X");
        webp[16] = 10;
        webp[24] = 1;
        webp[27] = 2;

        var result = validator.validate(new MockMultipartFile(
                "attachment", "image.webp", "application/octet-stream", webp));

        assertThat(result.contentType()).isEqualTo("image/webp");
        assertThat(result.width()).isEqualTo(2);
        assertThat(result.height()).isEqualTo(3);
    }

    @Test
    void validate_rejectsWebpDimensionOverLimit() {
        byte[] webp = new byte[30];
        writeAscii(webp, 0, "RIFF");
        webp[4] = 22;
        writeAscii(webp, 8, "WEBP");
        writeAscii(webp, 12, "VP8X");
        webp[16] = 10;
        webp[25] = 0x40;

        assertThatThrownBy(() -> validator.validate(new MockMultipartFile(
                "attachment", "wide.webp", "image/webp", webp)))
                .isInstanceOf(DocumentAssetTooLargeException.class);
    }

    @Test
    void validate_rejectsEmptyAndOversizedFile() {
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile(
                "attachment", "empty.png", "image/png", new byte[0])))
                .isInstanceOf(InvalidDocumentAssetException.class);

        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.getSize()).thenReturn(DocumentAssetValidator.MAX_FILE_BYTES + 1);
        assertThatThrownBy(() -> validator.validate(oversized))
                .isInstanceOf(DocumentAssetTooLargeException.class);
    }

    @Test
    void validateAll_rejectsMoreThanTwentyFiles() {
        Map<UUID, MultipartFile> files = new LinkedHashMap<>();
        for (int index = 0; index < 21; index++) files.put(UUID.randomUUID(), mock(MultipartFile.class));

        assertThatThrownBy(() -> validator.validateAll(files))
                .isInstanceOf(DocumentAssetTooLargeException.class);
    }

    @Test
    void validateAll_rejectsRequestOverOneHundredMegabytesBeforeReading() {
        Map<UUID, MultipartFile> files = new LinkedHashMap<>();
        for (int index = 0; index < 11; index++) {
            MultipartFile file = mock(MultipartFile.class);
            when(file.getSize()).thenReturn(DocumentAssetValidator.MAX_FILE_BYTES);
            files.put(UUID.randomUUID(), file);
        }

        assertThatThrownBy(() -> validator.validateAll(files))
                .isInstanceOf(DocumentAssetTooLargeException.class);
    }

    @Test
    void validateAll_keepsPlaceholderPairingWithValidatedAssets() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Map<UUID, MultipartFile> files = new LinkedHashMap<>();
        files.put(first, new MockMultipartFile("a", "a.png", "image/png", imageBytes("png", 2, 1)));
        files.put(second, new MockMultipartFile("b", "b.png", "image/png", imageBytes("png", 4, 3)));

        Map<UUID, DocumentAssetValidator.ValidatedAsset> validated = validator.validateAll(files);

        assertThat(validated.get(first).width()).isEqualTo(2);
        assertThat(validated.get(second).width()).isEqualTo(4);
    }

    private byte[] imageBytes(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }

    private void writeAscii(byte[] target, int offset, String value) {
        for (int index = 0; index < value.length(); index++) {
            target[offset + index] = (byte) value.charAt(index);
        }
    }
}
