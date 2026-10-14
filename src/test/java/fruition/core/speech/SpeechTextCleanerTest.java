package fruition.core.speech;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpeechTextCleanerTest {

    @Test
    void removesCitationMarkers() {
        assertThat(SpeechTextCleaner.clean("검색은 인덱스를 씁니다[1]. 캐시도 씁니다 [2, 3][4-5][^6].")).isEqualTo("검색은 인덱스를 씁니다. 캐시도 씁니다.");
        assertThat(SpeechTextCleaner.clean("인덱스를 씁니다 [2].\n끝 [3]\n다음")).isEqualTo("인덱스를 씁니다.\n끝\n다음");
        assertThat(SpeechTextCleaner.clean("목록 [1, 2, 3]에서 고릅니다")).isEqualTo("목록 에서 고릅니다");
        // 숫자가 아닌 대괄호는 남긴다
        assertThat(SpeechTextCleaner.clean("[참고] 문서")).isEqualTo("[참고] 문서");
    }

    @Test
    void removesCodeEntirely() {
        String markdown = "아래처럼 합니다.\n```java\nint a = 1;\n```\n그리고 `foo()`를 호출합니다.\n~~~\nraw\n~~~\n끝";
        assertThat(SpeechTextCleaner.clean(markdown)).isEqualTo("아래처럼 합니다.\n그리고 를 호출합니다.\n끝");
        // 닫히지 않은 펜스는 끝까지 코드로 본다
        assertThat(SpeechTextCleaner.clean("앞\n```\nunclosed")).isEqualTo("앞");
    }

    @Test
    void keepsLinkTextAndDropsUrlsImagesAndHtml() {
        assertThat(SpeechTextCleaner.clean("[공식 문서](https://example.com/a?b=1)를 보세요")).isEqualTo("공식 문서를 보세요");
        assertThat(SpeechTextCleaner.clean("[문서][ref] 참고\n\n[ref]: https://example.com")).isEqualTo("문서 참고");
        assertThat(SpeechTextCleaner.clean("그림 ![구조도](https://example.com/a.png) 입니다")).isEqualTo("그림 입니다");
        assertThat(SpeechTextCleaner.clean("주소는 https://example.com/x 입니다 <br> 끝<b>!</b>")).isEqualTo("주소는 입니다 끝!");
        assertThat(SpeechTextCleaner.clean("<https://example.com> 주소")).isEqualTo("주소");
    }

    @Test
    void stripsMarkdownSyntax() {
        String markdown = "# 제목\n\n## 소제목\n**굵게** 와 *기울임* 과 _밑줄_ 과 ~~취소~~\n\n- 첫째\n* 둘째\n+ 셋째\n\n> 인용문\n> > 중첩\n\n---";
        assertThat(SpeechTextCleaner.clean(markdown))
                .isEqualTo("제목\n소제목\n굵게 와 기울임 과 밑줄 과 취소\n첫째\n둘째\n셋째\n인용문\n중첩");
    }

    @Test
    void flattensTables() {
        String markdown = "| 이름 | 값 |\n| --- | :---: |\n| a | 1 |\n| b | 2 |";
        assertThat(SpeechTextCleaner.clean(markdown)).isEqualTo("이름, 값\na, 1\nb, 2");
    }

    @Test
    void removesInternalBlockRefsAndCollapsesWhitespace() {
        assertThat(SpeechTextCleaner.clean("본문 [doc:B0001] 이어서 [B0001, B0002]입니다. 또 [ws-1.doc:B0003, B0004]\n\n\n공백\t\t많음\r\n다음 줄  "))
                .isEqualTo("본문 이어서 입니다. 또\n공백 많음\n다음 줄");
        assertThat(SpeechTextCleaner.clean("결과입니다 [doc:B0001].")).isEqualTo("결과입니다.");
    }

    @Test
    void removesFootnoteDefinitionsBeforeCitations() {
        assertThat(SpeechTextCleaner.clean("본문[1]\n\n[1]: 출처 설명\n[^2]: 각주 설명")).isEqualTo("본문");
    }

    @Test
    void stripsOrderedListMarkers() {
        assertThat(SpeechTextCleaner.clean("1. 첫째\n2) 둘째\n  10. 열째")).isEqualTo("첫째\n둘째\n열째");
    }

    @Test
    void handlesUrlsWithParentheses() {
        assertThat(SpeechTextCleaner.clean("[위키](https://en.wikipedia.org/wiki/Foo_(bar))를 보세요")).isEqualTo("위키를 보세요");
        assertThat(SpeechTextCleaner.clean("(https://en.wikipedia.org/wiki/Foo_(bar)) 참고")).isEqualTo("() 참고");
        assertThat(SpeechTextCleaner.clean("주소 https://example.com/x. 다음")).isEqualTo("주소 . 다음");
    }

    @Test
    void codeOnlyAnswerBecomesEmpty() {
        assertThat(SpeechTextCleaner.clean("```sql\nselect 1;\n```")).isEmpty();
    }
}
