package fruition.core.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.agent.dto.AgentRunApproveRequest;
import fruition.core.agent.dto.AgentRunReviseRequest;
import fruition.core.agent.dto.AgentTurnRequest;
import fruition.core.agent.exception.AgentRunNotFoundException;
import fruition.core.agent.exception.InvalidAgentTurnRequestException;
import fruition.core.agent.repository.AgentRunCommandRepository;
import fruition.core.aihistory.service.AgentApplyOperationStore;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.dto.DocumentDetailResponse;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.agent.repository.PipelineAgentRunStatusRequester;
import fruition.core.document.service.DocumentEditLockService;
import fruition.core.document.service.DocumentService;
import fruition.shared.ai.AiModelCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTurnServiceTest {

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
    private final AiModelCatalog aiModelCatalog = new AiModelCatalog("openai,gemini,claude");

    private AgentTurnService service;

    @Mock fruition.core.aitask.service.AiTaskCancellationService cancellation;

    @BeforeEach
    void setUp() {
        // 대화 조립은 ChatConversationReaderTest 가 검증한다. 여기서는 빈 맥락으로 둔다.
        org.mockito.Mockito.lenient()
                .when(chatConversationReader.read(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new fruition.core.chat.service.ChatConversationReader.Conversation(
                        java.util.List.of(), null));
        service = new AgentTurnService(documentService, editLockService, workspaceAccessGuard, runRepository,
                statusRequester, outboxWriter, applyOperationStore, aiModelCatalog,
                chatSessionService, chatConversationReader, chatTurnRecorder, "ai.agent.command", cancellation, new ObjectMapper());
    }

    @Test
    void turn_validRequestQueuesDurableRun() throws Exception {
        AgentTurnRequest request = request("whole_document", 1, 2,
                new AgentTurnRequest.ConversationContext(
                        java.util.List.of("pair_1"),
                        Map.of("source", "selected"),
                        new AgentTurnRequest.ConversationContext.PendingSkillProposal(
                                "personal", "meeting-notes", "회의록을 작성합니다.", "# 정확한 원문 지침",
                                java.util.List.of("document-create"),
                                java.util.List.of("list_root_items", "list_folder_children", "create_document"))));
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 7));
        when(applyOperationStore.newOperationId()).thenReturn("op_apply_1");

        var response = service.turn("ws_1", "user_1", request);

        assertThat(response.status()).isEqualTo("queued");
        assertThat(response.applyOperationId()).isEqualTo("op_apply_1");
        verify(runRepository).create(anyString(), org.mockito.ArgumentMatchers.eq("ws_1"),
                org.mockito.ArgumentMatchers.eq("user_1"), org.mockito.ArgumentMatchers.eq("doc_1"),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("op_apply_1"));
        ArgumentCaptor<AgentTurnService.AgentCommand> command =
                ArgumentCaptor.forClass(AgentTurnService.AgentCommand.class);
        verify(outboxWriter).enqueue(anyString(), org.mockito.ArgumentMatchers.eq("ai.agent.command"),
                org.mockito.ArgumentMatchers.eq("session_1"), command.capture());
        assertThat(command.getValue().provider()).isEqualTo("openai");
        assertThat(command.getValue().model()).isEqualTo("gpt-5-nano");
        assertThat(command.getValue().skillMode()).isEqualTo("auto");
        assertThat(command.getValue().skillId()).isNull();
        assertThat(command.getValue().conversationContext().pendingSkillProposal().scopeType()).isEqualTo("personal");
        assertThat(command.getValue().conversationContext().pendingSkillProposal().name()).isEqualTo("meeting-notes");
        assertThat(command.getValue().conversationContext().pendingSkillProposal().description())
                .isEqualTo("회의록을 작성합니다.");
        assertThat(command.getValue().conversationContext().pendingSkillProposal().instructionsMarkdown())
                .isEqualTo("# 정확한 원문 지침");
        assertThat(command.getValue().conversationContext().pendingSkillProposal().capabilities())
                .containsExactly("document-create");
        assertThat(command.getValue().conversationContext().pendingSkillProposal().allowedTools())
                .containsExactly("list_root_items", "list_folder_children", "create_document");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command.getValue()))
                .contains("\"pending_skill_proposal\":{\"scope_type\":\"personal\"")
                .contains("\"instructions_markdown\":\"# 정확한 원문 지침\"");
    }

    @Test
    void turn_withoutDocumentSkipsEditPreconditionsAndApplyTable() {
        // 문서를 열지 않은 턴은 적용할 대상이 없다. 문서 조회·편집 잠금·버전 검사를 하지 않고,
        // 되돌려받을 표(apply_operation_id)도 만들지 않는다.
        var request = new AgentTurnRequest("session_1", null, null, "RAG가 뭐야?", "openai", "gpt-5-nano", null, null);

        var response = service.turn("ws_1", "user_1", request);

        assertThat(response.status()).isEqualTo("queued");
        assertThat(response.documentId()).isNull();
        assertThat(response.baseVersion()).isNull();
        assertThat(response.applyOperationId()).isNull();
        verify(workspaceAccessGuard).requireMember("ws_1", "user_1");
        verifyNoInteractions(documentService, editLockService, applyOperationStore);
        verify(runRepository).create(anyString(), org.mockito.ArgumentMatchers.eq("ws_1"),
                org.mockito.ArgumentMatchers.eq("user_1"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void turn_buildsConversationContextOnServerFromSelectedPairs() {
        // 대화 내용은 클라이언트가 만든 문자열이 아니라 서버가 세션에서 읽은 값이어야 한다.
        org.mockito.Mockito.when(chatConversationReader.read("session_1", java.util.List.of("pair_9")))
                .thenReturn(new fruition.core.chat.service.ChatConversationReader.Conversation(
                        java.util.List.of(new fruition.core.chat.service.ChatConversationReader.Message(
                                "assistant", "날씨를 알려주세요.", "conversation_reply", "agent_previous")),
                        "사용자: 이전 질문"));
        var route = new ObjectMapper().createObjectNode()
                .put("action", "markdown_create")
                .put("retrieval_source", "workspace");
        var result = new ObjectMapper().createObjectNode().set("route", route);
        when(runRepository.find("ws_1", "user_1", "agent_previous")).thenReturn(Optional.of(
                new AgentRunCommandRepository.RunView(
                        "agent_previous", null, 0L, null, "ready", result, null)));
        var request = new AgentTurnRequest("session_1", null, null, "이어서 해줘", "openai", "gpt-5-nano",
                null, "auto", null,
                new AgentTurnRequest.ConversationContext(java.util.List.of("pair_9"), null, null),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), null, null);

        service.turn("ws_1", "user_1", request);

        ArgumentCaptor<AgentTurnService.AgentCommand> command =
                ArgumentCaptor.forClass(AgentTurnService.AgentCommand.class);
        verify(outboxWriter).enqueue(anyString(), anyString(), anyString(), command.capture());
        var context = command.getValue().conversationContext();
        assertThat(context.recentConversationSummary()).isEqualTo("사용자: 이전 질문");
        assertThat(context.recentMessages()).extracting(AgentTurnService.CommandConversationMessage::content)
                .containsExactly("날씨를 알려주세요.");
        assertThat(context.recentMessages()).extracting(AgentTurnService.CommandConversationMessage::action)
                .containsExactly("conversation_reply");
        assertThat(context.recentMessages()).extracting(AgentTurnService.CommandConversationMessage::runId)
                .containsExactly("agent_previous");
        assertThat(context.recentMessages()).extracting(AgentTurnService.CommandConversationMessage::agentRoute)
                .containsExactly(route);
    }

    @Test
    void turn_omitsAgentRouteWhenCanonicalProjectionIsUnavailableOrInvalid() throws Exception {
        var objectMapper = new ObjectMapper();
        var messages = java.util.List.of(
                new fruition.core.chat.service.ChatConversationReader.Message(
                        "assistant", "없음", "markdown_create", "agent_missing"),
                new fruition.core.chat.service.ChatConversationReader.Message(
                        "assistant", "진행 중", "markdown_create", "agent_queued"),
                new fruition.core.chat.service.ChatConversationReader.Message(
                        "assistant", "route 없음", "markdown_edit", "agent_no_route"),
                new fruition.core.chat.service.ChatConversationReader.Message(
                        "assistant", "잘못된 route", "markdown_edit", "agent_invalid_route"));
        when(chatConversationReader.read("session_1", java.util.List.of()))
                .thenReturn(new fruition.core.chat.service.ChatConversationReader.Conversation(messages, null));
        when(runRepository.find("ws_1", "user_1", "agent_missing")).thenReturn(Optional.empty());
        when(runRepository.find("ws_1", "user_1", "agent_queued")).thenReturn(Optional.of(
                new AgentRunCommandRepository.RunView(
                        "agent_queued", null, 0L, null, "queued", null, null)));
        when(runRepository.find("ws_1", "user_1", "agent_no_route")).thenReturn(Optional.of(
                new AgentRunCommandRepository.RunView(
                        "agent_no_route", null, 0L, null, "ready",
                        objectMapper.createObjectNode().put("action", "markdown_edit"), null)));
        when(runRepository.find("ws_1", "user_1", "agent_invalid_route")).thenReturn(Optional.of(
                new AgentRunCommandRepository.RunView(
                        "agent_invalid_route", null, 0L, null, "consumed",
                        objectMapper.createObjectNode().put("route", "markdown_edit"), null)));

        service.turn("ws_1", "user_1",
                new AgentTurnRequest("session_1", null, null, "이어서 해줘", "openai", "gpt-5-nano", null, null));

        ArgumentCaptor<AgentTurnService.AgentCommand> command =
                ArgumentCaptor.forClass(AgentTurnService.AgentCommand.class);
        verify(outboxWriter).enqueue(anyString(), anyString(), anyString(), command.capture());
        assertThat(command.getValue().conversationContext().recentMessages())
                .extracting(AgentTurnService.CommandConversationMessage::agentRoute)
                .containsOnlyNulls();
        assertThat(objectMapper.writeValueAsString(command.getValue()))
                .contains("\"run_id\":\"agent_missing\"")
                .contains("\"agent_route\":null");
    }

    @Test
    void turn_carriesWebSearchFlagIntoCommand() {
        // 질의 엔드포인트가 받던 옵션이다. 한 입력창으로 합치면 이 경로로만 들어오므로 끊기면 안 된다.
        var request = new AgentTurnRequest("session_1", null, null, "최신 소식 알려줘", "openai", "gpt-5-nano",
                true, "auto", null, null, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                null, null);

        service.turn("ws_1", "user_1", request);

        ArgumentCaptor<AgentTurnService.AgentCommand> command =
                ArgumentCaptor.forClass(AgentTurnService.AgentCommand.class);
        verify(outboxWriter).enqueue(anyString(), anyString(), anyString(), command.capture());
        assertThat(command.getValue().allowWebSearch()).isTrue();
    }

    @Test
    void turn_omitsWebSearchFlagWhenNotRequested() {
        var request = new AgentTurnRequest("session_1", null, null, "RAG가 뭐야?", "openai", "gpt-5-nano", null, null);

        service.turn("ws_1", "user_1", request);

        ArgumentCaptor<AgentTurnService.AgentCommand> command =
                ArgumentCaptor.forClass(AgentTurnService.AgentCommand.class);
        verify(outboxWriter).enqueue(anyString(), anyString(), anyString(), command.capture());
        assertThat(command.getValue().allowWebSearch()).isNull();
    }

    @Test
    void turn_savesPendingChatPairAndCarriesMessageContextInCommand() {
        // 결과가 왔을 때 어느 말풍선을 채울지 알아야 하므로 ID를 command에 실어 되받는다.
        var request = new AgentTurnRequest("session_1", null, null, "RAG가 뭐야?", "openai", "gpt-5-nano", null, null);

        var response = service.turn("ws_1", "user_1", request);

        verify(chatSessionService).verifyOwnedSession("ws_1", "user_1", "session_1");
        ArgumentCaptor<AgentTurnService.AgentCommand> command =
                ArgumentCaptor.forClass(AgentTurnService.AgentCommand.class);
        verify(outboxWriter).enqueue(anyString(), anyString(), anyString(), command.capture());
        var context = command.getValue().messageContext();
        assertThat(command.getValue().sessionId()).isEqualTo("session_1");
        assertThat(context.assistantMessageId()).startsWith("chat_assistant_");
        verify(chatTurnRecorder).createPendingAgentPair(
                org.mockito.ArgumentMatchers.eq("session_1"),
                org.mockito.ArgumentMatchers.eq(context.pairId()),
                org.mockito.ArgumentMatchers.eq(context.userMessageId()),
                org.mockito.ArgumentMatchers.eq(context.assistantMessageId()),
                org.mockito.ArgumentMatchers.eq("RAG가 뭐야?"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("openai"),
                org.mockito.ArgumentMatchers.eq("gpt-5-nano"),
                org.mockito.ArgumentMatchers.eq(response.requestId()));
    }

    @Test
    void turn_withoutDocumentStillKeysOutboxBySession() {
        var request = new AgentTurnRequest("session_1", null, null, "RAG가 뭐야?", "openai", "gpt-5-nano", null, null);

        service.turn("ws_1", "user_1", request);

        // 문서 유무와 무관하게 세션이 key다. 한 세션의 턴은 보낸 순서대로 처리돼야 한다.
        ArgumentCaptor<AgentTurnService.AgentCommand> command =
                ArgumentCaptor.forClass(AgentTurnService.AgentCommand.class);
        verify(outboxWriter).enqueue(anyString(), org.mockito.ArgumentMatchers.eq("ai.agent.command"),
                org.mockito.ArgumentMatchers.eq("session_1"), command.capture());
        assertThat(command.getValue().documentId()).isNull();
        assertThat(command.getValue().baseVersion()).isNull();
        assertThat(command.getValue().editorSnapshot()).isNull();
    }

    @Test
    void request_rejectsPartialDocumentContext() {
        // 셋 중 하나만 오면 적용 경로가 반쯤 성립해 뒤에서 터진다. 생성 시점에 막는다.
        assertThatThrownBy(() -> new AgentTurnRequest("session_1", "doc_1", null, "수정해줘", "openai", "gpt-5-nano", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provided together or omitted together");
    }

    @Test
    void turn_explicitSkillSelectionPropagatesToCommandJson() throws Exception {
        AgentTurnRequest request = new ObjectMapper().readValue("""
                {
                  "session_id":"session_1","documentId":"doc_1","baseVersion":7,"message":"문서를 점검해줘",
                  "provider":"openai","model":"gpt-5-nano",
                  "skill_mode":"explicit","skill_id":"skill-1",
                  "editorSnapshot":{"markdown":"# 제목\\n본문","target":{"type":"whole_document","startLine":1,"endLine":2}}
                }
                """, AgentTurnRequest.class);
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 7));
        when(applyOperationStore.newOperationId()).thenReturn("op_apply_1");

        service.turn("ws_1", "user_1", request);

        ArgumentCaptor<AgentTurnService.AgentCommand> command = ArgumentCaptor.forClass(AgentTurnService.AgentCommand.class);
        verify(outboxWriter).enqueue(anyString(), org.mockito.ArgumentMatchers.eq("ai.agent.command"),
                org.mockito.ArgumentMatchers.eq("session_1"), command.capture());
        assertThat(command.getValue().skillMode()).isEqualTo("explicit");
        assertThat(command.getValue().skillId()).isEqualTo("skill-1");
        assertThat(new ObjectMapper().writeValueAsString(command.getValue()))
                .contains("\"skill_mode\":\"explicit\"")
                .contains("\"skill_id\":\"skill-1\"");
    }

    @Test
    void turn_offSkillSelectionPropagatesNullSkillId() throws Exception {
        AgentTurnRequest request = new ObjectMapper().readValue("""
                {
                  "session_id":"session_1","documentId":"doc_1","baseVersion":7,"message":"문서를 점검해줘",
                  "provider":"openai","model":"gpt-5-nano",
                  "skill_mode":"off",
                  "editorSnapshot":{"markdown":"# 제목\\n본문","target":{"type":"whole_document","startLine":1,"endLine":2}}
                }
                """, AgentTurnRequest.class);
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 7));
        when(applyOperationStore.newOperationId()).thenReturn("op_apply_1");

        service.turn("ws_1", "user_1", request);

        ArgumentCaptor<AgentTurnService.AgentCommand> command = ArgumentCaptor.forClass(AgentTurnService.AgentCommand.class);
        verify(outboxWriter).enqueue(anyString(), org.mockito.ArgumentMatchers.eq("ai.agent.command"),
                org.mockito.ArgumentMatchers.eq("session_1"), command.capture());
        assertThat(command.getValue().skillMode()).isEqualTo("off");
        assertThat(command.getValue().skillId()).isNull();
    }

    @Test
    void turn_ignoresClientSourceDetailsAndBuildsScopedCompletedAutonomousRun() throws Exception {
        String sourceRunId = "agent_0123456789abcdef0123456789abcdef";
        AgentTurnRequest request = new ObjectMapper().readValue("""
                {
                  "session_id":"session_1","documentId":"doc_1","baseVersion":7,"message":"이 작업을 Skill로 만들어줘",
                  "provider":"openai","model":"gpt-5-nano",
                  "skill_draft_sources":[{"run_id":"%s","status":"completed",
                    "request_summary":"조작된 요청","plan_summary":"조작된 계획",
                    "successful_operations":[{"tool_name":"delete_document","reason":"조작된 이유"}]}],
                  "skill_draft_user_directives":["일반화해줘"],
                  "skill_draft_excluded_literals":["secret-doc"],"skill_scope_type":"team",
                  "editorSnapshot":{"markdown":"# 제목\\n본문",
                    "target":{"type":"whole_document","startLine":1,"endLine":2}}
                }
                """.formatted(sourceRunId), AgentTurnRequest.class);
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 7));
        when(statusRequester.findAutonomous("ws_1", "user_1", sourceRunId)).thenReturn(Optional.of(
                autonomousRun(sourceRunId, "completed", "정식 요청", "정식 계획", List.of(
                        new PipelineAgentRunStatusRequester.Operation(
                                "move_document", "정식 이유", "succeeded"),
                        new PipelineAgentRunStatusRequester.Operation(
                                "rename_folder", "실패한 이유", "failed")))));
        when(applyOperationStore.newOperationId()).thenReturn("op_apply_1");

        service.turn("ws_1", "user_1", request);

        ArgumentCaptor<AgentTurnService.AgentCommand> command = ArgumentCaptor.forClass(AgentTurnService.AgentCommand.class);
        verify(outboxWriter).enqueue(anyString(), org.mockito.ArgumentMatchers.eq("ai.agent.command"),
                org.mockito.ArgumentMatchers.eq("session_1"), command.capture());
        var source = command.getValue().skillDraftSources().getFirst();
        assertThat(source.status()).isEqualTo("completed");
        assertThat(source.requestSummary()).isEqualTo("정식 요청");
        assertThat(source.planSummary()).isEqualTo("정식 계획");
        assertThat(source.successfulOperations().getFirst().toolName()).isEqualTo("move_document");
        assertThat(source.successfulOperations().getFirst().reason()).isEqualTo("정식 이유");
        assertThat(command.getValue().skillScopeType()).isEqualTo("team");
    }

    @Test
    void turn_rejectsMissingOrForeignSkillDraftSource() {
        String sourceRunId = "agent_0123456789abcdef0123456789abcdef";
        AgentTurnRequest request = skillDraftRequest(sourceRunId);
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 7));
        when(statusRequester.findAutonomous("ws_1", "user_1", sourceRunId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.turn("ws_1", "user_1", request))
                .isInstanceOf(InvalidAgentTurnRequestException.class);
        verify(outboxWriter, never()).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void turn_rejectsNonCompletedSkillDraftSource() {
        String sourceRunId = "agent_0123456789abcdef0123456789abcdef";
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 7));
        when(statusRequester.findAutonomous("ws_1", "user_1", sourceRunId)).thenReturn(Optional.of(
                autonomousRun(sourceRunId, "executing", "정식 요청", "정식 계획", List.of())));

        assertThatThrownBy(() -> service.turn("ws_1", "user_1", skillDraftRequest(sourceRunId)))
                .isInstanceOf(InvalidAgentTurnRequestException.class);
        verify(outboxWriter, never()).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void turn_rejectsCompletedNonAutonomousSkillDraftSource() {
        String sourceRunId = "agent_0123456789abcdef0123456789abcdef";
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 7));
        when(statusRequester.findAutonomous("ws_1", "user_1", sourceRunId)).thenReturn(Optional.of(
                new PipelineAgentRunStatusRequester.AutonomousRun(
                        sourceRunId, "markdown_turn", "completed", "정식 요청",
                        new PipelineAgentRunStatusRequester.Plan("정식 계획", List.of()))));

        assertThatThrownBy(() -> service.turn("ws_1", "user_1", skillDraftRequest(sourceRunId)))
                .isInstanceOf(InvalidAgentTurnRequestException.class);
        verify(outboxWriter, never()).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void turn_rejectsIncompleteCompletedSkillDraftSource() {
        String sourceRunId = "agent_0123456789abcdef0123456789abcdef";
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 7));
        when(statusRequester.findAutonomous("ws_1", "user_1", sourceRunId)).thenReturn(Optional.of(
                autonomousRun(sourceRunId, "completed", "정식 요청", "정식 계획", List.of(
                        new PipelineAgentRunStatusRequester.Operation(
                                "move_document", "실패한 operation", "failed")))));

        assertThatThrownBy(() -> service.turn("ws_1", "user_1", skillDraftRequest(sourceRunId)))
                .isInstanceOf(InvalidAgentTurnRequestException.class);
        verify(outboxWriter, never()).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void turn_rejectsTooManySkillDraftSourcesBeforeLookup() {
        var sources = List.of(
                new AgentTurnRequest.SkillDraftSourceSelector("agent_0123456789abcdef0123456789abcdef"),
                new AgentTurnRequest.SkillDraftSourceSelector("agent_1123456789abcdef0123456789abcdef"),
                new AgentTurnRequest.SkillDraftSourceSelector("agent_2123456789abcdef0123456789abcdef"),
                new AgentTurnRequest.SkillDraftSourceSelector("agent_3123456789abcdef0123456789abcdef"));
        var request = new AgentTurnRequest("session_1", "doc_1", 7L, "이 작업을 Skill로 만들어줘", "openai", "gpt-5-nano",
                null, sources, List.of(), List.of(), "team",
                new AgentTurnRequest.EditorSnapshot("# 제목\n본문",
                        new AgentTurnRequest.Target("whole_document", 1, 2)));
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 7));

        assertThatThrownBy(() -> service.turn("ws_1", "user_1", request))
                .isInstanceOf(InvalidAgentTurnRequestException.class);
        verify(statusRequester, never()).findAutonomous(anyString(), anyString(), anyString());
        verify(outboxWriter, never()).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void request_deserializesSnakeCasePendingSkillProposalFields() throws Exception {
        String json = """
                {
                  "documentId": "doc_1",
                  "baseVersion": 7,
                  "message": "문서를 점검해줘",
                  "conversationContext": {
                    "pendingSkillProposal": {
                      "scope_type": "personal",
                      "name": "meeting-notes",
                      "description": "회의록을 작성합니다.",
                      "instructions_markdown": "# 정확한 원문 지침",
                      "capabilities": ["document-create"],
                      "allowed_tools": ["list_root_items", "list_folder_children", "create_document"]
                    }
                  },
                  "editorSnapshot": {"markdown": "# 제목\\n본문"}
                }
                """;

        AgentTurnRequest request = new ObjectMapper().readValue(json, AgentTurnRequest.class);

        assertThat(request.conversationContext().pendingSkillProposal().scopeType()).isEqualTo("personal");
        assertThat(request.conversationContext().pendingSkillProposal().instructionsMarkdown())
                .isEqualTo("# 정확한 원문 지침");
        assertThat(request.conversationContext().pendingSkillProposal().capabilities())
                .containsExactly("document-create");
    }

    @Test
    void turn_rejectsNonMarkdownBeforeQueue() {
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("paper.pdf", "application/pdf", 7));

        assertThatThrownBy(() -> service.turn("ws_1", "user_1", request("whole_document", 1, 2)))
                .isInstanceOf(InvalidAgentTurnRequestException.class);
        verify(outboxWriter, never()).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void turn_rejectsStaleBaseVersionBeforeQueue() {
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 9));

        assertThatThrownBy(() -> service.turn("ws_1", "user_1", request("whole_document", 1, 2)))
                .isInstanceOf(DocumentVersionConflictException.class);
        verify(outboxWriter, never()).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void get_removedWorkspaceMemberHidesMalformedRunId() {
        doThrow(new WorkspaceNotFoundException("ws_1"))
                .when(workspaceAccessGuard).requireMember("ws_1", "user_1");

        assertThatThrownBy(() -> service.get("ws_1", "user_1", "agent_bad"))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verify(statusRequester, never()).find(anyString(), anyString(), anyString());
    }

    @Test
    void get_unknownRunUsesNotFoundException() {
        String runId = "agent_0123456789abcdef0123456789abcdef";
        when(statusRequester.find("ws_1", "user_1", runId)).thenReturn(Optional.empty());
        when(runRepository.find("ws_1", "user_1", runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("ws_1", "user_1", runId))
                .isInstanceOf(AgentRunNotFoundException.class);

        verify(workspaceAccessGuard).requireMember("ws_1", "user_1");
    }

    @Test
    void get_malformedRunIdUsesInvalidRequestException() {
        assertThatThrownBy(() -> service.get("ws_1", "user_1", "agent_bad"))
                .isInstanceOf(InvalidAgentTurnRequestException.class);

        verify(workspaceAccessGuard).requireMember("ws_1", "user_1");
        verify(statusRequester, never()).find(anyString(), anyString(), anyString());
    }

    @Test
    void autonomousLifecycleRequiresMembershipAndForwardsServerScope() {
        String runId = "run-autonomous";
        JsonNode response = new ObjectMapper().createObjectNode().put("id", runId);
        AgentRunApproveRequest approve = new AgentRunApproveRequest(1, "a".repeat(64));
        AgentRunReviseRequest revise = new AgentRunReviseRequest("계획을 좁혀줘");
        when(statusRequester.getAutonomousRun("ws_1", "user_1", runId)).thenReturn(response);
        when(statusRequester.approve("ws_1", "user_1", runId, 1, "a".repeat(64))).thenReturn(response);
        when(statusRequester.reject("ws_1", "user_1", runId)).thenReturn(response);
        when(statusRequester.cancel("ws_1", "user_1", runId)).thenReturn(response);
        when(statusRequester.revise("ws_1", "user_1", runId, "계획을 좁혀줘")).thenReturn(response);

        service.getRun("ws_1", "user_1", runId);
        service.approve("ws_1", "user_1", runId, approve);
        service.reject("ws_1", "user_1", runId);
        service.cancel("ws_1", "user_1", runId);
        service.revise("ws_1", "user_1", runId, revise);

        verify(workspaceAccessGuard, org.mockito.Mockito.times(5)).requireMember("ws_1", "user_1");
    }

    @Test
    void get_firstPollReturnsCoreQueuedProjectionBeforeAiRunExists() {
        String runId = "agent_0123456789abcdef0123456789abcdef";
        when(statusRequester.find("ws_1", "user_1", runId)).thenReturn(Optional.empty());
        when(runRepository.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new AgentRunCommandRepository.RunView(
                        runId, "doc_1", 7L, "op_1", "queued", null, null)));

        var response = service.get("ws_1", "user_1", runId);

        assertThat(response.status()).isEqualTo("queued");
    }

    @Test
    void get_prefersScopedAiRunStatusAfterAiRunExists() {
        String runId = "agent_0123456789abcdef0123456789abcdef";
        var result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("changed", true);
        when(statusRequester.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new PipelineAgentRunStatusRequester.RunStatus(
                        runId, "doc_1", 7L, "op_1", "completed", result, null)));

        var response = service.get("ws_1", "user_1", runId);

        assertThat(response.status()).isEqualTo("completed");
    }

    @Test
    void get_hidesAiCompletedWhileLocalProjectionStillQueued() {
        String runId = "agent_0123456789abcdef0123456789abcdef";
        var result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("changed", true);
        when(statusRequester.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new PipelineAgentRunStatusRequester.RunStatus(
                        runId, "doc_1", 7L, "op_1", "completed", result, null)));
        when(runRepository.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new AgentRunCommandRepository.RunView(
                        runId, "doc_1", 7L, "op_1", "queued", null, null)));

        var response = service.get("ws_1", "user_1", runId);

        assertThat(response.status()).isEqualTo("queued");
        assertThat(response.result()).isNull();
    }

    @Test
    void get_prefersFailedCoreProjectionOverAiCompleted() {
        String runId = "agent_0123456789abcdef0123456789abcdef";
        var result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("changed", true);
        when(statusRequester.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new PipelineAgentRunStatusRequester.RunStatus(
                        runId, "doc_1", 7L, "op_1", "completed", result, null)));
        when(runRepository.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new AgentRunCommandRepository.RunView(
                        runId, "doc_1", 7L, "op_1", "failed", null, "agent_turn_failed")));

        var response = service.get("ws_1", "user_1", runId);

        assertThat(response.status()).isEqualTo("failed");
        assertThat(response.error()).isEqualTo("agent_turn_failed");
        assertThat(response.result()).isNull();
    }

    @Test
    void get_mapsConsumedCoreProjectionToCompletedAfterAiRunCompletes() {
        String runId = "agent_0123456789abcdef0123456789abcdef";
        var result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("changed", true);
        when(statusRequester.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new PipelineAgentRunStatusRequester.RunStatus(
                        runId, "doc_1", 7L, "op_1", "completed", result, null)));
        when(runRepository.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new AgentRunCommandRepository.RunView(
                        runId, "doc_1", 7L, "op_1", "consumed", result, null)));

        var response = service.get("ws_1", "user_1", runId);

        assertThat(response.status()).isEqualTo("completed");
    }

    @Test
    void get_404FallbackReturnsReadyProjectionResult() {
        String runId = "agent_0123456789abcdef0123456789abcdef";
        var result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("changed", true);
        when(statusRequester.find("ws_1", "user_1", runId)).thenReturn(Optional.empty());
        when(runRepository.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new AgentRunCommandRepository.RunView(
                        runId, "doc_1", 7L, "op_1", "ready", result, null)));

        var response = service.get("ws_1", "user_1", runId);

        assertThat(response.status()).isEqualTo("completed");
        assertThat(response.result()).isSameAs(result);
    }

    @Test
    void get_404FallbackMapsConsumedProjectionToCompleted() {
        String runId = "agent_0123456789abcdef0123456789abcdef";
        var result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("changed", true);
        when(statusRequester.find("ws_1", "user_1", runId)).thenReturn(Optional.empty());
        when(runRepository.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new AgentRunCommandRepository.RunView(
                        runId, "doc_1", 7L, "op_1", "consumed", result, null)));

        var response = service.get("ws_1", "user_1", runId);

        assertThat(response.status()).isEqualTo("completed");
    }

    @Test
    void get_404FallbackReturnsFailedProjectionError() {
        String runId = "agent_0123456789abcdef0123456789abcdef";
        when(statusRequester.find("ws_1", "user_1", runId)).thenReturn(Optional.empty());
        when(runRepository.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new AgentRunCommandRepository.RunView(
                        runId, "doc_1", 7L, "op_1", "failed", null, "agent_turn_failed")));

        var response = service.get("ws_1", "user_1", runId);

        assertThat(response.status()).isEqualTo("failed");
        assertThat(response.error()).isEqualTo("agent_turn_failed");
    }

    private AgentTurnRequest request(String type, int startLine, int endLine) {
        return request(type, startLine, endLine, null);
    }

    private AgentTurnRequest request(String type, int startLine, int endLine,
                                     AgentTurnRequest.ConversationContext conversationContext) {
        return new AgentTurnRequest("session_1", "doc_1", 7L, "문서를 점검해줘", "openai", "gpt-5-nano", conversationContext,
                new AgentTurnRequest.EditorSnapshot("# 제목\n본문",
                        new AgentTurnRequest.Target(type, startLine, endLine)));
    }

    private AgentTurnRequest skillDraftRequest(String sourceRunId) {
        return new AgentTurnRequest("session_1", 
                "doc_1", 7L, "이 작업을 Skill로 만들어줘", "openai", "gpt-5-nano", null,
                List.of(new AgentTurnRequest.SkillDraftSourceSelector(sourceRunId)),
                List.of("일반화해줘"), List.of("secret-doc"), "team",
                new AgentTurnRequest.EditorSnapshot("# 제목\n본문",
                        new AgentTurnRequest.Target("whole_document", 1, 2)));
    }

    private PipelineAgentRunStatusRequester.AutonomousRun autonomousRun(
            String runId, String status, String requestSummary, String planSummary,
            List<PipelineAgentRunStatusRequester.Operation> operations) {
        return new PipelineAgentRunStatusRequester.AutonomousRun(
                runId, "workspace_workflow", status, requestSummary,
                new PipelineAgentRunStatusRequester.Plan(planSummary, operations));
    }

    private DocumentDetailResponse document(String filename, String mimeType, long currentVersion) {
        return new DocumentDetailResponse(
                "doc_1", filename, mimeType, 10, DocumentStatus.completed,
                "s3://source", null, Instant.now(), Instant.now(), null, List.of(),
                null, null, null, filename.substring(0, filename.lastIndexOf('.')),
                filename.substring(filename.lastIndexOf('.') + 1), null, false, currentVersion,
                currentVersion, null, Instant.now(), null, null);
    }

    /** 고른 문답 ID는 조회의 IN 절로 그대로 들어간다. 클라이언트가 SQL 크기를 정하지 못하게 막는다. */
    @Test
    void request_rejectsTooManySelectedPairIds() {
        java.util.List<String> tooMany = java.util.stream.IntStream.rangeClosed(0, 20)
                .mapToObj(index -> "pair_" + index)
                .toList();
        var request = new AgentTurnRequest("session_1", null, null, "정리해줘", "openai", "gpt-5-nano",
                new AgentTurnRequest.ConversationContext(tooMany, null, null), null);

        var violations = jakarta.validation.Validation.buildDefaultValidatorFactory()
                .getValidator().validate(request);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("conversationContext.selectedPairIds");
    }
    @Test
    void cancelledTurnStillAllowsEventSubscriptionAfterProjectionRollback() {
        when(cancellation.findStatus("agent_12345678123456781234567812345678", "ws_1", "user_1"))
                .thenReturn(java.util.Optional.of(java.util.Map.of("status", "cancelled")));
        service.verifyRunAccess("ws_1", "user_1", "agent_12345678123456781234567812345678");
        org.mockito.Mockito.verifyNoInteractions(runRepository);
    }

}
