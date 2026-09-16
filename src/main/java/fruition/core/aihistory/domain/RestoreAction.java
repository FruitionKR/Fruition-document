package fruition.core.aihistory.domain;

/** 복구에서 페이지 하나에 취할 조치. */
public enum RestoreAction {
    /** 받치는 기여가 하나도 남지 않아 페이지를 소프트 삭제한다. */
    delete,
    /**
     * 제외 대상이 이력의 꼬리라 직전 스냅샷이 곧 정답이다.
     * Backend가 그 revision의 본문과 object key를 재사용하며 LLM 호출이 없다.
     */
    restore,
    /**
     * 제외 대상이 이력 중간에 껴 있어 남은 기여만의 본문이 저장된 적이 없다.
     * llmPipeline이 조각을 다시 붙여 새로 써야 한다.
     */
    rebuild
}
