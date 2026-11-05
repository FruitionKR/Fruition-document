package fruition.core.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.agent.dto.AgentRunApproveRequest;
import fruition.core.agent.exception.PipelineAgentException;
import fruition.core.agent.repository.AgentRunCommandRepository;
import fruition.core.agent.repository.PipelineAgentRunStatusRequester;
import fruition.core.aihistory.service.AgentApplyOperationStore;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.document.service.DocumentEditLockService;
import fruition.core.document.service.DocumentService;
import fruition.core.usage.service.CreditService;
import fruition.shared.ai.AiModelCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 승인 대기 계획을 사용자의 자동 승인 설정에 따라 사용자 승인과 같은 경로로 승인하는지 본다. */
@ExtendWith(MockitoExtension.class)
class AgentTurnServiceAutoApprovalTest {

    private static final String RUN_ID = "0b1f6c1e-5b0e-4b53-9a67-3f2f0d8e9a11";
    private static final String HASH = "h".repeat(64);

    @Mock DocumentService documentService;
    @Mock DocumentEditLockService editLockService;
    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock AgentRunCommandRepository runRepository;
    @Mock PipelineAgentRunStatusRequester statusRequester;
    @Mock AiCommandOutboxWriter outboxWriter;
    @Mock AgentApplyOperationStore applyOperationStore;
    @Mock fruition.core.chat.service.ChatSessionService chatSessionService;
    @Mock fruition.core.chat.service.ChatConversationReader chatConversationReader;
    @Mock fruition.core.chat.service.ChatTurnRecorder chatTurnRecorder;
    @Mock fruition.core.aitask.service.AiTaskCancellationService cancellation;
    @Mock fruition.core.usage.service.UsageChargeService usageCharges;
    @Mock fruition.core.document.repository.FolderRepository folderRepository;
    @Mock AgentAutoApprovalService autoApproval;

    private AgentTurnService service;
    private JsonNode awaiting;
    private JsonNode executing;

