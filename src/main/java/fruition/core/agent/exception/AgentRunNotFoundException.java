package fruition.core.agent.exception;

public class AgentRunNotFoundException extends RuntimeException {

    public AgentRunNotFoundException(String runId) {
        super("Agent run을 찾을 수 없습니다: " + runId);
    }
}
