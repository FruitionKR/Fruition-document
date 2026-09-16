package fruition.core.chat.exception;

public class ChatSessionNotFoundException extends RuntimeException {
    public ChatSessionNotFoundException(String sessionId) {
        super("채팅 세션을 찾을 수 없습니다: id=" + sessionId);
    }
}