    @BeforeEach
    void setUp() throws Exception {
        service = new AgentTurnService(documentService, editLockService, workspaceAccessGuard, runRepository,
                statusRequester, outboxWriter, applyOperationStore, new AiModelCatalog("openai,gemini,claude"),
                chatSessionService, chatConversationReader, chatTurnRecorder, "ai.agent.command", cancellation,
                new ObjectMapper(), usageCharges, folderRepository,
                new org.springframework.transaction.support.TransactionTemplate(
                        org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class)));
        service.setAutoApproval(autoApproval);
        ObjectMapper mapper = new ObjectMapper();
        awaiting = mapper.readTree("""
                {"id":"%s","status":"awaiting_approval","plan":{"version":2,"operation_hash":"%s",
                 "status":"awaiting_approval","operations":[{"id":"op","tool_name":"move_document"}]}}
                """.formatted(RUN_ID, HASH));
        executing = mapper.readTree("""
                {"id":"%s","status":"executing","plan":{"version":2,"operation_hash":"%s","status":"approved",
                 "approved_by":"auto:user-setting","operations":[{"id":"op","tool_name":"move_document"}]}}
                """.formatted(RUN_ID, HASH));
    }

    @Test
    void manualDecisionLeavesPlanAwaitingApproval() {
        when(statusRequester.getAutonomousRun("ws_1", "user_1", RUN_ID)).thenReturn(awaiting);
        when(autoApproval.decide("ws_1", "user_1", awaiting))
                .thenReturn(new AgentAutoApprovalPolicy.Decision(false, "disabled", 0, null));

        assertThat(service.getRun("ws_1", "user_1", RUN_ID)).isEqualTo(awaiting);

        verify(statusRequester, never()).approveAutomatically(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString());
        verify(usageCharges, never()).reserveAutonomousRun(anyString(), anyString(), anyString());
    }

    @Test
    void allowedPlanIsApprovedLikeAUserApprovalAndRecordedAsAuto() {
        when(statusRequester.getAutonomousRun("ws_1", "user_1", RUN_ID)).thenReturn(awaiting, awaiting, executing);
        when(autoApproval.decide("ws_1", "user_1", awaiting))
                .thenReturn(new AgentAutoApprovalPolicy.Decision(true, "approved", 2, HASH));
        when(statusRequester.approveAutomatically("ws_1", "user_1", RUN_ID, 2, HASH, "auto:user-setting"))
                .thenReturn(executing);

        JsonNode result = service.getRun("ws_1", "user_1", RUN_ID);

        assertThat(result.path("plan").path("approved_by").asText()).isEqualTo("auto:user-setting");
        var order = inOrder(usageCharges, statusRequester);
        order.verify(usageCharges).reserveAutonomousRun(RUN_ID, "ws_1", "user_1");
        order.verify(statusRequester).approveAutomatically("ws_1", "user_1", RUN_ID, 2, HASH, "auto:user-setting");
    }

    @Test
    void insufficientCreditKeepsManualApprovalWithoutForwarding() {
        when(statusRequester.getAutonomousRun("ws_1", "user_1", RUN_ID)).thenReturn(awaiting);
        when(autoApproval.decide("ws_1", "user_1", awaiting))
                .thenReturn(new AgentAutoApprovalPolicy.Decision(true, "approved", 2, HASH));
        doThrow(new CreditService.InsufficientCreditException("부족"))
                .when(usageCharges).reserveAutonomousRun(RUN_ID, "ws_1", "user_1");

        assertThat(service.getRun("ws_1", "user_1", RUN_ID)).isEqualTo(awaiting);

        verify(statusRequester, never()).approveAutomatically(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString());
    }

    @Test
    void concurrentApprovalOrChangedPlanFallsBackToCurrentRun() {
        when(statusRequester.getAutonomousRun("ws_1", "user_1", RUN_ID)).thenReturn(awaiting, awaiting, executing);
        when(autoApproval.decide("ws_1", "user_1", awaiting))
                .thenReturn(new AgentAutoApprovalPolicy.Decision(true, "approved", 2, HASH));
        when(statusRequester.approveAutomatically("ws_1", "user_1", RUN_ID, 2, HASH, "auto:user-setting"))
                .thenThrow(new PipelineAgentException("AgentRun 요청이 거부되었습니다.", 409, null));

        assertThat(service.getRun("ws_1", "user_1", RUN_ID)).isEqualTo(executing);

        // 동시에 들어온 다른 승인이 이미 실행 중일 수 있으므로 run 단위 예약은 풀지 않는다.
        verify(usageCharges, never()).abortAutonomousAttempt(anyString(), anyString());
    }

    @Test
    void runsThatAreNotAwaitingApprovalAreNotEvaluated() {
        when(statusRequester.getAutonomousRun("ws_1", "user_1", RUN_ID)).thenReturn(executing);

        assertThat(service.getRun("ws_1", "user_1", RUN_ID)).isEqualTo(executing);

        verify(autoApproval, never()).decide(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void planReadyNotificationRequiresMembershipAndUsesTheSameDecision() {
        when(statusRequester.getAutonomousRun("ws_1", "user_1", RUN_ID)).thenReturn(awaiting);
        when(autoApproval.decide("ws_1", "user_1", awaiting))
                .thenReturn(new AgentAutoApprovalPolicy.Decision(false, "forbidden", 0, null));

        assertThat(service.autoApprove("ws_1", "user_1", RUN_ID)).isEqualTo(awaiting);

        verify(workspaceAccessGuard).requireMember("ws_1", "user_1");
    }

    /** 승인 대기로 오래 남아도 계획 단계 사용량이 보이게 plan-ready에서 수집한다. 자동 승인 여부와 무관하다. */
    @Test
    void planReadyCollectsPlanningCallsEvenWhenPlanStaysAwaitingApproval() {
        when(statusRequester.getAutonomousRun("ws_1", "user_1", RUN_ID)).thenReturn(awaiting);
        when(autoApproval.decide("ws_1", "user_1", awaiting))
                .thenReturn(new AgentAutoApprovalPolicy.Decision(false, "disabled", 0, null));

        service.autoApprove("ws_1", "user_1", RUN_ID);

        verify(usageCharges).collectAgentPlan(RUN_ID);
    }

    /** 화면 폴링은 승인 대기 중 반복되므로 수집 대기열에 넣지 않는다. */
    @Test
    void pollingAwaitingPlanDoesNotQueueCollection() {
        when(statusRequester.getAutonomousRun("ws_1", "user_1", RUN_ID)).thenReturn(awaiting);
        when(autoApproval.decide("ws_1", "user_1", awaiting))
                .thenReturn(new AgentAutoApprovalPolicy.Decision(false, "disabled", 0, null));

        service.getRun("ws_1", "user_1", RUN_ID);

        verify(usageCharges, never()).collectAgentPlan(anyString());
    }

    /** 사용자가 직접 승인하면 승인 주체를 싣지 않는다(ai-svc 기본값 user). */
    @Test
    void manualApprovalDoesNotClaimAutoApproval() {
        when(statusRequester.getAutonomousRun("ws_1", "user_1", RUN_ID)).thenReturn(awaiting);
        when(statusRequester.approve("ws_1", "user_1", RUN_ID, 2, HASH)).thenReturn(executing);

        service.approve("ws_1", "user_1", RUN_ID, new AgentRunApproveRequest(2, HASH));

        verify(statusRequester).approve("ws_1", "user_1", RUN_ID, 2, HASH);
        verify(statusRequester, never()).approveAutomatically(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString());
    }
}
