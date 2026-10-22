package fruition.core.document.service;

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

}
