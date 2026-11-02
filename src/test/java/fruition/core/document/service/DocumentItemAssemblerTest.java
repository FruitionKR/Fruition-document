package fruition.core.document.service;

import fruition.core.document.domain.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentItemAssemblerTest {

    private static Document markdownDocument(String contentHash) {
        return new Document("doc-1", "ws-1", "user-1", "새 노트.md", "text/markdown",
                10, "sources/documents/doc-1/original", contentHash);
    }

    @Test
    @DisplayName("직접 생성한 Markdown은 아직 ingest 전(content_hash null)이므로 재분석 대상이다")
    void needsReingest_directMarkdownNeverIngested_returnsTrue() {
        Document document = markdownDocument(null);
        document.initializeDirectMarkdown("hash", 10, 1);

        assertThat(DocumentItemAssembler.needsReingest(document)).isTrue();
    }

    @Test
    @DisplayName("복제로 생성한 Markdown도 ingest 전이므로 재분석 대상이다")
    void needsReingest_duplicatedMarkdownNeverIngested_returnsTrue() {
        Document document = markdownDocument(null);
        document.initializeDuplicate("src-doc", null, "hash", 10, 1);

        assertThat(DocumentItemAssembler.needsReingest(document)).isTrue();
    }

    @Test
    @DisplayName("변환 placeholder는 처리 중이므로 재분석 대상이 아니다")
    void needsReingest_convertPlaceholderProcessing_returnsFalse() {
        Document document = markdownDocument(null);
        document.initializeConvertPlaceholder("src-doc", UUID.randomUUID(), "hash", 10, 1);

        assertThat(DocumentItemAssembler.needsReingest(document)).isFalse();
    }

    @Test
    @DisplayName("ingest 스냅샷과 편집본 해시가 같으면 재분석 대상이 아니다")
    void needsReingest_sameHash_returnsFalse() {
        Document document = markdownDocument("hash");

        assertThat(DocumentItemAssembler.needsReingest(document)).isFalse();
    }

    @Test
    @DisplayName("ingest 스냅샷과 편집본 해시가 다르면 재분석 대상이다")
    void needsReingest_editedAfterIngest_returnsTrue() {
        Document document = markdownDocument("old-hash");
        document.initializeDirectMarkdown("new-hash", 10, 1);

        assertThat(DocumentItemAssembler.needsReingest(document)).isTrue();
    }

    @Test
    @DisplayName("EDITABLE이 아닌 문서는 재분석 대상이 아니다")
    void needsReingest_originalRole_returnsFalse() {
        Document document = new Document("doc-2", "ws-1", "user-1", "원본.pdf", "application/pdf",
                10, "sources/documents/doc-2/original", null);
        document.initializeDirectMarkdown("hash", 10, 1);

        assertThat(DocumentItemAssembler.needsReingest(document)).isFalse();
    }

    @Test
    @DisplayName("거절 코드는 문서 목록 항목의 error_code로 내려가고, 재ingest나 코드 없는 실패로 바뀌면 비워진다")
    void errorCode_exposedOnItemAndClearedOnLaterTransitions() {
        Document document = markdownDocument("hash");
        document.markProcessingFailed("AI_USAGE_LIMIT_EXCEEDED", "한도 문구", java.time.Instant.now());
        assertThat(document.getErrorCode()).isEqualTo("AI_USAGE_LIMIT_EXCEEDED");
        assertThat(document.getErrorMessage()).isEqualTo("한도 문구");

        document.reopenForReingest("hash", 10);
        assertThat(document.getErrorCode()).isNull();

        document.markProcessingFailed("AI_USAGE_LIMIT_EXCEEDED", "한도 문구", java.time.Instant.now());
        document.markProcessingFailed("파이프라인 실패", java.time.Instant.now());
        assertThat(document.getErrorCode()).isNull();
    }
}
