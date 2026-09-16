package fruition.core.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.chat.domain.ChatPartialWiki;
import fruition.core.chat.repository.ChatPartialWikiRepository;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatWikiExportReconcilerTest {

    @Mock fruition.core.document.repository.AiCommandOutboxWriter taskWriter;
    @Mock DocumentRepository documentRepository;
    @Mock PipelineWikiStateRequester wikiStateRequester;
    @Mock ChatPartialWikiRepository chatPartialWikiRepository;
    @Mock fruition.core.document.service.DocumentService documentService;

    ChatWikiExportReconciler reconciler;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(taskWriter.join(any())).thenReturn(true);
        // 트랜잭션 경계는 테스트 범위 밖이라 콜백을 그대로 실행한다.
        org.springframework.transaction.support.TransactionTemplate transactionTemplate =
                new org.springframework.transaction.support.TransactionTemplate() {
                    @Override
                    public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                        return action.doInTransaction(new org.springframework.transaction.support.SimpleTransactionStatus());
                    }
                };
        reconciler = new ChatWikiExportReconciler(
                documentRepository, wikiStateRequester, chatPartialWikiRepository, documentService,
                transactionTemplate, new ObjectMapper(), taskWriter);
    }

    /** export 시점에 documents.pipeline_input_blocks에 저장되는 형식 그대로. */
    private static String blocksJson(String... blockIds) {
        return Arrays.stream(blockIds)
                .map(id -> "{\"block_id\":\"" + id + "\",\"text\":\"Q : 질문\\nA : 답변\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private Document chatExportDoc(String documentId, String... blockIds) {
        Document d = new Document(documentId, "ws_1", "user_1", "c.md", "text/markdown", 10L,
                "sources/documents/" + documentId + "/original", "h", "chat_export");
        d.assignSelectionMode("partial");
        d.assignPipelineInput("# Chat Export\n\nQ : 질문\nA : 답변\n\n", blocksJson(blockIds));
        return d;
    }

    private Document chatExportDoc() {
        return chatExportDoc("chatdoc_1", "session_1:pair_1");
    }

    private PipelineWikiStateRequester.DocumentWikiContext wikiContext(String pageTitle) {
        return new PipelineWikiStateRequester.DocumentWikiContext(
                List.of(new PipelineWikiStateRequester.DocumentPage(
                        "wiki_1", "source", pageTitle, "title", "source_of", 1.0)),
                // 파이프라인이 새로 부여한 block ID다. 후처리는 더 이상 이 값을 보지 않는다.
                List.of(new PipelineWikiStateRequester.SourceBlock("B0001", "Q : 질문\nA : 답변")));
    }

    @Test
    @DisplayName("한 문서가 실패해도 나머지 문서는 확정된다")
    void reconciled_isolatesFailurePerDocument() {
        Document broken = chatExportDoc("chatdoc_broken", "session_1:pair_1");
        Document healthy = chatExportDoc();
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(broken, healthy));
        // 앞 문서에서 pipeline 조회가 터진다. 예전에는 이 하나가 tick 전체를 되돌렸다.
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_broken"))
                .thenThrow(new IllegalStateException("pipeline 응답 없음"));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(wikiContext("제목"));

        reconciler.reconcile();

        assertThat(broken.getReconciledAt()).isNull();      // 다음 tick에 다시 시도
        assertThat(healthy.getReconciledAt()).isNotNull();  // 뒤 문서는 확정
        verify(documentRepository).save(healthy);
    }

    @Test
    @DisplayName("후처리 성공 시 reconciled_at을 세팅하고 문서를 저장한다")
    void reconciled_setsReconciledAtAndSaves() {
        Document doc = chatExportDoc();
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(wikiContext("제목"));

        reconciler.reconcile();

        ArgumentCaptor<ChatPartialWiki> membership = ArgumentCaptor.forClass(ChatPartialWiki.class);
        verify(chatPartialWikiRepository).save(membership.capture());
        assertThat(membership.getValue().getSessionId()).isEqualTo("session_1");
        assertThat(membership.getValue().getPairId()).isEqualTo("pair_1");
        assertThat(membership.getValue().getWikiPageId()).isEqualTo("wiki_1");
        assertThat(doc.getReconciledAt()).isNotNull();
        verify(documentRepository).save(doc);
    }

    @Test
    @DisplayName("후처리 시 문서 이름을 Wiki 페이지 제목으로 확정한다")
    void reconciled_confirmsDocumentNameWithWikiPageTitle() {
        Document doc = chatExportDoc();
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(wikiContext("검색 인덱싱"));

        reconciler.reconcile();

        verify(documentService).confirmChatExportName(doc, "검색 인덱싱");
    }

    @Test
    @DisplayName("멤버십은 문서 전체 가드가 아니라 pair별로 누락분을 기록한다")
    void reconciled_recordsMissingPairsAfterPartialFailure() {
        Document doc = chatExportDoc("chatdoc_1", "session_1:pair_a", "session_1:pair_c");
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(wikiContext("제목"));
        when(chatPartialWikiRepository.existsByDocumentIdAndPairId("chatdoc_1", "pair_a")).thenReturn(true);
        when(chatPartialWikiRepository.existsByDocumentIdAndPairId("chatdoc_1", "pair_c")).thenReturn(false, true);

        reconciler.reconcile();
        reconciler.reconcile();

        ArgumentCaptor<ChatPartialWiki> membership = ArgumentCaptor.forClass(ChatPartialWiki.class);
        verify(chatPartialWikiRepository).save(membership.capture());
        assertThat(membership.getValue().getPairId()).isEqualTo("pair_c");
        assertThat(membership.getValue().getWikiPageId()).isEqualTo("wiki_1");
        assertThat(doc.getReconciledAt()).isNotNull();
        verify(documentRepository, times(2)).save(doc);
        verify(chatPartialWikiRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("형식이 깨진 블록 id는 건너뛰고 유효한 것만 기록한다")
    void reconciled_skipsMalformedBlockIds() {
        Document doc = chatExportDoc("chatdoc_1",
                "session_1:pair_valid", "malformed", "session_1:", ":pair_missing_session",
                "session_1:pair:extra", "session_1 :pair_whitespace");
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(wikiContext("제목"));
        when(chatPartialWikiRepository.existsByDocumentIdAndPairId("chatdoc_1", "pair_valid")).thenReturn(false);

        reconciler.reconcile();

        ArgumentCaptor<ChatPartialWiki> membership = ArgumentCaptor.forClass(ChatPartialWiki.class);
        verify(chatPartialWikiRepository).save(membership.capture());
        assertThat(membership.getValue().getSessionId()).isEqualTo("session_1");
        assertThat(membership.getValue().getPairId()).isEqualTo("pair_valid");
        assertThat(doc.getReconciledAt()).isNotNull();
    }

    @Test
    @DisplayName("여러 세션이 섞여 있으면 멤버십 없이 확정해 폴링에서 뺀다")
    void mixedSessions_reconcilesWithoutMembership() {
        Document doc = chatExportDoc("chatdoc_1", "session_1:pair_valid", "session_2:pair_foreign");
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(wikiContext("제목"));

        reconciler.reconcile();

        // export 블록은 뒤에 바뀌지 않으므로 재시도해도 같다. 3초마다 같은 경고를 반복하지 않는다.
        assertThat(doc.getReconciledAt()).isNotNull();
        verify(documentRepository).save(doc);
        verifyNoInteractions(chatPartialWikiRepository);
    }

    @Test
    @DisplayName("저장된 문답 블록이 없으면 멤버십 없이 확정해 폴링에서 뺀다")
    void missingProvenance_reconcilesWithoutMembership() {
        Document doc = new Document("chatdoc_1", "ws_1", "user_1", "c.md", "text/markdown", 10L,
                "sources/documents/chatdoc_1/original", "h", "chat_export");
        doc.assignSelectionMode("partial");
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(wikiContext("제목"));

        reconciler.reconcile();

        assertThat(doc.getReconciledAt()).isNotNull();
        verify(documentRepository).save(doc);
        // 이름 확정은 provenance와 무관하므로 계속 수행한다.
        verify(documentService).confirmChatExportName(doc, "제목");
        verifyNoInteractions(chatPartialWikiRepository);
    }

    @Test
    @DisplayName("문답 블록 JSON이 깨져 있어도 멤버십 없이 확정한다")
    void malformedProvenanceJson_reconcilesWithoutMembership() {
        Document doc = new Document("chatdoc_1", "ws_1", "user_1", "c.md", "text/markdown", 10L,
                "sources/documents/chatdoc_1/original", "h", "chat_export");
        doc.assignSelectionMode("partial");
        doc.assignPipelineInput("# Chat Export\n", "{망가진 JSON");
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(wikiContext("제목"));

        reconciler.reconcile();

        assertThat(doc.getReconciledAt()).isNotNull();
        verify(documentRepository).save(doc);
        verifyNoInteractions(chatPartialWikiRepository);
    }

    @Test
    @DisplayName("Wiki 페이지가 아직 없으면 reconciled_at을 세팅하지 않는다(재시도)")
    void notReady_doesNotReconcile() {
        Document doc = chatExportDoc();
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(
                new PipelineWikiStateRequester.DocumentWikiContext(List.of(), List.of()));

        reconciler.reconcile();

        assertThat(doc.getReconciledAt()).isNull();
        verify(documentRepository, never()).save(doc);
    }
}
