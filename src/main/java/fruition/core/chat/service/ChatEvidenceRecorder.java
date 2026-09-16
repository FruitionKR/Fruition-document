package fruition.core.chat.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatMessageReference;
import fruition.core.chat.domain.ChatMessageRelatedPage;
import fruition.core.chat.domain.SourceRef;
import fruition.core.chat.repository.ChatMessageReferenceRepository;
import fruition.core.chat.repository.ChatMessageRelatedPageRepository;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.document.domain.Document;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.query.repository.PipelineQueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 답변의 근거를 메시지에 붙인다.
 *
 * <p>질의 경로와 통합 입구(Agent turn)가 같은 pipeline 응답을 받으므로 저장 규칙을 여기 한 곳에 둔다.
 * 두 곳에 복제하면 같은 답변이 어느 입구로 들어왔는지에 따라 근거가 보이거나 사라진다.
 */
@Service
public class ChatEvidenceRecorder {

    private static final Logger log = LoggerFactory.getLogger(ChatEvidenceRecorder.class);
    private static final String REFERENCE_TYPE_SOURCE_BLOCK = "source_block";
    /** chat_message_related_pages.title 컬럼 길이. */
    private static final int MAX_TITLE_LENGTH = 255;

    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageReferenceRepository referenceRepository;
    private final ChatMessageRelatedPageRepository relatedPageRepository;
    private final DocumentRepository documentRepository;

    public ChatEvidenceRecorder(ChatMessageRepository chatMessageRepository,
                                ChatMessageReferenceRepository referenceRepository,
                                ChatMessageRelatedPageRepository relatedPageRepository,
                                DocumentRepository documentRepository) {
        this.chatMessageRepository = chatMessageRepository;
        this.referenceRepository = referenceRepository;
        this.relatedPageRepository = relatedPageRepository;
        this.documentRepository = documentRepository;
    }

    /**
     * 메시지 ID만 아는 호출부(결과 이벤트 반영)를 위한 진입점.
     *
     * <p>세션을 지우면 메시지도 함께 지워지므로, 진행 중이던 턴의 결과가 뒤늦게 오면 대상이 없다.
     * 정상 흐름이라 실패로 보지 않되 흔적은 남긴다. 근거가 왜 없는지 되짚을 때 유일한 단서다.
     */
    @Transactional
    public void record(String assistantMessageId, PipelineQueryResponse response) {
        chatMessageRepository.findById(assistantMessageId)
                .ifPresentOrElse(
                        message -> record(message, response),
                        () -> log.info("[답변 근거 저장 생략] messageId={} reason=메시지를 찾을 수 없음",
                                assistantMessageId));
    }

    @Transactional
    public void record(ChatMessage assistantMessage, PipelineQueryResponse response) {
        List<ChatMessageReference> references = buildReferences(assistantMessage, response);
        List<ChatMessageRelatedPage> relatedPages = buildRelatedPages(assistantMessage, response);
        referenceRepository.saveAll(references);
        relatedPageRepository.saveAll(relatedPages);
        log.info("[답변 근거 저장] messageId={} referenceCount={} relatedPageCount={}",
                assistantMessage.getId(), references.size(), relatedPages.size());
    }

    /**
     * 제목은 pipeline 쪽 컬럼이 text라 길이 상한이 없는데 여기 컬럼은 varchar(255)다. 그대로 넣으면
     * flush에서 터져 답변까지 되돌아가므로 잘라서 담는다. slug·page_type은 양쪽 다 255자라 그대로 둔다.
     *
     * <p>근거 참조와 달리 워크스페이스를 확인하지 않는다. wiki_page_id에는 FK가 없고 위키 페이지는
     * pipeline이 자기 DB에서 소유해 여기서 대조할 테이블이 없다. 즉 pipeline이 워크스페이스 경계를
     * 지킨다는 가정에 기대고 있다. 이 가정이 깨지면 남의 페이지 제목이 근거 목록에 그대로 보인다.
     */
    private List<ChatMessageRelatedPage> buildRelatedPages(ChatMessage assistantMessage,
                                                           PipelineQueryResponse pipelineResponse) {
        if (pipelineResponse.relatedPages() == null) return List.of();

        List<ChatMessageRelatedPage> pages = new ArrayList<>();
        List<PipelineQueryResponse.RelatedPage> relatedPages = pipelineResponse.relatedPages();
        for (int i = 0; i < relatedPages.size(); i++) {
            PipelineQueryResponse.RelatedPage rp = relatedPages.get(i);
            pages.add(new ChatMessageRelatedPage(
                    assistantMessage, rp.id(), rp.pageType(), truncateTitle(rp.title()), rp.slug(),
                    rp.relevanceScore(), rp.role(), rp.depth(), i + 1
            ));
        }
        return pages;
    }

