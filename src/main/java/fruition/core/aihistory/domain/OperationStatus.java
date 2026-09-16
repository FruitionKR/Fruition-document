package fruition.core.aihistory.domain;

/** AI 작업 상태. */
public enum OperationStatus {
    /** ingest 요청을 접수하고 llmPipeline 결과를 기다리는 중. */
    processing,
    /** 복구를 실행 중. 중간 실패 시 같은 restore_manifest로 재시도한다. */
    applying,
    /** 복구의 DB 반영이 끝나고 llmPipeline 통지를 기다리는 중. */
    notify_pending,
    /** 조립 지시서를 보내고 재조립 결과를 기다리는 중. */
    rebuilding,
    succeeded,
    /** 일부 페이지만 반영된 상태. 재조립 부분 실패에서 나온다. */
    partially_succeeded,
    failed,
    /** base 버전 불일치로 반영하지 못한 상태. */
    conflict;

    /** 더 이상 진행하지 않는 상태인지. 콜백 재수신 시 멱등 판단에 쓴다. */
    public boolean isTerminal() {
        return this == succeeded || this == partially_succeeded || this == failed || this == conflict;
    }
}
