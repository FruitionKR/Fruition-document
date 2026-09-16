package fruition.core.query.service;

import fruition.core.chat.service.ChatEvidenceRecorder;
import fruition.core.chat.service.ChatTurnRecorder;
import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatMessageReference;
import fruition.core.chat.domain.ChatMessageRelatedPage;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.exception.ChatSessionNotFoundException;
import fruition.core.chat.repository.ChatMessageReferenceRepository;
import fruition.core.chat.repository.ChatMessageRelatedPageRepository;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.repository.ChatSessionRepository;
import fruition.core.document.domain.Document;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.query.dto.QueryResponse;
import fruition.core.query.repository.PipelineQueryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

    private static final String SESSION_ID = "session_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";
    private static final String DOCUMENT_ID = "doc_1f9a74af";

    @Mock ChatMessageRepository chatMessageRepository;
    @Mock ChatMessageReferenceRepository referenceRepository;
    @Mock ChatMessageRelatedPageRepository relatedPageRepository;
    @Mock ChatSessionRepository chatSessionRepository;
    @Mock ChatTurnRecorder chatTurnRecorder;
    @Mock DocumentRepository documentRepository;

    QueryService queryService;

    @BeforeEach
    void setUp() {
        // 근거 저장은 recorder 로 옮겼지만 규칙은 그대로다. 실제 구현을 물려 기존 검증을 유지한다.
        queryService = new QueryService(
                chatMessageRepository,
                chatSessionRepository, chatTurnRecorder,
                new ChatEvidenceRecorder(chatMessageRepository, referenceRepository, relatedPageRepository,
                        documentRepository));
        // 기본은 근거가 가리키는 문서가 모두 이 워크스페이스에 있는 상황이다. 어긋나는 경우는 별도 테스트에서 만든다.
        lenient().when(documentRepository.findAllById(any())).thenAnswer(invocation -> {
            Iterable<?> documentIds = invocation.getArgument(0);
            List<Document> found = new ArrayList<>();
            documentIds.forEach(documentId -> found.add(documentInWorkspace(String.valueOf(documentId), WORKSPACE_ID)));
            return found;
        });
        lenient().when(chatMessageRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        lenient().when(chatMessageRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(referenceRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        lenient().when(relatedPageRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        ChatSession session = new ChatSession(SESSION_ID, "ws_aaa11111", "user_1f9a74af", null);
        lenient().when(chatSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        lenient().when(chatMessageRepository.findById(anyString())).thenAnswer(invocation -> Optional.of(
                new ChatMessage(invocation.getArgument(0), session, "pair_abc123", "assistant", "", "pending",
                        java.time.Instant.now(), null)));
        lenient().when(chatSessionRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("파이프라인 응답이 DTO에 담기고 DB에 저장되어 응답으로 반환된다")
    void query_pipelineResponse_savedAndReturned() {
        PipelineQueryResponse mockResponse = samplePipelineResponse();

        QueryResponse result = complete("Self-Attention이 뭐야?", false, mockResponse);

        // 응답 형태 검증
        assertThat(result.assistantMessage().content()).isEqualTo(mockResponse.answer());
        assertThat(result.relatedPages()).hasSize(2);
        assertThat(result.evidenceSnippets()).hasSize(2);
        assertThat(result.webSearchRequested()).isFalse();
        assertThat(result.webSearchExecuted()).isFalse();
        assertThat(result.resultCount()).isZero();
        assertThat(result.errorCode()).isNull();

        // related_pages role/depth 검증
        assertThat(result.relatedPages().get(0).role()).isEqualTo("seed_source");
        assertThat(result.relatedPages().get(0).depth()).isEqualTo(0);
        assertThat(result.relatedPages().get(1).role()).isEqualTo("focus_concept");

        verify(chatTurnRecorder).createPendingPair(
                eq(SESSION_ID), anyString(), anyString(), anyString(), eq("Self-Attention이 뭐야?"), any(),
                eq("openai"), eq("gpt-5-nano"));
        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getRole()).isEqualTo("assistant");
        assertThat(messageCaptor.getValue().getStatus()).isEqualTo("completed");
        assertThat(messageCaptor.getValue().getContent()).isEqualTo(mockResponse.answer());

        // chat_message_references 저장 검증 (원본 문서 block 기준)
        ArgumentCaptor<List<ChatMessageReference>> refCaptor = ArgumentCaptor.forClass(List.class);
        verify(referenceRepository).saveAll(refCaptor.capture());
        List<ChatMessageReference> savedRefs = refCaptor.getValue();
        assertThat(savedRefs).hasSize(2);

        ChatMessageReference firstRef = savedRefs.stream()
                .filter(r -> r.getRank() == 1).findFirst().orElseThrow();
        assertThat(firstRef.getReferenceType()).isEqualTo("source_block");
        assertThat(firstRef.getDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(firstRef.getSourceBlockIds()).isEqualTo(List.of("B0005", "B0006"));
        assertThat(firstRef.getQuote()).isEqualTo("원본 block citation이 붙어 있던 근거 문장");
        // source_refs는 대표 문서(DOCUMENT_ID) 외 다른 문서(doc_cross) block까지 보존한다.
        assertThat(firstRef.getSourceRefs()).containsExactly(
                new fruition.core.chat.domain.SourceRef(DOCUMENT_ID, "B0005"),
                new fruition.core.chat.domain.SourceRef("doc_cross", "B0009"));

        ChatMessageReference secondRef = savedRefs.stream()
                .filter(r -> r.getRank() == 2).findFirst().orElseThrow();
        assertThat(secondRef.getDocumentId()).isEqualTo("doc_2a8b91cc");
        assertThat(secondRef.getSourceBlockIds()).isEqualTo(List.of("B0010"));

        // chat_message_related_pages 저장 검증
        ArgumentCaptor<List<ChatMessageRelatedPage>> rpCaptor = ArgumentCaptor.forClass(List.class);
        verify(relatedPageRepository).saveAll(rpCaptor.capture());
        List<ChatMessageRelatedPage> savedRelatedPages = rpCaptor.getValue();
        assertThat(savedRelatedPages).hasSize(2);
        assertThat(savedRelatedPages.get(0).getRole()).isEqualTo("seed_source");
        assertThat(savedRelatedPages.get(0).getDepth()).isEqualTo(0);
        assertThat(savedRelatedPages.get(0).getRank()).isEqualTo(1);
        assertThat(savedRelatedPages.get(1).getRank()).isEqualTo(2);

        // 세션 last_message_at 갱신 검증
        verify(chatSessionRepository).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("웹 근거와 로컬 근거는 모두 반환하고 로컬 근거만 저장한다")
    void query_mixedEvidence_returnsBothAndPersistsOnlyLocal() {
        List<PipelineQueryResponse.EvidenceSnippet> evidence = List.of(
                new PipelineQueryResponse.EvidenceSnippet(
                        1, DOCUMENT_ID, List.of("B0001"), List.of(
                        new PipelineQueryResponse.SourceRef(DOCUMENT_ID, "B0001")), "로컬 근거"),
                new PipelineQueryResponse.EvidenceSnippet(
                        2, "web:search-123", List.of(), List.of(
                        new PipelineQueryResponse.SourceRef("web:search-123", "web-block")), "웹 근거")
        );
        PipelineQueryResponse response = responseWithEvidence(evidence);

        QueryResponse result = complete("질문", true, response);

        assertThat(result.evidenceSnippets()).containsExactlyElementsOf(evidence);
        ArgumentCaptor<List<ChatMessageReference>> refCaptor = ArgumentCaptor.forClass(List.class);
        verify(referenceRepository).saveAll(refCaptor.capture());
        assertThat(refCaptor.getValue()).hasSize(1);
        assertThat(refCaptor.getValue().get(0).getDocumentId()).isEqualTo(DOCUMENT_ID);
    }

    /**
     * document_id에는 documents FK가 걸려 있다. 없는 문서를 가리키는 근거를 그대로 넣으면 flush에서
     * 터져 답변까지 되돌아간다. 통합 입구에서는 그 롤백이 결과 반영 전체를 무르고 재시도를 부른다.
     */
    @Test
    @DisplayName("근거가 없는 문서를 가리키면 그 근거만 빼고 저장한다")
    void query_evidenceForMissingDocument_isSkipped() {
        List<PipelineQueryResponse.EvidenceSnippet> evidence = List.of(
                new PipelineQueryResponse.EvidenceSnippet(
                        1, DOCUMENT_ID, List.of("B0001"), List.of(
                        new PipelineQueryResponse.SourceRef(DOCUMENT_ID, "B0001")), "남아 있는 문서"),
                new PipelineQueryResponse.EvidenceSnippet(
                        2, "doc_deleted99", List.of("B0002"), List.of(
                        new PipelineQueryResponse.SourceRef("doc_deleted99", "B0002")), "사라진 문서")
        );
        // 삭제된 문서는 조회에서 빠진다. when()으로 다시 스텁하면 setUp의 answer가 먼저 불리므로 doReturn을 쓴다.
        doReturn(List.of(documentInWorkspace(DOCUMENT_ID, WORKSPACE_ID)))
                .when(documentRepository).findAllById(any());
        PipelineQueryResponse response = responseWithEvidence(evidence);

        QueryResponse result = complete("질문", true, response);

        // 응답에는 그대로 실어 보낸다. 저장만 걸러 낸다.
        assertThat(result.evidenceSnippets()).containsExactlyElementsOf(evidence);
        ArgumentCaptor<List<ChatMessageReference>> refCaptor = ArgumentCaptor.forClass(List.class);
        verify(referenceRepository).saveAll(refCaptor.capture());
        assertThat(refCaptor.getValue()).hasSize(1);
        assertThat(refCaptor.getValue().get(0).getDocumentId()).isEqualTo(DOCUMENT_ID);
    }

    private static Document documentInWorkspace(String documentId, String workspaceId) {
        Document document = org.mockito.Mockito.mock(Document.class);
        lenient().when(document.getId()).thenReturn(documentId);
        lenient().when(document.getWorkspaceId()).thenReturn(workspaceId);
        return document;
    }

    /** 삭제된 문서는 화면에서 열 수 없다. 눌러도 아무 일이 없는 근거를 남기지 않는다. */
    @Test
    @DisplayName("근거가 삭제된 문서를 가리키면 저장하지 않는다")
    void query_evidenceForDeletedDocument_isSkipped() {
        List<PipelineQueryResponse.EvidenceSnippet> evidence = List.of(
                new PipelineQueryResponse.EvidenceSnippet(
                        1, DOCUMENT_ID, List.of("B0001"), List.of(
                        new PipelineQueryResponse.SourceRef(DOCUMENT_ID, "B0001")), "삭제된 문서")
        );
        Document deleted = documentInWorkspace(DOCUMENT_ID, WORKSPACE_ID);
        lenient().when(deleted.getDeletedAt()).thenReturn(java.time.Instant.parse("2026-08-01T00:00:00Z"));
        doReturn(List.of(deleted)).when(documentRepository).findAllById(any());
        PipelineQueryResponse response = responseWithEvidence(evidence);

        complete("질문", true, response);

        ArgumentCaptor<List<ChatMessageReference>> refCaptor = ArgumentCaptor.forClass(List.class);
        verify(referenceRepository).saveAll(refCaptor.capture());
        assertThat(refCaptor.getValue()).isEmpty();
    }

    /**
     * 근거가 다른 워크스페이스 문서를 가리키면 화면에서 열리지 않는 근거가 되고, 그 문서 제목이
     * 근거 목록으로 새어 나간다. 대화가 속한 워크스페이스의 문서만 남긴다.
     */
    @Test
    @DisplayName("근거가 다른 워크스페이스 문서를 가리키면 저장하지 않는다")
    void query_evidenceFromOtherWorkspace_isSkipped() {
        List<PipelineQueryResponse.EvidenceSnippet> evidence = List.of(
                new PipelineQueryResponse.EvidenceSnippet(
                        1, DOCUMENT_ID, List.of("B0001"), List.of(
                        new PipelineQueryResponse.SourceRef(DOCUMENT_ID, "B0001")), "남의 워크스페이스 문서")
        );
        doReturn(List.of(documentInWorkspace(DOCUMENT_ID, "ws_other999")))
                .when(documentRepository).findAllById(any());
        PipelineQueryResponse response = responseWithEvidence(evidence);

        complete("질문", true, response);

        ArgumentCaptor<List<ChatMessageReference>> refCaptor = ArgumentCaptor.forClass(List.class);
        verify(referenceRepository).saveAll(refCaptor.capture());
        assertThat(refCaptor.getValue()).isEmpty();
    }

    /**
     * pipeline 쪽 제목 컬럼은 text라 길이 상한이 없는데 여기 컬럼은 varchar(255)다. 그대로 넣으면
     * flush에서 터져 답변까지 되돌아가고 컨슈머가 같은 메시지를 계속 재시도한다.
     */
    @Test
    @DisplayName("관련 페이지 제목이 컬럼 길이를 넘으면 잘라서 저장한다")
    void query_longRelatedPageTitle_isTruncated() {
        String longTitle = "제".repeat(400);
        PipelineQueryResponse response = new PipelineQueryResponse(
                "답변", null,
                List.of(new PipelineQueryResponse.RelatedPage(
                        "wiki_1", "concept", longTitle, "slug", 0.9, "seed_source", 0)),
                List.of(), null, List.of(), false, false, 0, null);

        complete("질문", true, response);

        ArgumentCaptor<List<ChatMessageRelatedPage>> pageCaptor = ArgumentCaptor.forClass(List.class);
        verify(relatedPageRepository).saveAll(pageCaptor.capture());
        assertThat(pageCaptor.getValue().get(0).getTitle()).hasSize(255);
    }

    /** 경계에 서로게이트 쌍이 걸리면 반쪽만 남아 UTF-8 인코딩이 불가능한 문자열이 된다. */
    @Test
    @DisplayName("자르는 경계가 서로게이트 쌍 한가운데면 한 글자 덜 남긴다")
    void query_titleTruncationDoesNotSplitSurrogatePair() {
        // 254자 뒤에 emoji(서로게이트 쌍)를 두어 255번째 char가 high surrogate가 되게 한다.
        String longTitle = "제".repeat(254) + "😀".repeat(10);
        PipelineQueryResponse response = new PipelineQueryResponse(
                "답변", null,
                List.of(new PipelineQueryResponse.RelatedPage(
                        "wiki_1", "concept", longTitle, "slug", 0.9, "seed_source", 0)),
                List.of(), null, List.of(), false, false, 0, null);

        complete("질문", true, response);

        ArgumentCaptor<List<ChatMessageRelatedPage>> pageCaptor = ArgumentCaptor.forClass(List.class);
        verify(relatedPageRepository).saveAll(pageCaptor.capture());
        String saved = pageCaptor.getValue().get(0).getTitle();
        assertThat(saved).hasSize(254);
        assertThat(Character.isHighSurrogate(saved.charAt(saved.length() - 1))).isFalse();
    }

    @Test
    @DisplayName("웹 근거만 있으면 응답은 완료되고 저장할 참조는 비어 있다")
    void query_allWebEvidence_completesWithEmptySavedReferences() {
        List<PipelineQueryResponse.EvidenceSnippet> evidence = List.of(
                new PipelineQueryResponse.EvidenceSnippet(
                        1, "web:search-123", List.of(), List.of(
                        new PipelineQueryResponse.SourceRef("web:search-123", "web-block")), "웹 근거")
        );
        PipelineQueryResponse response = responseWithEvidence(evidence);

        QueryResponse result = complete("질문", true, response);

        assertThat(result.assistantMessage().status()).isEqualTo("completed");
        assertThat(result.evidenceSnippets()).containsExactlyElementsOf(evidence);
        ArgumentCaptor<List<ChatMessageReference>> refCaptor = ArgumentCaptor.forClass(List.class);
        verify(referenceRepository).saveAll(refCaptor.capture());
        assertThat(refCaptor.getValue()).isEmpty();
    }

    @Test
    void failAsync_marksPendingAssistantFailed() {
        var context = queryService.prepareMessages(SESSION_ID, "질문", "query_test");
        queryService.failAsync("query_test", context, "처리 실패");
        verify(chatTurnRecorder).markFailed(context.assistantMessageId(), "처리 실패");
    }

    @Test
    @DisplayName("세션이 존재하지 않으면 ChatSessionNotFoundException을 던진다")
    void query_unknownSession_throwsChatSessionNotFound() {
        when(chatSessionRepository.findById("session_unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.completeAsync("session_unknown", "질문", "query_test",
                new QueryService.QueryMessageContext("pair", "user", "assistant", java.time.Instant.now()), samplePipelineResponse()))
                .isInstanceOf(ChatSessionNotFoundException.class);
    }

    @Test
    @DisplayName("새 pair 저장 전에 같은 세션의 완료된 최근 6개 메시지만 시간순으로 전달한다")
    void query_forwardsRecentCompletedMessagesBeforeCreatingPendingPair() {
        ChatSession session = new ChatSession(SESSION_ID, WORKSPACE_ID, "user_1f9a74af", null);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION_ID)).thenReturn(List.of(
                message(session, "old_user", "pair_old", "user", "오래된 질문", "completed", 1),
                message(session, "old_assistant", "pair_old", "assistant", "오래된 답변", "completed", 2),
                message(session, "pending_user", "pair_pending", "user", "진행 중인 질문", "completed", 3),
                message(session, "pending_assistant", "pair_pending", "assistant", "", "pending", 3),
                message(session, "failed_user", "pair_failed", "user", "실패한 질문", "completed", 4),
                message(session, "failed_assistant", "pair_failed", "assistant", "", "failed", 4),
                message(session, "user_2", "pair_2", "user", "질문2", "completed", 5),
                message(session, "assistant_2", "pair_2", "assistant", "답변2", "completed", 6),
                message(session, "user_3", "pair_3", "user", "질문3", "completed", 7),
                message(session, "assistant_3", "pair_3", "assistant", "답변3", "completed", 8),
                message(session, "user_4", "pair_4", "user", "질문4", "completed", 9),
                message(session, "assistant_4", "pair_4", "assistant", "답변4", "completed", 10)
        ));

        var context = queryService.prepareMessages(SESSION_ID, "새 질문", "query_test");

        assertThat(context.recentMessages()).extracting(QueryService.RecentMessage::content)
                .containsExactly("질문2", "답변2", "질문3", "답변3", "질문4", "답변4");

        InOrder order = org.mockito.Mockito.inOrder(chatMessageRepository, chatTurnRecorder);
        order.verify(chatMessageRepository).findAllBySessionIdInTurnOrder(SESSION_ID);
        order.verify(chatTurnRecorder).createPendingPair(
                eq(SESSION_ID), anyString(), anyString(), anyString(), eq("새 질문"), any(),
                eq("openai"), eq("gpt-5-nano"));
    }

    @Test
    @DisplayName("동일 timestamp의 pair는 저장 순서와 무관하게 pairId 후 role 순서로 전달한다")
    void prepareMessages_ordersSameTimestampPairsByPairIdThenRole() {
        ChatSession session = new ChatSession(SESSION_ID, WORKSPACE_ID, "user_1f9a74af", null);
        java.time.Instant createdAt = java.time.Instant.parse("2026-06-20T10:00:00Z");
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION_ID)).thenReturn(List.of(
                message(session, "assistant_pair_b", "pair_b", "assistant", "답변B", "completed", createdAt),
                message(session, "user_pair_a", "pair_a", "user", "질문A", "completed", createdAt),
                message(session, "assistant_pair_a", "pair_a", "assistant", "답변A", "completed", createdAt),
                message(session, "user_pair_b", "pair_b", "user", "질문B", "completed", createdAt)
        ));

        QueryService.QueryMessageContext context = queryService.prepareMessages(SESSION_ID, "새 질문", null);

        assertThat(context.recentMessages()).extracting(QueryService.RecentMessage::content)
                .containsExactly("질문A", "답변A", "질문B", "답변B");
        assertThat(context.recentMessages()).extracting(QueryService.RecentMessage::role)
                .containsExactly("user", "assistant", "user", "assistant");
    }

    @Test
    @DisplayName("최근 완료 pair의 각 메시지는 파이프라인 스키마 최대 길이로 잘라 전달한다")
    void query_capsRecentMessageContentBeforeSynchronousPipelineRequest() {
        ChatSession session = new ChatSession(SESSION_ID, WORKSPACE_ID, "user_1f9a74af", null);
        String longUserContent = "u".repeat(4001);
        String longAssistantContent = "a".repeat(4001);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION_ID)).thenReturn(List.of(
                message(session, "user_long", "pair_long", "user", longUserContent, "completed", 1),
                message(session, "assistant_long", "pair_long", "assistant", longAssistantContent, "completed", 2)
        ));

        var context = queryService.prepareMessages(SESSION_ID, "새 질문", "query_test");

        assertThat(context.recentMessages()).extracting(QueryService.RecentMessage::content)
                .containsExactly("u".repeat(4000), "a".repeat(4000));
    }

    private QueryResponse complete(String question, boolean webSearch, PipelineQueryResponse response) {
        var context = queryService.prepareMessages(SESSION_ID, question, "query_test", "openai", "gpt-5-nano", webSearch);
        return queryService.completeAsync(SESSION_ID, question, "query_test", context, response);
    }

    private ChatMessage message(ChatSession session, String id, String pairId, String role, String content,
                                String status, int second) {
        return message(session, id, pairId, role, content, status,
                java.time.Instant.parse("2026-06-20T10:00:" + String.format("%02d", second) + "Z"));
    }

    private ChatMessage message(ChatSession session, String id, String pairId, String role, String content,
                                String status, java.time.Instant createdAt) {
        return new ChatMessage(id, session, pairId, role, content, status,
                createdAt, null);
    }

    private PipelineQueryResponse samplePipelineResponse() {
        String sourceId = "source:codex-container-llm-wiki-api-20260611_013043";
        String conceptId = "concept:index-md";

        List<PipelineQueryResponse.RelatedPage> relatedPages = List.of(
                new PipelineQueryResponse.RelatedPage(sourceId, "source", "LLM Wiki",
                        "codex-container-llm-wiki-api-20260611_013043", 1.0, "seed_source", 0),
                new PipelineQueryResponse.RelatedPage(conceptId, "concept", "Index.md",
                        "index-md", 1.0, "focus_concept", 1)
        );

        List<PipelineQueryResponse.EvidenceSnippet> evidenceSnippets = List.of(
                new PipelineQueryResponse.EvidenceSnippet(
                        1, DOCUMENT_ID, List.of("B0005", "B0006"),
                        List.of(new PipelineQueryResponse.SourceRef(DOCUMENT_ID, "B0005"),
                                new PipelineQueryResponse.SourceRef("doc_cross", "B0009")),
                        "원본 block citation이 붙어 있던 근거 문장"),
                new PipelineQueryResponse.EvidenceSnippet(
                        2, "doc_2a8b91cc", List.of("B0010"),
                        List.of(new PipelineQueryResponse.SourceRef("doc_2a8b91cc", "B0010")),
                        "두 번째 근거 block 본문")
        );

        PipelineQueryResponse.GraphEdge edge = new PipelineQueryResponse.GraphEdge(
                sourceId, conceptId, "source_mentions_concept", "seed_to_focus", 1.0);
        PipelineQueryResponse.GraphContext graphContext = new PipelineQueryResponse.GraphContext(
                relatedPages, List.of(edge));

        PipelineQueryResponse.TraversalPath traversalPath = new PipelineQueryResponse.TraversalPath(
                "path_1", "primary_answer_path", true, 1.0, "answer_context_selected",
                List.of(sourceId, conceptId), List.of(edge));

        return new PipelineQueryResponse(
                "Index.md는 위키 내 모든 페이지를 카테고리별로 정리한 카탈로그 파일 역할을 합니다. [1]",
                null, relatedPages, evidenceSnippets, graphContext, List.of(traversalPath),
                false, false, 0, null
        );
    }

    private PipelineQueryResponse responseWithEvidence(List<PipelineQueryResponse.EvidenceSnippet> evidence) {
        return new PipelineQueryResponse("답변", null, List.of(), evidence, null, List.of(), false, false, 0, null);
    }
}
