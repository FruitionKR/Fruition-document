package fruition.core.document.exception;

public class DocumentVersionConflictException extends RuntimeException {

    /** 본문 저장 충돌일 때 서버의 현재 편집 revision. 클라이언트가 충돌을 등록할 때 쓴다. 모르면 null. */
    private final Long currentRevision;

    public DocumentVersionConflictException(String message) {
        this(message, null);
    }

    public DocumentVersionConflictException(String message, Long currentRevision) {
        super(message);
        this.currentRevision = currentRevision;
    }

    public Long getCurrentRevision() {
        return currentRevision;
    }
}
