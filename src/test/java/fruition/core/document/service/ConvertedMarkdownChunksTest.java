package fruition.core.document.service;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ConvertedMarkdownChunksTest {
    @Test void renumberPagesOffsetsBatchLocalPageNumbers() {
        String markdown = "<!-- page 1 -->\n\n앞\n\n<!-- page 2 -->\n\n뒤\n";
        assertThat(ConvertedMarkdownChunks.renumberPages(markdown, 11))
                .isEqualTo("<!-- page 11 -->\n\n앞\n\n<!-- page 12 -->\n\n뒤\n");
    }
}
