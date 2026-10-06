package fruition.core.document.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiMarkdownSanitizerTest {

    @Test
    @DisplayName("PDF 변환의 짝이 맞지 않는 밑줄 태그를 지우고 글자는 남긴다")
    void stripsUnbalancedFormatTags() {
        String markdown = "# AI기반 스마트 약물관리 플랫폼\n\n<u>임시명세서</u>\n\n[1 / 12]\n</u>\n<u>임시명세서\n";

        assertThat(AiMarkdownSanitizer.sanitize(markdown))
                .isEqualTo("# AI기반 스마트 약물관리 플랫폼\n\n임시명세서\n\n[1 / 12]\n\n임시명세서\n");
    }

    @Test
    @DisplayName("실행·삽입 태그는 지우고 script 블록은 내용까지 지운다")
    void dropsExecutableHtml() {
        assertThat(AiMarkdownSanitizer.sanitize("본문 <img src=x onerror=\"alert(1)\"> 끝"))
                .isEqualTo("본문  끝");
        assertThat(AiMarkdownSanitizer.sanitize("앞\n\n<script>\nalert(1)\n</script>\n\n뒤"))
                .isEqualTo("앞\n\n\n\n뒤");
        assertThat(AiMarkdownSanitizer.sanitize("a <<u>script>alert(1)</script> b"))
                .doesNotContain("<script>");
    }

    @Test
    @DisplayName("줄·칸을 나누는 태그는 공백으로 바꿔 글자가 붙지 않게 한다")
    void replacesSeparatorTagsWithSpace() {
        assertThat(AiMarkdownSanitizer.sanitize("첫 줄<br>둘째 줄")).isEqualTo("첫 줄 둘째 줄");
        assertThat(AiMarkdownSanitizer.sanitize("<table><tr><td>이름</td><td>나이</td></tr></table>\n"))
                .isEqualTo("  이름  나이  \n");
    }

    @Test
    @DisplayName("모르는 태그는 본문 글자일 수 있어 글자로 보이게 escape한다")
    void escapesUnknownTags() {
        assertThat(AiMarkdownSanitizer.sanitize("타입은 List<String>입니다")).isEqualTo("타입은 List\\<String>입니다");
    }

    @Test
    @DisplayName("코드·주석·일반 글자는 바꾸지 않는다")
    void keepsCodeCommentsAndText() {
        String markdown = "<!-- page 3 -->\n\n`<u>코드</u>`\n\n```html\n<script>alert(1)</script>\n```\n\n"
                + "javascript:alert(1) 예제와 a < b 비교\n";

        assertThat(AiMarkdownSanitizer.sanitize(markdown)).isEqualTo(markdown);
    }

    @Test
    @DisplayName("위험한 주소의 링크·이미지는 글자만 남기고 안전한 주소는 그대로 둔다")
    void filtersLinkDestinations() {
        assertThat(AiMarkdownSanitizer.sanitize("[여기를 클릭](javascript:alert(1))")).isEqualTo("여기를 클릭");
        assertThat(AiMarkdownSanitizer.sanitize("[x](JaVa\tScRiPt:alert(1))")).isEqualTo("[x](JaVa\tScRiPt:alert(1))");
        assertThat(AiMarkdownSanitizer.sanitize("[x](&#106;avascript:alert(1))")).isEqualTo("x");
        assertThat(AiMarkdownSanitizer.sanitize("![로고](vbscript:x)")).isEqualTo("로고");
        assertThat(AiMarkdownSanitizer.sanitize("[문서](data:text/html;base64,PHNjcmlwdD4=)")).isEqualTo("문서");

        String safe = "[웹](https://example.com) [메일](mailto:a@b.c) [목차](#intro) "
                + "![외부](https://example.com/a.png) ![그림](/api/workspaces/ws/assets/a/content) "
                + "[source crop](data:image/png;base64,AAAA)";
        assertThat(AiMarkdownSanitizer.sanitize(safe)).isEqualTo(safe);
    }
}
