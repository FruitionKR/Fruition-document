package fruition.core.document.dto;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * 내보내기 응답 본문. ZIP은 최대 100MB까지 커질 수 있어 bytes 대신 stream으로 전달한다.
 * {@code content}는 응답을 쓴 뒤 닫히며, 임시 파일 기반 stream은 닫힐 때 파일도 정리된다.
 */
public record DocumentExportResult(
        String filename,
        String contentType,
        long contentLength,
        InputStream content
) {
    public static DocumentExportResult markdown(String filename, byte[] bytes) {
        return new DocumentExportResult(
                filename, "text/markdown;charset=UTF-8", bytes.length, new ByteArrayInputStream(bytes));
    }

    public static DocumentExportResult zip(String filename, long contentLength, InputStream content) {
        return new DocumentExportResult(filename, "application/zip", contentLength, content);
    }
}
