package fruition.core.document.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ConvertedMarkdownChunks {
    private static final Pattern PAGE_COMMENT = Pattern.compile("<!-- page (\\d{1,6}) -->");

    private ConvertedMarkdownChunks() {}

    /** 페이지 묶음 변환 결과의 페이지 주석을 원본 PDF 기준 번호로 바꾼다. 변환기는 묶음마다 1쪽부터 센다. */
    static String renumberPages(String markdown, int firstPage) {
        Matcher matcher = PAGE_COMMENT.matcher(markdown);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            int page = Integer.parseInt(matcher.group(1)) + firstPage - 1;
            matcher.appendReplacement(output, "<!-- page " + page + " -->");
        }
        matcher.appendTail(output);
        return output.toString();
    }

    /**
     * 편집 문서 상한을 넘는 변환 결과만 나눈다. 표·수식이 잘리지 않도록 페이지 주석 경계를 우선하고,
     * 한 페이지가 상한을 넘을 때만 줄·문자 단위로 자른다.
     */
    static List<String> split(String markdown, int limit) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bytes = 0;
        for (String page : markdown.split("(?=<!-- page \\d+ -->)", -1)) {
            int length = page.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + length > limit && bytes > 0) {
                chunks.add(current.toString());
                current.setLength(0);
                bytes = 0;
            }
            if (length <= limit) {
                current.append(page);
                bytes += length;
                continue;
            }
            for (String line : page.split("(?<=\\n)", -1)) {
                int lineLength = line.getBytes(StandardCharsets.UTF_8).length;
                if (lineLength <= limit) {
                    if (bytes + lineLength > limit) { chunks.add(current.toString()); current.setLength(0); bytes = 0; }
                    current.append(line); bytes += lineLength;
                    continue;
                }
                if (line.contains("data:image/")) {
                    throw new IllegalArgumentException("단일 삽입 이미지가 편집 문서 처리 단위를 초과했습니다.");
                }
                for (int offset = 0; offset < line.length();) {
                    int cp = line.codePointAt(offset);
                    String value = new String(Character.toChars(cp));
                    int count = value.getBytes(StandardCharsets.UTF_8).length;
                    if (bytes + count > limit) { chunks.add(current.toString()); current.setLength(0); bytes = 0; }
                    current.append(value); bytes += count; offset += Character.charCount(cp);
                }
            }
        }
        if (!current.isEmpty() || chunks.isEmpty()) chunks.add(current.toString());
        return chunks;
    }
}