    private static String truncateTitle(String title) {
        if (title == null || title.length() <= MAX_TITLE_LENGTH) return title;
        // 경계가 서로게이트 쌍 한가운데면 한 글자 더 줄인다. 반쪽만 남기면 UTF-8로 인코딩할 수 없어
        // 결국 같은 자리에서 터진다.
        int end = Character.isHighSurrogate(title.charAt(MAX_TITLE_LENGTH - 1))
                ? MAX_TITLE_LENGTH - 1
                : MAX_TITLE_LENGTH;
        return title.substring(0, end);
    }

    /**
     * 웹 검색 근거는 우리 문서가 아니라 저장하지 않는다. 화면에는 응답으로만 보인다.
     *
     * <p>우리 문서를 가리키는 근거도 그냥 넣지 않고 {@link #exclusionReason}으로 한 번 거른다.
     * 걸러야 하는 이유는 두 가지다. document_id에 걸린 documents FK를 어기면 flush에서 터지는데,
     * 이 저장은 결과 반영 트랜잭션 안에서 일어나므로 답변까지 함께 되돌아가고 컨슈머가 같은 메시지를
     * 계속 재시도한다. 그리고 근거는 답변에 딸린 정보라 못 쓸 근거 때문에 답변을 잃을 이유가 없다.
     * 무엇을 거를지는 {@link #exclusionReason}에만 적는다.
     */
    private List<ChatMessageReference> buildReferences(ChatMessage assistantMessage,
                                                       PipelineQueryResponse pipelineResponse) {
        if (pipelineResponse.evidenceSnippets() == null) return List.of();

        List<PipelineQueryResponse.EvidenceSnippet> usable = pipelineResponse.evidenceSnippets().stream()
                .filter(snippet -> snippet.sourceDocumentId() != null
                        && !snippet.sourceDocumentId().startsWith("web:")
                        && snippet.text() != null && !snippet.text().isBlank())
                .toList();
        if (usable.isEmpty()) return List.of();

        String workspaceId = assistantMessage.getSession().getWorkspaceId();
        Map<String, Document> documentsById = documentsById(usable);
        List<ChatMessageReference> refs = new ArrayList<>();
        for (PipelineQueryResponse.EvidenceSnippet snippet : usable) {
            Document document = documentsById.get(snippet.sourceDocumentId());
            String excluded = exclusionReason(document, workspaceId);
            if (excluded != null) {
                log.warn("[답변 근거 제외] messageId={} rank={} documentId={} reason={}",
                        assistantMessage.getId(), snippet.rank(), snippet.sourceDocumentId(), excluded);
                continue;
            }
            refs.add(new ChatMessageReference(
                    assistantMessage, REFERENCE_TYPE_SOURCE_BLOCK,
                    snippet.sourceDocumentId(), snippet.rank(),
                    snippet.sourceBlockIds(), snippet.text(),
                    toDomainSourceRefs(snippet.sourceRefs())
            ));
        }
        return refs;
    }

    /**
     * 근거로 남길 수 없는 이유. 남길 수 있으면 null. 규칙을 늘릴 자리는 여기 한 곳이다.
     *
     * <p>기준은 "이 대화를 보는 사람이 그 문서를 열 수 있는가"다. 열 수 없는 문서를 가리키는 근거는
     * 눌러도 아무 일이 없고, 남의 워크스페이스 문서라면 제목이 근거 목록으로 새어 나간다.
     *
     * <ul>
     *   <li>없는 문서 — FK를 어겨 저장 자체가 실패한다.
     *   <li>다른 워크스페이스 — 열 수도 없고 제목이 새어 나간다.
     *   <li>삭제된 문서 — 화면에서 열 수 없다.
     * </ul>
     */
    private static String exclusionReason(Document document, String workspaceId) {
        if (document == null) return "문서를 찾을 수 없음";
        if (!document.getWorkspaceId().equals(workspaceId)) return "다른 워크스페이스의 문서";
        if (document.getDeletedAt() != null) return "삭제된 문서";
        return null;
    }

    /** 근거 개수만큼 조회하면 N+1이 되므로 문서 ID를 모아 한 번에 읽는다. */
    private Map<String, Document> documentsById(List<PipelineQueryResponse.EvidenceSnippet> snippets) {
        List<String> documentIds = snippets.stream()
                .map(PipelineQueryResponse.EvidenceSnippet::sourceDocumentId)
                .distinct()
                .toList();
        return documentRepository.findAllById(documentIds).stream()
                .collect(Collectors.toMap(Document::getId, document -> document));
    }

    private List<SourceRef> toDomainSourceRefs(List<PipelineQueryResponse.SourceRef> sourceRefs) {
        if (sourceRefs == null) return null;
        return sourceRefs.stream()
                .map(r -> new SourceRef(r.sourceDocumentId(), r.sourceBlockId()))
                .toList();
    }
}
