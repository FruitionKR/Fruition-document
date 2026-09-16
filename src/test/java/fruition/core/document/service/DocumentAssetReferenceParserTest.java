package fruition.core.document.service;

import fruition.core.document.exception.InvalidDocumentAssetException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentAssetReferenceParserTest {

    private final DocumentAssetReferenceParser parser = new DocumentAssetReferenceParser();

    @Test
    void parse_extractsOnlyManagedImageDestinationsAndDeduplicates() {
        UUID assetId = UUID.randomUUID();
        String path = "/api/workspaces/ws_1/assets/" + assetId + "/content";
        String markdown = """
                ![첫 이미지](%s)
                ![같은 이미지](%s)
                [일반 링크](%s)
                외부 이미지 ![](https://example.com/image.png)
                `![코드](%s)`
                """.formatted(path, path, path, path);

        assertThat(parser.parse(markdown)).containsExactly(
                new DocumentAssetReferenceParser.ManagedAssetReference("ws_1", assetId));
    }

    @Test
    void parse_ignoresAttachmentPlaceholderUntilItIsReplaced() {
        assertThat(parser.parse("![](attachment://" + UUID.randomUUID() + ")")).isEmpty();
    }

    @Test
    void parse_rejectsMalformedManagedImagePath() {
        assertThatThrownBy(() -> parser.parse(
                "![](/api/workspaces/ws_1/assets/not-an-id/content)"))
                .isInstanceOf(InvalidDocumentAssetException.class);
    }
}
