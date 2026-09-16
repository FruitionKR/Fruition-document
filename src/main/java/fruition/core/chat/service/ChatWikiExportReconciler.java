package fruition.core.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.chat.domain.ChatPartialWiki;
import fruition.core.chat.repository.ChatPartialWikiRepository;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.service.DocumentService;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채팅 Wiki page화 export의 처리 완료를 폴링으로 감지해 후처리한다.
 *
 * Backend가 pipeline run 상태를 폴링해 완료 처리한 chat_export 문서를 주기적으로 훑고,
 * Wiki 현재 상태는 pipeline 내부 API로 읽어 후처리한다. 후처리는 발췌 문답 ↔ 위키 페이지 멤버십을
 * {@code chat_partial_wiki}에 기록하는 것뿐이다(1:N). export마다 독립 source page가 생기므로
 * 세션을 특정 page에 연결하지 않는다. 멱등하게 동작한다.
 *
 * session_id와 pair_id는 export 시점에 문서에 저장해 둔 {@code documents.pipeline_input_blocks}에서 읽는다.
 * 파이프라인이 block ID를 새로 부여하므로 왕복시킨 값으로는 원본을 특정할 수 없다.
 */
@Component
public class ChatWikiExportReconciler {

    private static final Logger log = LoggerFactory.getLogger(ChatWikiExportReconciler.class);

    /** export 블록 id의 {@code session_id:pair_id} provenance. group(1)=session_id, group(2)=pair_id. */
    private static final Pattern PAIR_REF = Pattern.compile("(?U)^([^:\\s]+):([^:\\s]+)$");

    private final DocumentRepository documentRepository;
    private final PipelineWikiStateRequester pipelineWikiStateRequester;
    private final ChatPartialWikiRepository chatPartialWikiRepository;
    private final DocumentService documentService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final fruition.core.document.repository.AiCommandOutboxWriter taskWriter;

    public ChatWikiExportReconciler(DocumentRepository documentRepository,
                                    PipelineWikiStateRequester pipelineWikiStateRequester,
                                    ChatPartialWikiRepository chatPartialWikiRepository,
                                    DocumentService documentService,
                                    TransactionTemplate transactionTemplate,
                                    ObjectMapper objectMapper, fruition.core.document.repository.AiCommandOutboxWriter taskWriter) {
        this.documentRepository = documentRepository;
        this.pipelineWikiStateRequester = pipelineWikiStateRequester;
        this.chatPartialWikiRepository = chatPartialWikiRepository;
        this.documentService = documentService;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.taskWriter = taskWriter;
    }

    /**
     * 문서 하나가 실패해도 나머지는 확정되도록 문서 단위로 처리한다. 한 트랜잭션으로 묶으면
     * 뒤쪽 문서의 실패가 앞서 끝낸 문서까지 되돌려, 문서 하나가 전체 후처리를 막는다.
     */
    @Scheduled(fixedDelay = 3000)
    public void reconcile() {
        for (Document document : documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull(
                "chat_export", DocumentStatus.completed)) {
            try {
                reconcileOne(document);
            } catch (RuntimeException e) {
                // 이 문서만 건너뛴다. reconciled_at을 남기지 않았으므로 다음 tick에 다시 시도한다.
                // 예외를 마지막 인자로 넘겨 스택을 남긴다. DB 쓰기 실패까지 여기로 모이므로 메시지만으론 부족하다.
                log.warn("[chat-wiki][reconcile] 건너뜀 document={}", document.getId(), e);
            }
        }
    }

