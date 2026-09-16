package fruition.core.aihistory.service;

import fruition.core.document.dto.MarkdownDiff;
import fruition.core.document.service.MarkdownDiffService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 변경내역에 남길 증감 줄 수를 센다. 목록 화면이 계산 없이 바로 보여주기 위한 값이다.
 *
 * <p>문서와 Wiki가 같이 쓴다. 리소스 종류를 모르고 본문 두 벌만 받는다.
 */
@Component
public class LineCounter {

    private static final Logger log = LoggerFactory.getLogger(LineCounter.class);

    private final MarkdownDiffService markdownDiffService;

    public LineCounter(MarkdownDiffService markdownDiffService) {
        this.markdownDiffService = markdownDiffService;
    }

    /**
     * 계산이 실패해도 적재를 막지 않는다. 줄 수는 없어도 되는 값이고, 큰 문서는 비교가 거부될 수
     * 있는데 그것 때문에 사용자 저장이나 콜백 수신이 실패하는 것은 잘못된 트레이드오프다.
     *
     * @param before 이전 본문. {@code null}이면 새로 만든 것이라 셀 것이 없다
     */
    public LineCount count(String resourceId, Long beforeRevision, String before,
                           long afterRevision, String after) {
        if (before == null) {
            return LineCount.none();
        }
        try {
            MarkdownDiff diff = markdownDiffService.diff(
                    beforeRevision == null ? 0 : beforeRevision, before, afterRevision, after);
            return new LineCount(diff.additions(), diff.deletions());
        } catch (RuntimeException e) {
            log.warn("[줄 수 계산 생략] resourceId={} reason={}", resourceId, e.getMessage());
            return LineCount.none();
        }
    }

    public record LineCount(Integer additions, Integer deletions) {
        private static final LineCount NONE = new LineCount(null, null);

        public static LineCount none() {
            return NONE;
        }
    }
}
