package fruition.core.document.service;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI가 만든 Markdown(PDF 변환·회의록·채팅 내보내기)을 저장하기 전에 원시 HTML과 위험한 링크를 걷어낸다.
 * 화면은 HTML을 그리지 않아 태그가 글자로 보이고, 나중에 HTML을 그리게 되면 저장된 태그가 그대로 실행된다.
 *
 * <ul>
 *   <li>서식 태그({@code <u>}, {@code <span>} 등)는 태그만 지우고 안의 글자를 남긴다. 줄·칸을 나누던 태그는 공백으로 바꾼다.</li>
 *   <li>실행·삽입 태그({@code <script>}, {@code <img>} 등)는 지운다. 블록 전체가 script·style이면 내용까지 지운다.</li>
 *   <li>그 밖의 태그는 {@code List<String>} 같은 본문 글자일 수 있어 글자로 보이게 escape한다.</li>
 *   <li>HTML 주석은 남긴다. 변환 결과의 페이지 표시({@code <!-- page N -->})가 주석이다.</li>
 *   <li>링크·이미지 주소는 http·https·mailto·상대 경로와 png·jpeg·gif·webp {@code data:} 이미지만 허용한다.
 *       그 밖의 주소({@code javascript:} 등)는 링크를 빼고 글자만 남긴다. 외부 이미지는 허용한다.</li>
 * </ul>
 *
 * <p>Markdown 파서로 HTML·링크 노드만 고치므로 코드 블록·인라인 코드·일반 글자는 바뀌지 않는다.
 */
public final class AiMarkdownSanitizer {

