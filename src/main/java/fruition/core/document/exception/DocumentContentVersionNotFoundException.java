package fruition.core.document.exception;

public class DocumentContentVersionNotFoundException extends RuntimeException {
    public DocumentContentVersionNotFoundException(String documentId, long version) {
        super("문서 콘텐츠 버전을 찾을 수 없습니다: documentId=" + documentId + " version=" + version);
    }
}
