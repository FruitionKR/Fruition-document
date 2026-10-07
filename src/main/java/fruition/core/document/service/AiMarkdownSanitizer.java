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

import java.net.IDN;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI가 만든 Markdown(PDF 변환·회의록·채팅 내보내기·채팅 답변)을 저장하기 전에 원시 HTML과 외부 링크를 걷어낸다.
 * 화면은 HTML을 그리지 않아 태그가 글자로 보이고, 나중에 HTML을 그리게 되면 저장된 태그가 그대로 실행된다.
 *
 * <ul>
 *   <li>서식 태그({@code <u>}, {@code <span>} 등)는 태그만 지우고 안의 글자를 남긴다. 줄·칸을 나누던 태그는 공백으로 바꾼다.</li>
 *   <li>실행·삽입 태그({@code <script>}, {@code <img>} 등)는 지운다. 블록 전체가 script·style이면 내용까지 지운다.</li>
 *   <li>그 밖의 태그는 {@code List<String>} 같은 본문 글자일 수 있어 글자로 보이게 escape한다.</li>
 *   <li>HTML 주석은 남긴다. 변환 결과의 페이지 표시({@code <!-- page N -->})가 주석이다.</li>
 *   <li>외부 주소(scheme이 있거나 {@code //}·{@code /\}로 시작)의 이미지는 {@code 외부 이미지(host)}, 링크는
 *       {@code 텍스트 (host)} 글자로 바꾼다. 프롬프트 인젝션으로 문서 내용이 주소에 실려 나가는 것을 막는다(Fruition-ai ADR-0028).
 *       상대 경로·{@code #anchor}와 png·jpeg·gif·webp {@code data:} 이미지는 요청을 밖으로 보내지 않아 그대로 둔다.</li>
 *   <li>{@link #sanitizeConverted}(PDF 변환)는 사용자가 올린 원문의 링크라 http·https·mailto 링크를 남기고 외부 이미지만 바꾼다.</li>
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
    // 브라우저는 '\'를 '/'로 읽어 /\host도 외부 주소가 된다.
    private static final Pattern NETWORK_PATH = Pattern.compile("^[/\\\\]{2}");
    private static final Pattern AUTHORITY = Pattern.compile("^(?:[A-Za-z][A-Za-z0-9+.-]*:)?//([^/?#]*)");
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
    // 바꾼 자리에서 새 태그·링크가 생길 수 있어(<<u>script>, [![a](x)](y)) 바뀌지 않을 때까지 반복한다.
    // 이 횟수를 넘기는 입력은 남은 태그를 모두 escape한다. escape는 글자를 지우지 않아 새 태그를 만들지 않는다.
    private static final int MAX_STRIP_PASSES = 5;

    private AiMarkdownSanitizer() {
    }

    public static String sanitize(String markdown) {
        return sanitize(markdown, false);
    }

    /** PDF 변환 결과용. 원문 링크는 남기고 외부 이미지만 바꾼다. */
    public static String sanitizeConverted(String markdown) {
        return sanitize(markdown, true);
    }

    private static String sanitize(String markdown, boolean keepLinks) {
        if (markdown == null) {
            return null;
        }
        String current = markdown;
        for (int pass = 0; pass < MAX_STRIP_PASSES; pass++) {
            String next = sanitizeOnce(current, false, keepLinks);
            if (next.equals(current)) {
                return current;
            }
            current = next;
        }
        return sanitizeOnce(current, true, keepLinks);
    }

    private static String sanitizeOnce(String markdown, boolean escapeAll, boolean keepLinks) {
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
                String destination = node.getDestination();
                if (keepLinks ? isSafe(destination) : !isExternal(destination)) {
                    visitChildren(node);
                    return;
                }
                String host = host(destination);
                String text = childrenSource(node);
                Range range = range(node);
                boolean autolink = range != null && markdown.charAt(range.start()) == '<';
                if (autolink) {
                    edit(node, host.isEmpty() ? text : host);
                } else if (text.isBlank()) {
                    edit(node, host);
                } else {
                    edit(node, host.isEmpty() ? text : text + " (" + host + ")");
                }
            }

            @Override
            public void visit(Image node) {
                if (!isExternal(node.getDestination())) {
                    visitChildren(node);
                    return;
                }
                String host = host(node.getDestination());
                edit(node, host.isEmpty() ? "외부 이미지" : "외부 이미지(" + host + ")");
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

    private static boolean isExternal(String destination) {
        String compact = destination.replaceAll("[\\x00-\\x20]", "");
        if (DATA_IMAGE.matcher(compact).find()) {
            return false;
        }
        return SCHEME.matcher(compact).find() || NETWORK_PATH.matcher(compact).find();
    }

    /** 주소의 hostname을 소문자로 준다. 한글 도메인은 punycode로 바꾼다. host가 없으면 빈 문자열이다. */
    private static String host(String destination) {
        Matcher authority = AUTHORITY.matcher(destination.replaceAll("[\\x00-\\x20]", "").replace('\\', '/'));
        if (!authority.find()) {
            return "";
        }
        String host = authority.group(1).substring(authority.group(1).lastIndexOf('@') + 1);
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            host = end < 0 ? "" : host.substring(1, end);
        } else {
            host = host.replaceFirst(":.*$", "");
        }
        try {
            return IDN.toASCII(host).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return host.toLowerCase(Locale.ROOT);
        }
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
