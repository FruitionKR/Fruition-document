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

    private static final String BASE = "첫 줄\n\n가운데\n\n끝 줄\n";

    private static DocumentContentVersionRepository versionsWith(String base) {
        var versions = mock(DocumentContentVersionRepository.class);
        when(versions.findById(any())).thenReturn(Optional.ofNullable(base == null ? null
                : new DocumentContentVersion("doc_1", 1, base, "base-hash", "user", Instant.now())));
        return versions;
    }

    @Test
    void mergeOnlyStillMergesEditsThatDoNotOverlap() {
        var merge = ContentSaveMerge.mergeOnly(versionsWith(BASE), "doc_1", 1, "첫 줄(AI)\n\n가운데\n\n끝 줄\n");

        var result = merge.resolve(2, "첫 줄\n\n가운데\n\n끝 줄(사람)\n");

        assertThat(result.markdown()).isEqualTo("첫 줄(AI)\n\n가운데\n\n끝 줄(사람)\n");
        assertThat(merge.outcome().status()).isEqualTo("merged");
    }

    @Test
    void mergeOnlyRejectsOverlapInsteadOfOverwriting() {
        var merge = ContentSaveMerge.mergeOnly(versionsWith(BASE), "doc_1", 1, "첫 줄\n\n가운데(AI)\n\n끝 줄\n");

        assertThat(merge.resolve(2, "첫 줄\n\n가운데(사람)\n\n끝 줄\n")).isNull();
        assertThat(merge.outcome().applied()).isFalse();
    }

    /** 기준 스냅샷이 없으면 겹치는지 알 수 없다. 덮어쓰지 않는다. */
    @Test
    void mergeOnlyRejectsWhenBaseSnapshotIsMissing() {
        var merge = ContentSaveMerge.mergeOnly(versionsWith(null), "doc_1", 1, "AI 본문\n");

        assertThat(merge.resolve(2, "사람 본문\n")).isNull();
    }

    @Test
    void defaultModeStillOverwritesOnOverlap() {
        var merge = new ContentSaveMerge(versionsWith(BASE), "doc_1", 1, "첫 줄\n\n가운데(요청)\n\n끝 줄\n");

        var result = merge.resolve(2, "첫 줄\n\n가운데(서버)\n\n끝 줄\n");

        assertThat(result.markdown()).isEqualTo("첫 줄\n\n가운데(요청)\n\n끝 줄\n");
        assertThat(merge.outcome().status()).isEqualTo("overwritten");
    }
}
