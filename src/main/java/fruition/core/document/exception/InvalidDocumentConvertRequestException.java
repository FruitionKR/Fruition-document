package fruition.core.document.exception;

/** PDF → Markdown 변환 요청이 잘못된 경우(대상이 PDF 원본이 아니거나 원본 파일이 없음). */
public class InvalidDocumentConvertRequestException extends RuntimeException {
    public InvalidDocumentConvertRequestException(String message) {
        super(message);
    }
}
