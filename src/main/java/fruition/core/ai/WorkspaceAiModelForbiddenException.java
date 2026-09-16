package fruition.core.ai;

public class WorkspaceAiModelForbiddenException extends RuntimeException {
    public WorkspaceAiModelForbiddenException() {
        super("워크스페이스 소유자만 AI 모델 설정을 변경할 수 있습니다.");
    }
}
