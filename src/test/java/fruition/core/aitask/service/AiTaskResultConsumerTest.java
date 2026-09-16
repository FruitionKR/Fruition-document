package fruition.core.aitask.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.query.dto.QueryResponse;
import fruition.core.query.service.QueryEventBroker;
import fruition.core.query.service.QueryRunStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class AiTaskResultConsumerTest {

    @Mock AiTaskResultApplier applier;
    @Mock QueryRunStore queryRunStore;
    @Mock QueryEventBroker queryEventBroker;

    private AiTaskResultConsumer consumer;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(applier.acceptsProgress(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        consumer = new AiTaskResultConsumer(
                new ObjectMapper(), applier, queryRunStore, queryEventBroker);
    }

    @Test
    void duplicateSuccessPublishesSseOnlyForFirstRedisTerminalTransition() throws Exception {
        QueryResponse response = org.mockito.Mockito.mock(QueryResponse.class);
        var projection = new AiTaskResultApplier.QueryProjection("query-1", response, null);
        when(applier.applyQuery(org.mockito.ArgumentMatchers.any())).thenReturn(projection);
        when(queryRunStore.markCompleted("query-1", response)).thenReturn(true, false);

        consumer.consume("{\"kind\":\"query\"}");
        consumer.consume("{\"kind\":\"query\"}");

        verify(queryEventBroker).complete("query-1");
    }

    @Test
    void canonicalSuccessThenLateFailureDoesNotPublishContradictorySse() throws Exception {
        QueryResponse response = org.mockito.Mockito.mock(QueryResponse.class);
        when(applier.applyQuery(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AiTaskResultApplier.QueryProjection("query-1", response, null),
                new AiTaskResultApplier.QueryProjection("query-1", null, "late failure"));
        when(queryRunStore.markCompleted("query-1", response)).thenReturn(true);
        when(queryRunStore.markFailed("query-1", "late failure")).thenReturn(false);

        consumer.consume("{\"kind\":\"query\"}");
        consumer.consume("{\"kind\":\"query\"}");

        verify(queryEventBroker).complete("query-1");
        verify(queryEventBroker, never()).fail("query-1", "late failure");
    }

    @Test
    void queryProgressPublishesSseLogWithoutApplyingTerminalResult() throws Exception {
        consumer.consume("""
                {
                  "event_id":"query:query-1:progress:1:wiki_loaded",
                  "kind":"query",
                  "run_id":"query-1",
                  "status":"progress",
                  "payload":{
                    "stage":"wiki_loaded",
                    "message":"Wiki 데이터를 불러왔습니다.",
                    "data":{"page_count":3}
                  }
                }
                """);

        verify(queryEventBroker).publish(
                "query-1",
                "query:query-1:progress:1:wiki_loaded",
                "wiki_loaded",
                "Wiki 데이터를 불러왔습니다.",
                java.util.Map.of("page_count", 3));
        verify(applier).acceptsProgress(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verifyNoMoreInteractions(applier);
        verifyNoInteractions(queryRunStore);
    }

    /**
     * error handler가 무한 재시도라, 진행 이벤트 중계 실패를 올리면 그 파티션의 최종 결과까지 막힌다.
     * 진행 이벤트는 유실을 허용하고 삼킨다.
     */
    @Test
    void queryProgressRelayFailureDoesNotStopTheConsumer() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("질의 진행 이벤트 ID는 필수입니다."))
                .when(queryEventBroker).publish(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        consumer.consume("""
                {"kind":"query","run_id":"query-1","status":"progress",
                 "payload":{"stage":"wiki_loaded","message":"불러왔습니다.","data":{}}}
                """);

        verify(applier).acceptsProgress(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verifyNoMoreInteractions(applier);
        verifyNoInteractions(queryRunStore);
    }

    /**
     * Agent turn도 AI가 chat_answer로 판정하면 질의와 같은 단계를 낸다. kind 분기가 먼저 걸리면
     * 이 이벤트가 applyAgent로 들어가 최종 결과로 오인되고 run이 깨진다.
     */
    @Test
    void agentProgressIsRelayedInsteadOfAppliedAsResult() throws Exception {
        consumer.consume("""
                {
                  "event_id":"query:agent-1:progress:1:wiki_loaded",
                  "kind":"agent",
                  "run_id":"agent-1",
                  "status":"progress",
                  "payload":{"stage":"wiki_loaded","message":"Wiki 데이터를 불러왔습니다.","data":{}}
                }
                """);

        verify(queryEventBroker).publish(
                "agent-1",
                "query:agent-1:progress:1:wiki_loaded",
                "wiki_loaded",
                "Wiki 데이터를 불러왔습니다.",
                java.util.Map.of());
        verify(applier).acceptsProgress(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verifyNoMoreInteractions(applier);
        verifyNoInteractions(queryRunStore);
    }

    /**
     * Agent turn을 구독한 화면도 끝을 알아야 한다. 종료 이벤트가 없으면 emitter 타임아웃까지 매달린다.
     */
    @Test
    void agentTerminalEventCompletesTheSseStream() throws Exception {
        when(applier.applyAgent(any())).thenReturn(new AiTaskResultApplier.AgentApplyResult(true, null));

        consumer.consume("""
                {"event_id":"agent:agent-1:succeeded","kind":"agent","run_id":"agent-1",
                 "status":"succeeded","request":{},"payload":{"action":"chat_answer"}}
                """);

        verify(queryEventBroker).complete("agent-1");
    }

    @Test
    void agentFailureEventFailsTheSseStream() throws Exception {
        when(applier.applyAgent(any())).thenReturn(
                new AiTaskResultApplier.AgentApplyResult(true, "모델 호출 실패"));

        consumer.consume("""
                {"event_id":"agent:agent-1:failed","kind":"agent","run_id":"agent-1",
                 "status":"failed","error":"모델 호출 실패"}
                """);

        verify(queryEventBroker).fail("agent-1", "모델 호출 실패");
    }

    @Test
    void internallyRejectedAgentSuccessFailsTheSseStream() throws Exception {
        when(applier.applyAgent(any())).thenReturn(
                new AiTaskResultApplier.AgentApplyResult(true, "agent_result_invalid_payload"));

        consumer.consume("""
                {"event_id":"agent:agent-1:succeeded","kind":"agent","run_id":"agent-1",
                 "status":"succeeded","request":{},"payload":null}
                """);

        // 내부 코드가 아니라 사용자에게 보일 문구가 나가야 한다.
        verify(queryEventBroker).fail("agent-1", "Agent 결과 형식이 올바르지 않습니다.");
        verify(queryEventBroker, never()).complete(anyString());
    }

    /** 재전송이면 이미 끝난 스트림이다. 다시 발행하면 늦게 구독한 화면이 완료를 두 번 본다. */
    @Test
    void replayedAgentTerminalEventDoesNotPublishSseAgain() throws Exception {
        when(applier.applyAgent(any())).thenReturn(new AiTaskResultApplier.AgentApplyResult(false, null));

        consumer.consume("""
                {"event_id":"agent:agent-1:succeeded","kind":"agent","run_id":"agent-1",
                 "status":"succeeded","request":{},"payload":{"action":"chat_answer"}}
                """);

        verify(queryEventBroker, never()).complete(anyString());
        verify(queryEventBroker, never()).fail(anyString(), anyString());
    }

    @Test
    void consumeSetsFlowIdDuringProcessingAndClearsItAfterward() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(MDC.get("flowId")).isEqualTo("run-1");
            return null;
        }).when(applier).applyIngest(org.mockito.ArgumentMatchers.any());

        consumer.consume("{\"kind\":\"ingest\",\"run_id\":\"run-1\"}");

        assertThat(MDC.get("flowId")).isNull();
    }
}
