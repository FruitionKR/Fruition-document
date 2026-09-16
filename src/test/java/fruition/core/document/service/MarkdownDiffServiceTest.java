package fruition.core.document.service;

import fruition.core.document.dto.DocumentContentDiffResponse;
import fruition.core.document.exception.MarkdownDiffTooLargeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkdownDiffServiceTest {

    private final MarkdownDiffService service = new MarkdownDiffService();

    @Test
    void compare_returnsGitHubStyleLineDiff() {
        DocumentContentDiffResponse response = service.compare(
                "doc_1", 1, "# 제목\n기존 내용\n마지막",
                2, "# 제목\n변경 내용\n추가 내용\n마지막");

        assertThat(response.additions()).isEqualTo(2);
        assertThat(response.deletions()).isEqualTo(1);
        assertThat(response.hunks()).hasSize(1);
        assertThat(response.hunks().getFirst().lines())
                .extracting(DocumentContentDiffResponse.Line::type)
                .containsExactly(
                        DocumentContentDiffResponse.Type.CONTEXT,
                        DocumentContentDiffResponse.Type.DELETE,
                        DocumentContentDiffResponse.Type.ADD,
                        DocumentContentDiffResponse.Type.ADD,
                        DocumentContentDiffResponse.Type.CONTEXT);
        assertThat(response.hunks().getFirst().lines().get(1).oldLine()).isEqualTo(2);
        assertThat(response.hunks().getFirst().lines().get(1).newLine()).isNull();
        assertThat(response.hunks().getFirst().lines().get(2).oldLine()).isNull();
        assertThat(response.hunks().getFirst().lines().get(2).newLine()).isEqualTo(2);
    }

    @Test
    void compare_sameMarkdown_returnsEmptyHunks() {
        DocumentContentDiffResponse response =
                service.compare("doc_1", 1, "동일", 2, "동일");

        assertThat(response.additions()).isZero();
        assertThat(response.deletions()).isZero();
        assertThat(response.hunks()).isEmpty();
    }

    @Test
    void compare_emptyMarkdown_returnsEmptyHunks() {
        DocumentContentDiffResponse response =
                service.compare("doc_1", 1, "", 2, "");

        assertThat(response.additions()).isZero();
        assertThat(response.deletions()).isZero();
        assertThat(response.hunks()).isEmpty();
    }

    // 기존 hand-rolled Myers 구현은 "편집 거리"로 메모리를 가늠했기 때문에, 문서 자체는
    // 작아도(2,000줄) 모든 줄이 달라 편집 거리가 크면 예외를 던졌다. java-diff-utils의
    // 선형 공간 Myers 구현은 편집 거리와 무관하게 O(n+m) 메모리만 쓰므로 이 시나리오는
    // 더 이상 거부 대상이 아니다(HIGH 결함 수정 대상). 가드는 이제 "입력 자체의 크기"만 본다.
    @Test
    void compare_completelyDifferentButSmallMarkdown_returnsDiffInsteadOfException() {
        String before = differentLines("이전", 2_000);
        String after = differentLines("이후", 2_000);

        DocumentContentDiffResponse response = service.compare("doc_1", 1, before, 2, after);

        assertThat(response.additions()).isEqualTo(2_000);
        assertThat(response.deletions()).isEqualTo(2_000);
    }

    @Test
    void compare_oversizedInput_throwsMarkdownDiffTooLargeException() {
        StringBuilder oversized = new StringBuilder();
        while (oversized.length() < 20_000_000) {
            oversized.append("x".repeat(1000)).append('\n');
        }
        String after = oversized + "extra\n";

        assertThatThrownBy(() -> service.compare("doc_1", 1, oversized.toString(), 2, after))
                .isInstanceOf(MarkdownDiffTooLargeException.class)
                .hasMessage("두 문서의 차이가 너무 커서 안전하게 비교할 수 없습니다.");
    }

    @Test
    void compare_largeDocumentWithSmallEdit_returnsDiffNotTooLarge() {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < 100_000; index++) {
            builder.append("line ").append(index).append('\n');
        }
        String before = builder.toString();
        String after = before.replace("line 77777\n", "line 77777 변경\n");

        DocumentContentDiffResponse response = service.compare("doc_1", 1, before, 2, after);

        assertThat(response.additions()).isEqualTo(1);
        assertThat(response.deletions()).isEqualTo(1);
    }

    @Test
    void compare_crlfAndLfMixedLineEndings_producesLineLevelDiff() {
        String before = "line1\r\nline2\nline3";
        String after = "line1\r\nline2 changed\nline3";

        DocumentContentDiffResponse response = service.compare("doc_1", 1, before, 2, after);

        assertThat(response.additions()).isEqualTo(1);
        assertThat(response.deletions()).isEqualTo(1);
        assertThat(response.hunks().getFirst().lines())
                .extracting(DocumentContentDiffResponse.Line::content)
                .containsExactly("line1", "line2", "line2 changed", "line3");
    }

    @Test
    void compare_trailingNewlineDifference_isDetectedAsAddedLine() {
        DocumentContentDiffResponse response =
                service.compare("doc_1", 1, "a\nb", 2, "a\nb\n");

        assertThat(response.additions()).isEqualTo(1);
        assertThat(response.deletions()).isZero();
    }

    @Test
    void compare_multiByteEmojiContent_producesLineLevelDiff() {
        DocumentContentDiffResponse response = service.compare(
                "doc_1", 1, "안녕 🙂\n두번째 줄",
                2, "안녕 🎉\n두번째 줄");

        assertThat(response.additions()).isEqualTo(1);
        assertThat(response.deletions()).isEqualTo(1);
        assertThat(response.hunks().getFirst().lines())
                .extracting(DocumentContentDiffResponse.Line::content)
                .contains("안녕 🙂", "안녕 🎉", "두번째 줄");
    }

    private String differentLines(String prefix, int count) {
        StringBuilder markdown = new StringBuilder();
        for (int index = 0; index < count; index++) {
            markdown.append(prefix).append(index).append('\n');
        }
        return markdown.toString();
    }
}
