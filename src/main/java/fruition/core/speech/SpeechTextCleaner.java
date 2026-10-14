package fruition.core.speech;

import java.util.regex.Pattern;

/**
 * 채팅 답변 Markdown을 소리 내어 읽을 문장으로 바꾼다. 규칙 기반이며 모델을 부르지 않는다.
 *
 * <p>코드(블록·인라인)·이미지·HTML·각주 정의·인용 번호·내부 블록 참조는 통째로 지우고, 링크는 글자만 남긴다.
 * 제목·강조·목록(번호 포함)·인용문·표 기호는 기호만 걷어 낸다.
 */
final class SpeechTextCleaner {
    private SpeechTextCleaner() {}

    private static final Pattern FENCE = Pattern.compile("(?ms)^[ \\t]*(```|~~~).*?(^[ \\t]*\\1[^\\n]*$|\\z)");
    private static final Pattern INLINE_CODE = Pattern.compile("(`+)[^`]*?\\1");
    private static final Pattern HTML = Pattern.compile("(?s)<!--.*?-->|</?[a-zA-Z][^>]*>");
    // 링크 주소: 괄호가 한 단계까지 들어간 주소(예: wikipedia.org/wiki/Foo_(bar))도 끝까지 잡는다.
    private static final String DEST = "(?:[^()\\s]|\\([^()\\s]*\\))*";
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\(" + DEST + "\\)|!\\[[^\\]]*]\\[[^\\]]*]");
    // 각주·링크 참조 정의 줄. 인용 번호보다 먼저 지워야 "[1]: 설명" 줄이 번호만 지워진 채 남지 않는다.
    private static final Pattern REF_DEFINITION = Pattern.compile("(?m)^[ \\t]*\\[[^\\]]+]:[ \\t]*\\S.*$");
    // 인용 번호 [1], [1, 2], [1-3], [^1], 번호만 글자로 쓴 링크 [1](url), 그리고 ai가 붙이는 내부 블록 참조
    // [doc:B0001], [B0001, B0002] (ai의 source_references _REF_PATTERN과 같은 형식)
    private static final String MARKER = "(?:\\[\\^?\\d+(?:\\s*[,\\-–]\\s*\\d+)*](?:\\(" + DEST + "\\))?"
            + "|\\[(?:[A-Za-z0-9_.-]+:)?B\\d{4}(?:\\s*,\\s*(?:[A-Za-z0-9_.-]+:)?B\\d{4})*])";
    // 마커 앞 공백까지 지우되, 마커 뒤가 문장부호나 줄 끝일 때만이다("씁니다 [2]." -> "씁니다."). 그 밖에는 앞 공백을 남긴다.
    private static final Pattern MARKER_BEFORE_PUNCT = Pattern.compile(
            "[ \\t]*" + MARKER + "(?:[ \\t]*" + MARKER + ")*(?=[ \\t]*(?:[.,!?;:)\\]。、]|$))", Pattern.MULTILINE);
    private static final Pattern MARKER_ANY = Pattern.compile(MARKER);
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\(" + DEST + "\\)|\\[([^\\]]+)]\\[[^\\]]*]");
    private static final Pattern URL = Pattern.compile("https?://(?:[^\\s()<>]|\\([^\\s()<>]*\\))+(?<![.,;:!?])");
    private static final Pattern RULE = Pattern.compile("(?m)^[ \\t]*([-*_][ \\t]*){3,}$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("(?m)^[ \\t]*\\|?[ \\t]*:?-+:?[ \\t]*(\\|[ \\t]*:?-+:?[ \\t]*)*\\|?[ \\t]*$");
    private static final Pattern TABLE_ROW = Pattern.compile("(?m)^[ \\t]*\\|(.*?)\\|?[ \\t]*$");
    private static final Pattern HEADING = Pattern.compile("(?m)^[ \\t]{0,3}#{1,6}[ \\t]*");
    private static final Pattern QUOTE = Pattern.compile("(?m)^[ \\t]*(>[ \\t]?)+");
    private static final Pattern ORDERED = Pattern.compile("(?m)^[ \\t]*\\d{1,3}[.)][ \\t]+");
    private static final Pattern BULLET = Pattern.compile("(?m)^[ \\t]*[-*+][ \\t]+(\\[[ xX]][ \\t]+)?");
    private static final Pattern STRONG = Pattern.compile("\\*\\*|__|~~");
    private static final Pattern STAR_EMPHASIS = Pattern.compile("\\*([^*\\n]+)\\*");
    private static final Pattern UNDERSCORE_EMPHASIS = Pattern.compile("(?<![\\w])_([^_\\n]+)_(?![\\w])");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\u00A0]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\s*\\n\\s*");

    static String clean(String markdown) {
        String text = markdown.replace("\r\n", "\n").replace('\r', '\n');
        text = FENCE.matcher(text).replaceAll("");
        text = INLINE_CODE.matcher(text).replaceAll("");
        text = HTML.matcher(text).replaceAll("");
        text = IMAGE.matcher(text).replaceAll("");
        text = REF_DEFINITION.matcher(text).replaceAll("");
        text = MARKER_BEFORE_PUNCT.matcher(text).replaceAll("");
        text = MARKER_ANY.matcher(text).replaceAll("");
        text = LINK.matcher(text).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
                m.group(1) != null ? m.group(1) : m.group(2)));
        text = URL.matcher(text).replaceAll("");
        text = RULE.matcher(text).replaceAll("");
        text = TABLE_SEPARATOR.matcher(text).replaceAll("");
        text = TABLE_ROW.matcher(text).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
                m.group(1).replaceAll("[ \\t]*\\|[ \\t]*", ", ").strip()));
        text = HEADING.matcher(text).replaceAll("");
        text = QUOTE.matcher(text).replaceAll("");
        text = ORDERED.matcher(text).replaceAll("");
        text = BULLET.matcher(text).replaceAll("");
        text = STRONG.matcher(text).replaceAll("");
        text = STAR_EMPHASIS.matcher(text).replaceAll("$1");
        text = UNDERSCORE_EMPHASIS.matcher(text).replaceAll("$1");
        text = SPACES.matcher(text).replaceAll(" ");
        return BLANK_LINES.matcher(text).replaceAll("\n").strip();
    }
}
