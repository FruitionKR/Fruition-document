package fruition.core.aihistory.exception;

/** 이미 확정된 작업에 다른 내용의 콜백이 다시 온 경우. 재전송이 아니라 계약 위반이다. */
public class OperationPayloadConflictException extends RuntimeException {
    public OperationPayloadConflictException(String operationId) {
        super("이미 처리된 작업에 다른 결과가 도착했습니다: operationId=" + operationId);
    }
}
