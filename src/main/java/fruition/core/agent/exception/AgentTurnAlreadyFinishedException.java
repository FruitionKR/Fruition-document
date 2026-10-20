package fruition.core.agent.exception;

/** 결과가 이미 반영됐거나 실패로 끝난 Agent turn은 취소할 수 없다. */
public class AgentTurnAlreadyFinishedException extends RuntimeException {

    public AgentTurnAlreadyFinishedException(String runId) {
        super("이미 끝난 Agent turn은 취소할 수 없습니다: " + runId);
    }
}
