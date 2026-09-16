package fruition.core.aihistory.dto;

import fruition.core.aihistory.domain.RestoreAction;

import java.util.List;

/**
 * 복구 계획 전체. 미리보기 응답과 실행 입력이 같은 값을 쓴다.
 *
 * <p>본문을 읽지 않고 기여 명단만으로 만든다.
 */
public record RestorePlan(List<PageRestorePlan> pages) {

    public RestorePlan {
        pages = List.copyOf(pages);
    }

    public List<PageRestorePlan> byAction(RestoreAction action) {
        return pages.stream().filter(p -> p.action() == action).toList();
    }

    public int deleteCount() {
        return byAction(RestoreAction.delete).size();
    }

    public int restoreCount() {
        return byAction(RestoreAction.restore).size();
    }

    public int rebuildCount() {
        return byAction(RestoreAction.rebuild).size();
    }

    /** 재조립 대상이 없으면 llmPipeline 통지 후 바로 완료로 넘어간다. */
    public boolean hasRebuild() {
        return rebuildCount() > 0;
    }
}
