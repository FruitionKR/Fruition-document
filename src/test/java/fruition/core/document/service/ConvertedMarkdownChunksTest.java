package fruition.core.document.service;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
class ConvertedMarkdownChunksTest {
    @Test void contentUnderLimitStaysOneChunk() {
        String markdown = "<!-- page 1 -->\n\n| A |\n| --- |\n\n<!-- page 2 -->\n\n본문\n";
        assertThat(ConvertedMarkdownChunks.split(markdown, 1024)).containsExactly(markdown);
    }

    @Test void overLimitSplitsAtPageBoundariesFirst() {
        String page = "<!-- page %d -->\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n";
        String markdown = String.format(page, 1) + String.format(page, 2) + String.format(page, 3);
        int pageBytes = String.format(page, 1).getBytes(StandardCharsets.UTF_8).length;
        var chunks = ConvertedMarkdownChunks.split(markdown, pageBytes * 2);
        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).startsWith("<!-- page "));
        assertThat(String.join("", chunks)).isEqualTo(markdown);
    }

    @Test void oversizedPageFallsBackToLinesWithoutDroppingBytes() {
        String markdown = "<!-- page 1 -->\n" + "# section\n한글😀\n".repeat(600000);
        int limit = 64 * 1024;
        var chunks = ConvertedMarkdownChunks.split(markdown, limit);
        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(String.join("", chunks)).isEqualTo(markdown);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(limit));
    }

    @Test void renumberPagesOffsetsBatchLocalPageNumbers() {
        String markdown = "<!-- page 1 -->\n\n앞\n\n<!-- page 2 -->\n\n뒤\n";
        assertThat(ConvertedMarkdownChunks.renumberPages(markdown, 11))
                .isEqualTo("<!-- page 11 -->\n\n앞\n\n<!-- page 12 -->\n\n뒤\n");
    }
}
