package fruition.core.query.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import fruition.core.query.dto.QueryResponse;

import java.time.Instant;

public record QueryRun(
        String requestId,
        String workspaceId,
        String sessionId,
        String provider,
        String model,
        boolean webSearchEnabled,
        QueryRunStatus status,
        String question,
        QueryResponse result,
        String errorMessage,
        Instant createdAt,
        Instant completedAt) {

    public static QueryRun pending(String requestId, String workspaceId, String sessionId, String provider,
                                   String model, boolean webSearchEnabled, String question, Instant createdAt) {
        return new QueryRun(requestId, workspaceId, sessionId, provider, model, webSearchEnabled,
                QueryRunStatus.PENDING, question, null, null, createdAt, null);
    }

    public static QueryRun pending(String requestId, String workspaceId, String sessionId,
                                   String question, Instant createdAt) {
        return pending(requestId, workspaceId, sessionId, "openai", "gpt-5-nano", false, question, createdAt);
    }

    public QueryRun running() {
        return new QueryRun(requestId, workspaceId, sessionId, provider, model, webSearchEnabled,
                QueryRunStatus.RUNNING, question, result, errorMessage, createdAt, completedAt);
    }

    public QueryRun completed(QueryResponse result, Instant completedAt) {
        return new QueryRun(requestId, workspaceId, sessionId, provider, model, webSearchEnabled,
                QueryRunStatus.COMPLETED, question, result, null, createdAt, completedAt);
    }

    public QueryRun cancelled(Instant completedAt) {
        return new QueryRun(requestId, workspaceId, sessionId, provider, model, webSearchEnabled,
                QueryRunStatus.CANCELLED, question, null, null, createdAt, completedAt);
    }

    public QueryRun failed(String errorMessage, Instant completedAt) {
        return new QueryRun(requestId, workspaceId, sessionId, provider, model, webSearchEnabled,
                QueryRunStatus.FAILED, question, null, errorMessage, createdAt, completedAt);
    }

    // 파생 값이라 Redis 저장 JSON에 필드로 직렬화되지 않게 한다.
    @JsonIgnore
    public boolean isFinished() {
        return status == QueryRunStatus.CANCELLED || status == QueryRunStatus.COMPLETED || status == QueryRunStatus.FAILED;
    }
}
