package fruition.core.chat.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.dto.ChatWikiExportRequest;
import fruition.core.chat.dto.ChatWikiExportResponse;
import fruition.core.chat.exception.EmptyChatWikiExportException;
import fruition.core.chat.exception.InvalidChatWikiExportRequestException;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.document.service.DocumentService;
import fruition.shared.util.SecretMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatWikiExportServiceTest {

    private static final String WS = "ws_1";
    private static final String USER = "user_1";
    private static final String SESSION = "session_1";
    private static final Instant NOW = Instant.parse("2026-07-08T10:00:00Z");

    @Mock ChatSessionService chatSessionService;
    @Mock ChatMessageRepository chatMessageRepository;
    @Mock DocumentService documentService;

    ChatWikiExportService service;

    @BeforeEach
    void setUp() {
        service = new ChatWikiExportService(chatSessionService, chatMessageRepository,
                new ChatWikiMarkdownSerializer(), new SecretMasker(), documentService);
    }

    private ChatSession session() {
        return new ChatSession(SESSION, WS, USER, "제목");
    }

    private ChatMessage msg(ChatSession s, String id, String pairId, String role, String content, String status) {
        return new ChatMessage(id, s, pairId, role, content, status, NOW, null);
    }

    private List<ChatMessage> twoCompletedPairs(ChatSession s) {
        return List.of(
                msg(s, "u1", "p1", "user", "질문1", "completed"),
                msg(s, "a1", "p1", "assistant", "답변1", "completed"),
                msg(s, "u2", "p2", "user", "질문2", "completed"),
                msg(s, "a2", "p2", "assistant", "답변2", "completed")
        );
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<DocumentService.PipelineSourceBlock>> blockCaptor() {
        return ArgumentCaptor.forClass((Class<List<DocumentService.PipelineSourceBlock>>) (Class<?>) List.class);
    }

    private List<String> blockIds(List<DocumentService.PipelineSourceBlock> blocks) {
        return blocks.stream().map(DocumentService.PipelineSourceBlock::blockId).toList();
    }

    private ArgumentCaptor<List<DocumentService.PipelineSourceBlock>> stubDocumentCreation() {
        ArgumentCaptor<List<DocumentService.PipelineSourceBlock>> blocks = blockCaptor();
        when(documentService.createChatExportDocument(
                eq(WS), eq(USER), anyString(), anyString(), anyString(), blocks.capture()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", false));
        return blocks;
    }

    @Test
    @DisplayName("선택한 pair의 문답만 직렬화해 문서로 저장한다")
    void exportsSelectedPairsOnly() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(twoCompletedPairs(s));
        ArgumentCaptor<List<DocumentService.PipelineSourceBlock>> blocks = stubDocumentCreation();

        ChatWikiExportResponse response = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1")));

        assertThat(response.status()).isEqualTo("saved");
        assertThat(response.exportDocumentId()).isEqualTo("chatdoc_1");
        assertThat(blockIds(blocks.getValue())).containsExactly("session_1:p1");
    }

    @Test
    @DisplayName("세션 전체 pair를 넘기면 모든 문답이 한 문서로 들어간다")
    void exportsEveryPairWhenAllSelected() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(twoCompletedPairs(s));
        ArgumentCaptor<List<DocumentService.PipelineSourceBlock>> blocks = stubDocumentCreation();

        service.export(WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1", "p2")));

        assertThat(blockIds(blocks.getValue())).containsExactly("session_1:p1", "session_1:p2");
    }

    @Test
    @DisplayName("본문 제목도 세션 전체가 아닌 발췌한 첫 질문을 사용한다")
    void markdownTitleDescribesSelectedPairs() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(twoCompletedPairs(s));
        ArgumentCaptor<String> markdown = ArgumentCaptor.forClass(String.class);
        when(documentService.createChatExportDocument(
                eq(WS), eq(USER), eq("질문2"), markdown.capture(), anyString(), any()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", false));

        service.export(WS, USER, SESSION, new ChatWikiExportRequest(List.of("p2")));

        assertThat(markdown.getValue()).startsWith("# 질문2\n\nQ : 질문2")
                .contains("A : 답변2").doesNotContain("Chat Export", "질문1", "답변1");
    }

    @Test
    @DisplayName("본문 제목은 비밀값을 마스킹한 질문에서 만든다")
    void markdownTitleUsesMaskedQuestion() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(List.of(
                msg(s, "u1", "p1", "user", "sk-abcdefghijklmnop 연결 방법", "completed"),
                msg(s, "a1", "p1", "assistant", "설정을 확인하세요.", "completed")));

        assertThat(service.previewMarkdown(WS, USER, SESSION))
                .startsWith("# [REDACTED] 연결 방법\n\n")
                .doesNotContain("sk-abcdefghijklmnop");
    }

    @Test
    @DisplayName("완료된 문답이 없는 미리보기도 기본 제목으로 반환한다")
    void emptyPreviewHasSessionTitle() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(List.of());

        assertThat(service.previewMarkdown(WS, USER, SESSION)).isEqualTo("# 제목\n\n");
    }

    @Test
    @DisplayName("임시 문서 이름은 발췌한 첫 질문을 20자로 줄여 쓴다")
    void interimNameComesFromFirstQuestion() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(List.of(
                msg(s, "u1", "p1", "user", "검색 인덱싱은 어떻게 동작하나요?", "completed"),
                msg(s, "a1", "p1", "assistant", "역색인을 씁니다.", "completed")));
        ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
        when(documentService.createChatExportDocument(
                eq(WS), eq(USER), displayName.capture(), anyString(), anyString(), any()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", false));

        service.export(WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1")));

        // 18자라 그대로, 세션 제목("제목")이 아니라 질문에서 온다.
        assertThat(displayName.getValue()).isEqualTo("검색 인덱싱은 어떻게 동작하나요?");
    }

    @Test
    @DisplayName("긴 질문은 20자에서 줄임표로 접는다")
    void interimNameIsTruncated() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(List.of(
                msg(s, "u1", "p1", "user", "검색 인덱싱 파이프라인에서 청크 크기를 바꾸려면 어디를 봐야 하나요?", "completed"),
                msg(s, "a1", "p1", "assistant", "설정 파일을 봅니다.", "completed")));
        ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
        when(documentService.createChatExportDocument(
                eq(WS), eq(USER), displayName.capture(), anyString(), anyString(), any()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", false));

        service.export(WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1")));

        assertThat(displayName.getValue()).hasSize(20).endsWith("…");
    }

    @Test
    @DisplayName("이모지가 섞인 긴 질문도 문자가 깨지지 않게 자른다")
    void interimNameTruncatesByCodePoint() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(List.of(
                msg(s, "u1", "p1", "user", "🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉 축하", "completed"),
                msg(s, "a1", "p1", "assistant", "네", "completed")));
        ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
        when(documentService.createChatExportDocument(
                eq(WS), eq(USER), displayName.capture(), anyString(), anyString(), any()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", false));

        service.export(WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1")));

        String name = displayName.getValue();
        // 이모지는 char 2개라 char 기준으로 자르면 짝이 깨진다.
        assertThat(name.codePointCount(0, name.length())).isEqualTo(20);
        assertThat(name).endsWith("…");
        // 짝이 깨진 surrogate가 있으면 UTF-8 왕복에서 대체 문자로 바뀐다.
        byte[] utf8 = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(new String(utf8, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(name);
    }

    @Test
    @DisplayName("질문의 경로 문자는 파일명에 쓸 수 있게 걷어낸다")
    void interimNameStripsPathCharacters() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(List.of(
                msg(s, "u1", "p1", "user", "src/main 경로는?", "completed"),
                msg(s, "a1", "p1", "assistant", "거기 있습니다.", "completed")));
        ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
        when(documentService.createChatExportDocument(
                eq(WS), eq(USER), displayName.capture(), anyString(), anyString(), any()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", false));

        service.export(WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1")));

        assertThat(displayName.getValue()).isEqualTo("src main 경로는?").doesNotContain("/");
    }

    @Test
    @DisplayName("질문을 쓸 수 없으면 세션 제목으로 떨어지고, 제목도 없으면 세션 ID 대신 기본 제목을 쓴다")
    void interimNameFallsBackWithoutLeakingSessionId() {
        // 기본 제목이 생기기 전에 저장된 세션 상태를 재현한다(생성자는 이제 빈 제목을 채운다).
        ChatSession s = new ChatSession(SESSION, WS, USER, "제목");
        org.springframework.test.util.ReflectionTestUtils.setField(s, "title", null);
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(List.of(
                msg(s, "u1", "p1", "user", "", "completed"),   // 질문이 비어 이름 재료가 없다
                msg(s, "a1", "p1", "assistant", "답변1", "completed")));
        ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
        when(documentService.createChatExportDocument(
                eq(WS), eq(USER), displayName.capture(), anyString(), anyString(), any()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", false));

        service.export(WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1")));

        assertThat(displayName.getValue()).isEqualTo("새 채팅").doesNotContain(SESSION);
    }

    @Test
    @DisplayName("skipped 결과는 status=skipped로 반환한다")
    void skippedResultMapsToSkippedStatus() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(twoCompletedPairs(s));
        when(documentService.createChatExportDocument(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_existing", true));

        ChatWikiExportResponse response = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1")));

        assertThat(response.status()).isEqualTo("skipped");
    }

    @Test
    @DisplayName("pair_ids가 없거나 비면 400")
    void rejectsMissingPairIds() {
        assertThatThrownBy(() -> service.export(WS, USER, SESSION, new ChatWikiExportRequest(null)))
                .isInstanceOf(InvalidChatWikiExportRequestException.class);
        assertThatThrownBy(() -> service.export(WS, USER, SESSION, new ChatWikiExportRequest(List.of())))
                .isInstanceOf(InvalidChatWikiExportRequestException.class);
    }

    @Test
    @DisplayName("완전한 문답이 하나도 없으면 400(EmptyChatWikiExport)")
    void rejectsWhenNoCompletePair() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        // user만 completed, assistant는 failed → 완전한 문답 없음
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(List.of(
                msg(s, "u1", "p1", "user", "질문", "completed"),
                msg(s, "a1", "p1", "assistant", "", "failed")
        ));

        assertThatThrownBy(() -> service.export(WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1"))))
                .isInstanceOf(EmptyChatWikiExportException.class);
    }

    @Test
    @DisplayName("같은 세션을 다시 내보내도 누적하지 않고 매번 새 문서 생성을 요청한다")
    void reExportAlwaysCreatesAnotherDocument() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(twoCompletedPairs(s));
        when(documentService.createChatExportDocument(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", false))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_2", false));

        ChatWikiExportResponse first = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1")));
        ChatWikiExportResponse second = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest(List.of("p2")));

        assertThat(first.exportDocumentId()).isEqualTo("chatdoc_1");
        assertThat(second.exportDocumentId()).isEqualTo("chatdoc_2");
        ArgumentCaptor<List<DocumentService.PipelineSourceBlock>> blocks = blockCaptor();
        verify(documentService, times(2)).createChatExportDocument(
                eq(WS), eq(USER), anyString(), anyString(), anyString(), blocks.capture());
        assertThat(blockIds(blocks.getAllValues().get(0))).containsExactly("session_1:p1");
        assertThat(blockIds(blocks.getAllValues().get(1))).containsExactly("session_1:p2");
    }

    @Test
    @DisplayName("같은 선택을 다시 내보내면 기존 문서를 재사용한다(skipped)")
    void sameSelectionReplayReusesExistingDocument() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(twoCompletedPairs(s));
        when(documentService.createChatExportDocument(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", false))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", true));

        ChatWikiExportResponse first = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1")));
        ChatWikiExportResponse replay = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1")));

        assertThat(first).isEqualTo(new ChatWikiExportResponse("chatdoc_1", "saved"));
        assertThat(replay).isEqualTo(new ChatWikiExportResponse("chatdoc_1", "skipped"));
        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(documentService, times(2)).createChatExportDocument(
                eq(WS), eq(USER), anyString(), anyString(), hashes.capture(), any());
        assertThat(hashes.getAllValues()).hasSize(2).containsOnly(hashes.getAllValues().get(0));
    }

    @Test
    @DisplayName("본문에는 id를 남기지 않고 provenance는 블록으로만 넘긴다")
    void keepsProvenanceOutOfMarkdownBody() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(twoCompletedPairs(s));
        ArgumentCaptor<String> markdown = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<DocumentService.PipelineSourceBlock>> blocks = blockCaptor();
        when(documentService.createChatExportDocument(
                eq(WS), eq(USER), anyString(), markdown.capture(), anyString(), blocks.capture()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", false));

        service.export(WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1", "p2")));

        assertThat(markdown.getValue())
                .contains("Q : 질문1")
                .contains("Q : 질문2")
                .doesNotContain(SESSION);
        assertThat(blocks.getValue()).extracting(DocumentService.PipelineSourceBlock::blockId)
                .containsExactly("session_1:p1", "session_1:p2");
        assertThat(blocks.getValue().get(0).text()).isEqualTo("Q : 질문1  \nA : 답변1");
    }
}