    /** 문서 하나의 후처리. Wiki 상태 조회는 네트워크 호출이라 트랜잭션 밖에서 먼저 끝낸다. */
    private void reconcileOne(Document document) {
        var wikiContext = pipelineWikiStateRequester.documentContext(
                document.getWorkspaceId(), document.getId());

        PipelineWikiStateRequester.DocumentPage sourcePage = wikiContext.pages().stream()
                .filter(page -> "source_of".equals(page.relationType()))
                .findFirst()
                .orElse(null);
        if (sourcePage == null) {
            return; // Wiki 페이지가 아직 없으면 다음 tick에 다시 본다
        }

        ChatProvenance provenance = readProvenance(document);

        transactionTemplate.execute(status -> {
            if (!taskWriter.join(document.getPipelineRunId())) return null;
            // export 시점 이름은 첫 질문을 줄인 임시값이다. 내용을 요약한 페이지 제목이 나왔으면 그걸로 확정한다.
            documentService.confirmChatExportName(document, sourcePage.title());

            if (provenance != null) {
                recordPairs(document.getWorkspaceId(), provenance.sessionId(), provenance.pairIds(),
                        sourcePage.id(), document.getId());
                log.info("[chat-wiki][reconcile] session={} page={} 발췌기록 완료 (document={})",
                        provenance.sessionId(), sourcePage.id(), document.getId());
            }

            // 후처리 완료 → reconciled_at 세팅(다음 tick 조회에서 제외).
            // provenance를 못 읽은 문서도 여기서 제외한다. 재시도해도 결과가 같은데 3초마다
            // 같은 경고를 반복하면 다른 문서의 일시적 실패가 로그에 묻힌다.
            document.markReconciled(Instant.now());
            documentRepository.save(document);
            return null;
        });
    }

    /** 문서 한 건이 담고 있는 채팅 출처. export가 한 세션에서만 만들어지므로 세션은 하나로 확정된다. */
    private record ChatProvenance(String sessionId, Set<String> pairIds) {}

    /**
     * export 시점에 문서에 저장해 둔 문답 블록에서 세션과 문답을 읽는다. 읽을 수 없으면 {@code null}.
     *
     * <p>블록은 export 시점에 한 번 쓰고 그 뒤 바뀌지 않으므로(채팅 문서는 재처리가 막혀 있다)
     * 여기서 실패하면 재시도해도 결과가 같다. 그래서 예외로 올리지 않고 경고를 한 번만 남긴다.
     */
    private ChatProvenance readProvenance(Document document) {
        JsonNode blocks;
        try {
            blocks = objectMapper.readTree(document.getPipelineInputBlocks());
        } catch (Exception e) {
            log.warn("[chat-wiki][reconcile] 문답 블록 JSON을 읽을 수 없어 발췌기록을 건너뛴다 document={}",
                    document.getId(), e);
            return null;
        }
        Set<String> sessionIds = new LinkedHashSet<>();
        Set<String> pairIds = new LinkedHashSet<>();
        for (JsonNode block : blocks) {
            Matcher matcher = PAIR_REF.matcher(block.path("block_id").asText(""));
            if (matcher.matches()) {
                sessionIds.add(matcher.group(1));
                pairIds.add(matcher.group(2));
            }
        }
        if (sessionIds.size() != 1) {
            log.warn("[chat-wiki][reconcile] 문답 블록의 세션 provenance가 유효하지 않아 발췌기록을 건너뛴다"
                    + " document={} sessions={}", document.getId(), sessionIds);
            return null;
        }
        return new ChatProvenance(sessionIds.iterator().next(), pairIds);
    }

    /** 내보낸 문답을 pair별로 기록한다(멱등·부분 실패 후 재시도 가능). */
    private void recordPairs(String workspaceId, String sessionId, Set<String> pairIds, String wikiPageId, String documentId) {
        Instant now = Instant.now();
        for (String pairId : pairIds) {
            if (chatPartialWikiRepository.existsByDocumentIdAndPairId(documentId, pairId)) {
                continue;
            }
            String id = "cpw_" + UUID.randomUUID().toString().replace("-", "");
            chatPartialWikiRepository.save(new ChatPartialWiki(id, workspaceId, sessionId, pairId, wikiPageId, documentId, now));
        }
    }
}
