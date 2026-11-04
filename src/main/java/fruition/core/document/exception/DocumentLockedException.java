package fruition.core.document.exception;

/** 다른 사용자가 편집 잠금을 보유 중이라 획득/쓰기가 거부됐다. HTTP 423으로 매핑한다. */
public class DocumentLockedException extends RuntimeException {
    private final String documentName;

    public DocumentLockedException(String message) {
        this(message, null);
    }

    public DocumentLockedException(String message, String documentName) {
        super(message);
        this.documentName = documentName;
    }

    /** 응답에 담을 잠긴 문서 제목. 담지 않으면 null. */
    public String getDocumentName() {
        return documentName;
    }
}
