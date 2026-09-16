package fruition.core.chat.exception;

public class EmptyChatWikiExportException extends RuntimeException {
    public EmptyChatWikiExportException(String sessionId) {
        super("위키화할 완료된 메시지가 없습니다: session_id=" + sessionId);
    }
}
