package fruition.core.document.service;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
class ConvertedMarkdownChunksTest {
    @Test void largeUnicodeContentIsSplitWithoutDroppingBytes() {
        String markdown = "# section\n한글😀\n".repeat(600000);
        var chunks = ConvertedMarkdownChunks.split(markdown);
        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(String.join("", chunks)).isEqualTo(markdown);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64 * 1024));
    }
}
