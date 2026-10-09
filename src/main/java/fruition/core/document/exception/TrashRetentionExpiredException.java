package fruition.core.document.exception;

/** 휴지통 보관 기간이 지나 영구 삭제 대상이 된 문서·폴더는 복구할 수 없다. */
public class TrashRetentionExpiredException extends RuntimeException {
    public TrashRetentionExpiredException(String message) {
        super(message);
    }
}
