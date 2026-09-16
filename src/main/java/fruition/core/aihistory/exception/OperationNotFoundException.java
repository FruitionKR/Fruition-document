package fruition.core.aihistory.exception;

public class OperationNotFoundException extends RuntimeException {
    public OperationNotFoundException(String operationId) {
        super("AI 작업 로그를 찾을 수 없습니다: operationId=" + operationId);
    }
}
