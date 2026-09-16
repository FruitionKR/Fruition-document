package fruition.core.chat.exception;

public class ChatSessionLimitExceededException extends RuntimeException {
    public ChatSessionLimitExceededException(String workspaceId) {
        super("워크스페이스당 채팅 세션은 최대 10개까지 만들 수 있습니다: workspace_id=" + workspaceId);
    }
}
