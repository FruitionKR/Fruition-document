package fruition.core.document.service;

import fruition.core.document.exception.InvalidDocumentFilenameException;
import fruition.core.document.exception.InvalidMarkdownContentException;
import fruition.core.document.exception.MarkdownContentTooLargeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentEditingRulesTest {

    @org.junit.jupiter.api.Test
    void generatedFilenameUsesCanonicalKoreanToAvoidHiddenDuplicates() {
        assertThat(DocumentEditingRules.uniqueFilename("보고서", Set.of("보고서.md")).filename())
                .isEqualTo("보고서 (2).md");
        assertThat(DocumentEditingRules.rename("보고서", "old.md").filename()).isEqualTo("보고서.md");
    }

    @Test
    @DisplayName("복제 이름은 기존 복사본 번호를 이어가고 255자 안에서 본체를 줄인다")
    void duplicateFilename_selectsNextNumberAndTruncatesBase() {
        Set<String> existingNames = Set.of(
                "보고서.md",
                "보고서 복사본.md",
                "보고서 복사본 (2).md"
        );

        assertThat(DocumentEditingRules.duplicateFilename("보고서", existingNames))
                .isEqualTo(new DocumentEditingRules.Filename(
                        "보고서 복사본 (3)", "보고서 복사본 (3).md", "보고서 복사본 (3).md"));
        assertThat(DocumentEditingRules.duplicateFilename("보고서 복사본", existingNames).filename())
                .isEqualTo("보고서 복사본 (3).md");
        assertThat(DocumentEditingRules.duplicateFilename("가".repeat(255), Set.of()).filename())
                .hasSize(255)
                .endsWith(" 복사본.md");
    }

    @Test
    void rename_preservesPdfAndMarkdownExtensions() {
        assertThat(DocumentEditingRules.rename(" 새 보고서 ", "보고서.pdf"))
                .isEqualTo(new DocumentEditingRules.Filename("새 보고서", "새 보고서.pdf", "새 보고서.pdf"));
        assertThat(DocumentEditingRules.rename("회의록", "기존.MD"))
                .isEqualTo(new DocumentEditingRules.Filename("회의록", "회의록.MD", "회의록.md"));
    }

    @Test
    void rename_rejectsInvalidNamesAndFilenameLongerThan255Characters() {
        for (String invalid : new String[]{" ", ".", "..", "a/b", "a\\b", "a\nb", "a\0b"}) {
            assertThatThrownBy(() -> DocumentEditingRules.rename(invalid, "기존.md"))
                    .isInstanceOf(InvalidDocumentFilenameException.class);
        }

        assertThatThrownBy(() -> DocumentEditingRules.rename("가".repeat(253), "기존.md"))
                .isInstanceOf(InvalidDocumentFilenameException.class);
        assertThat(DocumentEditingRules.rename("가".repeat(252), "기존.md").filename()).hasSize(255);
    }

    @Test
    void markdown_allowsEmptyAndRejectsNull() {
        assertThat(DocumentEditingRules.markdown("").markdown()).isEmpty();
        assertThatThrownBy(() -> DocumentEditingRules.markdown((String) null))
                .isInstanceOf(InvalidMarkdownContentException.class);
    }

    @Test
    void markdown_validatesFiveMegabytesByUtf8Bytes() {
        int koreanCharactersAtLimit = DocumentEditingRules.MAX_MARKDOWN_BYTES / 3;
        String atLimit = "가".repeat(koreanCharactersAtLimit) + "ab";

        assertThat(atLimit.getBytes(StandardCharsets.UTF_8))
                .hasSize(DocumentEditingRules.MAX_MARKDOWN_BYTES);
        assertThat(DocumentEditingRules.markdown(atLimit).bytes())
                .hasSize(DocumentEditingRules.MAX_MARKDOWN_BYTES);
        assertThatThrownBy(() -> DocumentEditingRules.markdown(atLimit + "가"))
                .isInstanceOf(MarkdownContentTooLargeException.class);
    }

    @Test
    void markdown_rejectsMalformedUtf8Bytes() {
        assertThatThrownBy(() -> DocumentEditingRules.markdown(new byte[]{(byte) 0xC3, (byte) 0x28}))
                .isInstanceOf(InvalidMarkdownContentException.class);
    }

    @Test
    void markdown_calculatesSha256AndDetectsNoOp() {
        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.markdown("hello");

        assertThat(content.contentHash())
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(content.hasSameContent(content.contentHash())).isTrue();
        assertThat(content.hasSameContent("different")).isFalse();
    }

    @Test
    @DisplayName("이름이 비어 있으면 번호를 붙이지 않는다")
    void uniqueFilename_whenFree_keepsName() {
        DocumentEditingRules.Filename result =
                DocumentEditingRules.uniqueFilename("검색 인덱싱", java.util.Set.of());

        assertThat(result.displayName()).isEqualTo("검색 인덱싱");
        assertThat(result.filename()).isEqualTo("검색 인덱싱.md");
    }

    @Test
    @DisplayName("이름이 겹치면 비어 있는 번호를 찾아 붙인다")
    void uniqueFilename_whenTaken_appendsNumber() {
        DocumentEditingRules.Filename result = DocumentEditingRules.uniqueFilename(
                "검색 인덱싱", java.util.Set.of("검색 인덱싱.md", "검색 인덱싱 (2).md"));

        assertThat(result.displayName()).isEqualTo("검색 인덱싱 (3)");
        assertThat(result.filename()).isEqualTo("검색 인덱싱 (3).md");
    }

    @Test
    @DisplayName("긴 파일명은 Unicode code point 경계를 보존해 자른다")
    void uniqueFilename_truncatesAtCodePointBoundary() {
        String displayName = "a".repeat(251) + "😀" + "tail";

        DocumentEditingRules.Filename result =
                DocumentEditingRules.uniqueFilename(displayName, java.util.Set.of());

        assertThat(result.filename()).isEqualTo("a".repeat(251) + "😀.md");
        assertThat(result.filename().codePointCount(0, result.filename().length())).isEqualTo(255);
    }

    @Test
    @DisplayName("파일명에 못 쓰는 문자를 걷어낸다")
    void sanitizeDisplayName_stripsUnusableCharacters() {
        assertThat(DocumentEditingRules.sanitizeDisplayName("CI/CD 파이프라인")).isEqualTo("CI CD 파이프라인");
        assertThat(DocumentEditingRules.sanitizeDisplayName("a\\b")).isEqualTo("a b");
        assertThat(DocumentEditingRules.sanitizeDisplayName("앞\n뒤")).isEqualTo("앞 뒤");
    }

    @Test
    @DisplayName("정제 결과가 이름으로 쓸 수 없으면 빈 문자열이다")
    void sanitizeDisplayName_returnsEmptyWhenUnusable() {
        assertThat(DocumentEditingRules.sanitizeDisplayName(null)).isEmpty();
        assertThat(DocumentEditingRules.sanitizeDisplayName("   ")).isEmpty();
        assertThat(DocumentEditingRules.sanitizeDisplayName("/")).isEmpty();
        assertThat(DocumentEditingRules.sanitizeDisplayName("..")).isEmpty();
    }

    @Test
    @DisplayName("정제한 이름은 normalizeDisplayName 검증을 통과한다")
    void sanitizedNameIsAcceptedByUniqueFilename() {
        String sanitized = DocumentEditingRules.sanitizeDisplayName("[채팅] CI/CD 파이프라인");

        DocumentEditingRules.Filename result =
                DocumentEditingRules.uniqueFilename(sanitized, java.util.Set.of());

        assertThat(result.filename()).isEqualTo("[채팅] CI CD 파이프라인.md");
    }
}
