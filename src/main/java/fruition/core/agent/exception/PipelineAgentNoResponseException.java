package fruition.core.agent.exception;

/**
 * ai-svc에 보낸 요청의 응답을 받지 못했다(시간 초과·연결 끊김). 요청이 처리됐는지 알 수 없다.
 * 사용자 응답은 {@link PipelineAgentException}과 같다(503).
 */
public class PipelineAgentNoResponseException extends PipelineAgentException {

    public PipelineAgentNoResponseException(String message) {
        super(message, 503, null);
    }
}
