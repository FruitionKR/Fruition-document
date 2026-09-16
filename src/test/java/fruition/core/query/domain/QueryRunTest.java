package fruition.core.query.domain;

import fruition.core.query.dto.QueryResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRunTest {

    private static final Instant CREATED_AT = Instant.parse("2026-06-20T10:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-06-20T10:00:05Z");

    @Test
    void pending_createsRunWithPendingStatusAndNoResult() {
        QueryRun run = QueryRun.pending("query_abc123", "ws_abc123", "session_abc123", "질문", CREATED_AT);

        assertThat(run.requestId()).isEqualTo("query_abc123");
        assertThat(run.workspaceId()).isEqualTo("ws_abc123");
        assertThat(run.sessionId()).isEqualTo("session_abc123");
        assertThat(run.status()).isEqualTo(QueryRunStatus.PENDING);
        assertThat(run.question()).isEqualTo("질문");
        assertThat(run.result()).isNull();
        assertThat(run.createdAt()).isEqualTo(CREATED_AT);
        assertThat(run.isFinished()).isFalse();
    }

    @Test
    void running_returnsNewInstanceWithRunningStatus_originalUnchanged() {
        QueryRun pending = QueryRun.pending("query_abc123", "ws_abc123", "session_abc123", "질문", CREATED_AT);

        QueryRun running = pending.running();

        assertThat(running.status()).isEqualTo(QueryRunStatus.RUNNING);
        assertThat(running).isNotSameAs(pending);
        assertThat(pending.status()).isEqualTo(QueryRunStatus.PENDING);
    }

    @Test
    void completed_setsResultAndCompletedAt_clearsErrorMessage() {
        QueryRun running = QueryRun.pending("query_abc123", "ws_abc123", "session_abc123", "질문", CREATED_AT).running();
        QueryResponse result = new QueryResponse(null, null, null, null, null, null, false, false, 0, null);

        QueryRun completed = running.completed(result, COMPLETED_AT);

        assertThat(completed.status()).isEqualTo(QueryRunStatus.COMPLETED);
        assertThat(completed.result()).isSameAs(result);
        assertThat(completed.errorMessage()).isNull();
        assertThat(completed.completedAt()).isEqualTo(COMPLETED_AT);
        assertThat(completed.isFinished()).isTrue();
    }

    @Test
    void failed_setsErrorMessageAndCompletedAt_resultStaysNull() {
        QueryRun running = QueryRun.pending("query_abc123", "ws_abc123", "session_abc123", "질문", CREATED_AT).running();

        QueryRun failed = running.failed("파이프라인 오류", COMPLETED_AT);

        assertThat(failed.status()).isEqualTo(QueryRunStatus.FAILED);
        assertThat(failed.errorMessage()).isEqualTo("파이프라인 오류");
        assertThat(failed.result()).isNull();
        assertThat(failed.completedAt()).isEqualTo(COMPLETED_AT);
        assertThat(failed.isFinished()).isTrue();
    }
}
