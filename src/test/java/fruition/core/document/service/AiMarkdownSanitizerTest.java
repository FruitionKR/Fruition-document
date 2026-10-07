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
        assertThat(AiMarkdownSanitizer.sanitize("![로고](vbscript:x)")).isEqualTo("외부 이미지");
        assertThat(AiMarkdownSanitizer.sanitize("[문서](data:text/html;base64,PHNjcmlwdD4=)")).isEqualTo("문서");

        String safe = "[목차](#intro) ![그림](/api/workspaces/ws/assets/a/content) "
                + "[source crop](data:image/png;base64,AAAA) [상대](docs/a.md)";
        assertThat(AiMarkdownSanitizer.sanitize(safe)).isEqualTo(safe);
    }

    @Test
    @DisplayName("외부 이미지·링크는 host만 남긴 글자로 바꾼다")
    void neutralizesExternalImagesAndLinks() {
        assertThat(AiMarkdownSanitizer.sanitize("![alt](https://Host.Example/x.png?q=회의록)"))
                .isEqualTo("외부 이미지(host.example)");
        assertThat(AiMarkdownSanitizer.sanitize("[자세히](https://attacker.example/?q=내용)"))
                .isEqualTo("자세히 (attacker.example)");
        assertThat(AiMarkdownSanitizer.sanitize("[](https://host.example/a)")).isEqualTo("host.example");
        assertThat(AiMarkdownSanitizer.sanitize("<https://host.example/a>")).isEqualTo("host.example");
        assertThat(AiMarkdownSanitizer.sanitize("[![i](https://a.example/x.png)](https://b.example)"))
                .isEqualTo("외부 이미지(a.example) (b.example)");
        assertThat(AiMarkdownSanitizer.sanitize("[메일](mailto:a@b.example)")).isEqualTo("메일");
        assertThat(AiMarkdownSanitizer.sanitize("[a](https://user@Host.example:8443/p)")).isEqualTo("a (host.example)");
        assertThat(AiMarkdownSanitizer.sanitize("[r][1]\n\n[1]: https://host.example/a"))
                .isEqualTo("r (host.example)\n\n[1]: https://host.example/a");
        assertThat(AiMarkdownSanitizer.sanitize("[링크](https://예시.한국/a)")).isEqualTo("링크 (xn--vv4b11d.xn--3e0b707e)");
    }

    @Test
    @DisplayName("scheme 없이 //·/\\로 시작하는 주소도 외부로 본다")
    void treatsNetworkPathsAsExternal() {
        assertThat(AiMarkdownSanitizer.sanitize("![](//attacker.example/x.png)")).isEqualTo("외부 이미지(attacker.example)");
        assertThat(AiMarkdownSanitizer.sanitize("![](/\\attacker.example/x.png)")).isEqualTo("외부 이미지(attacker.example)");
        assertThat(AiMarkdownSanitizer.sanitize("![]( \t//attacker.example/x.png)")).isEqualTo("외부 이미지(attacker.example)");
    }

    @Test
    @DisplayName("코드 안의 주소와 평문 URL은 그대로 두고, 두 번 걸러도 결과가 같다")
    void keepsCodeAndPlainUrlsAndIsIdempotent() {
        String code = "`![](https://x.example/a.png)`\n\n```\n[a](https://x.example)\n```\n\nhttps://x.example/plain\n";
        assertThat(AiMarkdownSanitizer.sanitize(code)).isEqualTo(code);

        String once = AiMarkdownSanitizer.sanitize("[![i](https://a.example/x.png)](https://b.example) <a@b.example>");
        assertThat(AiMarkdownSanitizer.sanitize(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("PDF 변환 결과는 원문 링크를 남기고 외부 이미지만 바꾼다")
    void convertedKeepsLinksButDropsExternalImages() {
        assertThat(AiMarkdownSanitizer.sanitizeConverted(
                "[웹](https://example.com) [메일](mailto:a@b.c) ![외부](https://example.com/a.png) "
                        + "![그림](/api/workspaces/ws/assets/a/content) [x](javascript:alert(1))"))
                .isEqualTo("[웹](https://example.com) [메일](mailto:a@b.c) 외부 이미지(example.com) "
                        + "![그림](/api/workspaces/ws/assets/a/content) x");
    }

    @Test
    @DisplayName("수천 자짜리 태그도 스택을 넘치지 않고 처리한다")
    void handlesVeryLongTagsWithoutStackOverflow() throws Exception {
        String[] inputs = {
                "<div " + "a=b ".repeat(5000) + ">x</div>",
                "<div ".repeat(20000),
                "<div " + "a=\"b\" ".repeat(20000) + ">x</div>",
                "<div><img src=data:image/png;base64," + "A".repeat(100000) + "></div>"
        };
        // 운영(Linux x64) 기본 스레드 스택 1MB에서도 넘치지 않아야 한다.
        for (String input : inputs) {
            var result = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread thread = new Thread(null, () -> {
                try {
                    AiMarkdownSanitizer.sanitize(input);
                } catch (Throwable throwable) {
                    result.set(throwable);
                }
            }, "sanitize", 1024 * 1024);
            thread.start();
            thread.join();
            assertThat(result.get()).isNull();
        }
        assertThat(AiMarkdownSanitizer.sanitize("<div " + "a=b ".repeat(5000) + ">x</div>").strip()).isEqualTo("x");
    }

    @Test
    @DisplayName("닫히지 않은 태그가 남은 HTML 블록도 글자로 보이게 escape한다")
    void escapesUnclosedTagsInHtmlBlocks() {
        assertThat(AiMarkdownSanitizer.sanitize("<div onmouseover=alert(1)\n\nhello"))
                .isEqualTo("\\<div onmouseover=alert(1)\n\nhello");
        assertThat(AiMarkdownSanitizer.sanitize("<div>\n<img src=x onerror=alert(1)\n\nhello"))
                .doesNotContainPattern("(?<!\\\\)<img").contains("\\<img");
    }

    @Test
    @DisplayName("접기 태그는 지우고 안의 글자를 남긴다")
    void stripsDetailsAndSummaryTags() {
        assertThat(AiMarkdownSanitizer.sanitize("a <details><summary>요약</summary> b"))
                .isEqualTo("a 요약 b");
    }
}
