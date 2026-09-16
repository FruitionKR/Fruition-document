package fruition.core.document.dto;

import java.util.List;

/**
 * 리소스에 매이지 않는 diff 결과. 문서와 Wiki 페이지가 같은 계산기를 쓴다.
 *
 * <p>API 응답은 이 값을 감싸서 만든다. {@link DocumentContentDiffResponse}가 문서용 어댑터다.
 */
public record MarkdownDiff(
        long fromVersion,
        long toVersion,
        int additions,
        int deletions,
        List<DocumentContentDiffResponse.Hunk> hunks
) {}
