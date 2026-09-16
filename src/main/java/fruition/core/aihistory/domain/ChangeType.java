package fruition.core.aihistory.domain;

/** 변경내역 한 건의 유형. */
public enum ChangeType {
    /** 새로 만든 리소스. before_revision이 NULL이며 되돌릴 지점이 없다. */
    created,
    updated,
    /** 받치는 기여가 하나도 남지 않아 소프트 삭제한 경우. */
    deleted,
    /** 복구가 예전 버전 본문으로 되돌린 경우. */
    restored,
    /** 복구가 llmPipeline 재조립에 맡긴 경우. 본문을 건드리지 않고 기록만 남긴다. */
    delegated,
    /** 재조립이 성공해 새 버전을 만든 경우. */
    rebuilt,
    /** 재조립이 실패한 경우. 사유는 change_summary에 남긴다. */
    rebuild_failed,
    /** 페이지 이외 복구 조치가 실패한 경우. */
    action_failed,
    /** llmPipeline 복구가 relation link를 제거하거나 복원한 경우. */
    link_removed,
    link_restored
}