    private static final Parser PARSER = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();
    // 속성 부분은 소유 수량자로 쓴다. 일반 반복은 글자마다 재귀해 수천 자짜리 태그 하나로 StackOverflowError가 난다.
    // 세 갈래의 첫 글자가 겹치지 않아 되돌아갈 필요가 없으므로 매칭 결과는 같다.
    private static final Pattern TAG = Pattern.compile(
            "<!--[\\s\\S]*?-->|<(/?)([A-Za-z][A-Za-z0-9-]*)(?:[^>\"']++|\"[^\"]*+\"|'[^']*+')*+>");
    private static final Pattern STRAY_LT = Pattern.compile("(?<!\\\\)<(?!!--)");
    private static final Pattern TAG_NAME = Pattern.compile("^</?([A-Za-z][A-Za-z0-9-]*)");
    private static final Pattern RAW_TEXT_BLOCK = Pattern.compile("^\\s*<(script|style|textarea)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");
    private static final Pattern DATA_IMAGE = Pattern.compile("^data:image/(png|jpeg|gif|webp)[;,]",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> SAFE_SCHEMES = Set.of("http", "https", "mailto");
    private static final Set<String> SEPARATOR_TAGS = Set.of(
            "br", "p", "div", "tr", "td", "th", "li", "hr", "h1", "h2", "h3", "h4", "h5", "h6");
    private static final Set<String> FORMAT_TAGS = Set.of(
            "u", "b", "i", "em", "strong", "span", "font", "sup", "sub", "small", "big", "mark", "s", "strike",
            "del", "ins", "center", "a", "abbr", "cite", "code", "kbd", "var", "q", "table", "thead", "tbody",
            "tfoot", "caption", "colgroup", "col", "ul", "ol", "dl", "dt", "dd", "blockquote", "pre", "section",
            "article", "header", "footer", "figure", "figcaption", "details", "summary");
    private static final Set<String> DROP_TAGS = Set.of(
            "script", "style", "iframe", "frame", "frameset", "object", "embed", "applet", "img", "svg", "math",
            "video", "audio", "source", "track", "picture", "canvas", "form", "input", "button", "select", "option",
            "textarea", "link", "meta", "base", "noscript", "template", "dialog", "portal");
    // 태그를 지운 자리에서 새 태그가 생길 수 있어(<<u>script>) 바뀌지 않을 때까지 반복한다.
    // 이 횟수를 넘기는 입력은 남은 태그를 모두 escape한다. escape는 글자를 지우지 않아 새 태그를 만들지 않는다.
    private static final int MAX_STRIP_PASSES = 5;

    private AiMarkdownSanitizer() {
    }

    public static String sanitize(String markdown) {
        if (markdown == null) {
            return null;
        }
        String current = markdown;
        for (int pass = 0; pass < MAX_STRIP_PASSES; pass++) {
            String next = sanitizeOnce(current, false);
            if (next.equals(current)) {
                return current;
            }
            current = next;
        }
        return sanitizeOnce(current, true);
    }

    private static String sanitizeOnce(String markdown, boolean escapeAll) {
        List<Edit> edits = new ArrayList<>();
        PARSER.parse(markdown).accept(new AbstractVisitor() {
            @Override
            public void visit(HtmlInline node) {
                String literal = node.getLiteral();
                if (literal.startsWith("<!--")) {
                    return;
                }
                Matcher name = TAG_NAME.matcher(literal);
                edit(node, replaceTag(literal, name.find() ? name.group(1) : null, escapeAll));
            }

            @Override
            public void visit(HtmlBlock node) {
                Range range = range(node);
                if (range == null) {
                    return;
                }
                String source = markdown.substring(range.start(), range.end());
                if (RAW_TEXT_BLOCK.matcher(source).find()) {
                    edits.add(new Edit(range.start(), range.end(), ""));
                    return;
                }
                Matcher tag = TAG.matcher(source);
                StringBuilder replaced = new StringBuilder();
                int last = 0;
                while (tag.find()) {
                    // 태그로 매칭되지 않은 '<'(닫히지 않은 태그, 선언·처리 명령 등)도 블록을 HTML로 만들 수 있어 escape한다.
                    replaced.append(escapeStray(source.substring(last, tag.start())));
                    replaced.append(tag.group(2) == null ? tag.group() : replaceTag(tag.group(), tag.group(2), escapeAll));
                    last = tag.end();
                }
                replaced.append(escapeStray(source.substring(last)));
                edits.add(new Edit(range.start(), range.end(), replaced.toString()));
            }

            @Override
            public void visit(Link node) {
                if (isSafe(node.getDestination())) {
                    visitChildren(node);
                } else {
                    edit(node, childrenSource(node));
                }
            }

            @Override
            public void visit(Image node) {
                if (isSafe(node.getDestination())) {
                    visitChildren(node);
                } else {
                    edit(node, childrenSource(node));
                }
            }

            private void edit(Node node, String replacement) {
                Range range = range(node);
                if (range != null) {
                    edits.add(new Edit(range.start(), range.end(), replacement));
                }
            }

            private String childrenSource(Node node) {
                Node first = node.getFirstChild();
                Node last = node.getLastChild();
                Range from = first == null ? null : range(first);
                Range to = last == null ? null : range(last);
                return from == null || to == null ? "" : markdown.substring(from.start(), to.end());
            }
        });
        if (edits.isEmpty()) {
            return markdown;
        }
        edits.sort(Comparator.comparingInt(Edit::start));
        StringBuilder output = new StringBuilder(markdown.length());
        int cursor = 0;
        for (Edit edit : edits) {
            if (edit.start() < cursor) {
                continue;
            }
            output.append(markdown, cursor, edit.start()).append(edit.replacement());
            cursor = edit.end();
        }
        return output.append(markdown, cursor, markdown.length()).toString();
    }

    private static String replaceTag(String tag, String name, boolean escapeAll) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (escapeAll || lower.isEmpty()) {
            return "\\" + tag;
        }
        if (DROP_TAGS.contains(lower)) {
            return "";
        }
        if (SEPARATOR_TAGS.contains(lower)) {
            return " ";
        }
        return FORMAT_TAGS.contains(lower) ? "" : "\\" + tag;
    }

    private static String escapeStray(String text) {
        return STRAY_LT.matcher(text).replaceAll("\\\\<");
    }

    private static boolean isSafe(String destination) {
        // 브라우저는 주소의 공백·제어 문자를 무시하고 scheme을 읽는다(java\tscript: 등).
        String compact = destination.replaceAll("[\\x00-\\x20]", "");
        Matcher scheme = SCHEME.matcher(compact);
        if (!scheme.find()) {
            return true;
        }
        return SAFE_SCHEMES.contains(scheme.group(1).toLowerCase(Locale.ROOT))
                || DATA_IMAGE.matcher(compact).find();
    }

    private static Range range(Node node) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (spans.isEmpty()) {
            return null;
        }
        SourceSpan last = spans.getLast();
        return new Range(spans.getFirst().getInputIndex(), last.getInputIndex() + last.getLength());
    }

    private record Range(int start, int end) {
    }

    private record Edit(int start, int end, String replacement) {
    }
}
