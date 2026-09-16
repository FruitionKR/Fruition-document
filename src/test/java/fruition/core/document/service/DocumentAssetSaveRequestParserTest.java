package fruition.core.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.exception.InvalidMarkdownContentException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentAssetSaveRequestParserTest {

    private final DocumentAssetSaveRequestParser parser =
            new DocumentAssetSaveRequestParser(new ObjectMapper());

    @Test
    void parse_matchesPlaceholderAndFilePart() {
        UUID attachmentId = UUID.randomUUID();
        MultiValueMap<String, MultipartFile> files = new LinkedMultiValueMap<>();
        files.add("attachment_" + attachmentId, image("diagram.png"));

        var parsed = parser.parse(metadata("![diagram](attachment://" + attachmentId + ")", 3), files);

        assertThat(parsed.baseVersion()).isEqualTo(3);
        assertThat(parsed.attachments()).containsOnlyKeys(attachmentId);
    }

    @Test
    void parse_acceptsPlaceholderUuidRegardlessOfVersion() {
        // 프론트가 v4가 아닌 UUID(예: v7)를 발급해도 placeholder 대응이 성립하면 저장을 막지 않는다.
        UUID attachmentId = UUID.fromString("018f3a2b-1c4d-7e8f-9a0b-1c2d3e4f5a6b");
        MultiValueMap<String, MultipartFile> files = new LinkedMultiValueMap<>();
        files.add("attachment_" + attachmentId, image("diagram.png"));

        var parsed = parser.parse(metadata("![](attachment://" + attachmentId + ")", 1), files);

        assertThat(parsed.attachments()).containsOnlyKeys(attachmentId);
    }

    @Test
    void parse_allowsSaveWithoutImages() {
        var parsed = parser.parse(metadata("# 본문", 1), new LinkedMultiValueMap<>());

        assertThat(parsed.attachments()).isEmpty();
    }

    @Test
    void parse_rejectsPlaceholderWithoutFile() {
        UUID attachmentId = UUID.randomUUID();

        assertThatThrownBy(() -> parser.parse(
                metadata("![](attachment://" + attachmentId + ")", 1),
                new LinkedMultiValueMap<>()))
                .isInstanceOf(InvalidMarkdownContentException.class);
    }

    @Test
    void parse_rejectsUnusedFile() {
        UUID attachmentId = UUID.randomUUID();
        MultiValueMap<String, MultipartFile> files = new LinkedMultiValueMap<>();
        files.add("attachment_" + attachmentId, image("unused.png"));

        assertThatThrownBy(() -> parser.parse(metadata("# 본문", 1), files))
                .isInstanceOf(InvalidMarkdownContentException.class);
    }

    @Test
    void parse_rejectsDuplicateFilePart() {
        UUID attachmentId = UUID.randomUUID();
        MultiValueMap<String, MultipartFile> files = new LinkedMultiValueMap<>();
        files.add("attachment_" + attachmentId, image("first.png"));
        files.add("attachment_" + attachmentId, image("second.png"));

        assertThatThrownBy(() -> parser.parse(
                metadata("![](attachment://" + attachmentId + ")", 1), files))
                .isInstanceOf(InvalidMarkdownContentException.class);
    }

    @Test
    void parse_rejectsInvalidMetadataAndPartNames() {
        assertThatThrownBy(() -> parser.parse("{}", new LinkedMultiValueMap<>()))
                .isInstanceOf(InvalidMarkdownContentException.class);

        MultiValueMap<String, MultipartFile> files = new LinkedMultiValueMap<>();
        files.add("file", image("diagram.png"));
        assertThatThrownBy(() -> parser.parse(metadata("# 본문", 1), files))
                .isInstanceOf(InvalidMarkdownContentException.class);
    }

    private String metadata(String markdown, long baseVersion) {
        return """
                {"markdown":%s,"base_version":%d}
                """.formatted(toJsonString(markdown), baseVersion);
    }

    private String toJsonString(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private MockMultipartFile image(String filename) {
        return new MockMultipartFile(
                "file", filename, "image/png", "png".getBytes(StandardCharsets.UTF_8));
    }
}
