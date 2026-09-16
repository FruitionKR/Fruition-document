package fruition.core.aihistory.exception;

/** 미리보기 이후 대상이 바뀐 경우. 되돌리기는 무를 수 없으므로 실행하지 않고 다시 확인시킨다. */
public class RestorePreviewStaleException extends RuntimeException {
    public RestorePreviewStaleException() {
        super("복구 대상이 변경되었습니다. 미리보기를 다시 확인해 주세요.");
    }
}
