package fruition.core.document.service;

import fruition.core.document.domain.DocumentContentVersion;
import fruition.core.document.repository.DocumentContentVersionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContentSaveMergeTest {
    @Test
    void largeDocumentKeepsIndependentEditsAndFullBody() {
        var versions = mock(DocumentContentVersionRepository.class);
        String body = "x".repeat(5 * 1024 * 1024 + 1) + "\n";
        String base = "첫 줄\n" + body + "마지막 줄\n";
        when(versions.findById(any())).thenReturn(Optional.of(
                new DocumentContentVersion("doc_large", 1, base, "base-hash", "user", Instant.now())));
        var merge = new ContentSaveMerge(versions, "doc_large", 1, "첫 줄 수정\n" + body + "마지막 줄\n");

        var result = merge.resolve(2, "첫 줄\n" + body + "마지막 줄 수정\n");

        assertThat(result.markdown()).isEqualTo("첫 줄 수정\n" + body + "마지막 줄 수정\n");
        assertThat(merge.outcome().status()).isEqualTo("merged");
    }
}
