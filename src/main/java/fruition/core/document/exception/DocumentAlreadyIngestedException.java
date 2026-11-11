package fruition.core.document.exception;

/** 현재 편집본이 마지막으로 성공한 편입과 같은 내용이라 다시 편입하지 않는다. 문서를 고치면 다시 편입할 수 있다. */
public class DocumentAlreadyIngestedException extends RuntimeException {
    public DocumentAlreadyIngestedException(String message) {
        super(message);
    }
}
