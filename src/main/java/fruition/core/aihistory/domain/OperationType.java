package fruition.core.aihistory.domain;

/**
 * AI 작업 유형.
 *
 * <p>{@code restore_rebuild}는 별도 유형이 아니라 {@link #restore}의 처리 단계다.
 * 재조립 결과 콜백은 새 작업을 만들지 않고 기존 restore 작업의 rebuilding 단계를 완료시킨다.
 */
public enum OperationType {
    /** 문서 AI 편집. 동기 처리이며 저장과 같은 트랜잭션에서 기록한다. */
    document_edit,
    /** 원문 문서를 Wiki page로 만드는 작업. 비동기이며 콜백으로 결과를 받는다. */
    ingest,
    /** 기존 Wiki를 다듬는 작업. 원문 기여가 아니므로 기여 명단을 만들지 않는다. */
    lint,
    /** 특정 작업의 기여를 걷어내는 복구. */
    restore
}
