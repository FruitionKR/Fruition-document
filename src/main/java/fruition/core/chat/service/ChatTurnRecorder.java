package fruition.core.chat.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.exception.ChatSessionNotFoundException;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.repository.ChatSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ChatTurnRecorder {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionRepository chatSessionRepository;

    public ChatTurnRecorder(ChatMessageRepository chatMessageRepository,
                                ChatSessionRepository chatSessionRepository) {
        this.chatMessageRepository = chatMessageRepository;
        this.chatSessionRepository = chatSessionRepository;
    }

    @Transactional
    public void createPendingPair(String sessionId,
                                  String pairId,
                                  String userMessageId,
                                  String assistantMessageId,
                                  String question,
                                  Instant createdAt) {
        createPendingPair(sessionId, pairId, userMessageId, assistantMessageId, question, createdAt,
                "openai", "gpt-5-nano");
    }

    @Transactional
    public void createPendingPair(String sessionId,
                                  String pairId,
                                  String userMessageId,
                                  String assistantMessageId,
                                  String question,
                                  Instant createdAt,
                                  String provider,
                                  String model) {
        createPendingPair(sessionId, pairId, userMessageId, assistantMessageId, question, createdAt,
                provider, model, false);
    }

    @Transactional
    public void createPendingPair(String sessionId,
                                  String pairId,
                                  String userMessageId,
                                  String assistantMessageId,
                                  String question,
                                  Instant createdAt,
                                  String provider,
                                  String model,
                                  boolean webSearchEnabled) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ChatSessionNotFoundException(sessionId));
        chatMessageRepository.saveAll(List.of(
                new ChatMessage(userMessageId, session, pairId, "user", question, "completed", createdAt,
                        null, provider, model, webSearchEnabled),
                new ChatMessage(assistantMessageId, session, pairId, "assistant", "", "pending", createdAt,
                        null, provider, model, webSearchEnabled)
        ));
        session.touchLastMessageAt(createdAt);
        chatSessionRepository.save(session);
    }

    /**
     * Agent turn용 쌍. 질의와 달리 assistant 메시지에 run ID를 새겨, 결과가 왔을 때
     * 어느 말풍선을 채울지와 승인 상태를 어디서 읽을지 정한다.
     */
    @Transactional
    public void createPendingAgentPair(String sessionId,
                                       String pairId,
                                       String userMessageId,
                                       String assistantMessageId,
                                       String message,
                                       Instant createdAt,
                                       String provider,
                                       String model,
                                       String runId) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ChatSessionNotFoundException(sessionId));
        ChatMessage assistant = new ChatMessage(assistantMessageId, session, pairId, "assistant", "", "pending",
                createdAt, null, provider, model, false);
        assistant.assignAgentRun(runId);
        chatMessageRepository.saveAll(List.of(
                new ChatMessage(userMessageId, session, pairId, "user", message, "completed", createdAt,
                        null, provider, model, false),
                assistant
        ));
        session.touchLastMessageAt(createdAt);
        chatSessionRepository.save(session);
    }

    /**
     * pipeline이 갱신해 돌려준 누적 대화 요약을 세션에 남긴다. 다음 턴이 이 요약을 맥락으로 읽는다.
     *
     * <p>대화가 짧으면 pipeline이 요약을 만들지 않아 비어 온다. 그때는 기존 요약을 지우지 않는다.
     * 세션 없이 만들어진 예전 run은 sessionId가 비어 온다.
     */
    @Transactional
    public void recordContextSummary(String sessionId, String summary) {
        if (sessionId == null || sessionId.isBlank() || summary == null || summary.isBlank()) {
            return;
        }
        // 세션이 지워진 뒤 결과가 도착할 수 있다. 요약은 부수 정보라 없으면 넘긴다.
        chatSessionRepository.findById(sessionId).ifPresent(session -> {
            session.updateContextSummary(summary, Instant.now());
            chatSessionRepository.save(session);
        });
    }

    /** Agent 결과가 도착했을 때 AI가 고른 갈래와 본문을 채운다. */
    @Transactional
    public void completeAgentTurn(String assistantMessageId, String action, String content) {
        ChatMessage assistantMessage = chatMessageRepository.findById(assistantMessageId)
                .orElseThrow(() -> new IllegalStateException(
                        "처리 중인 assistant 메시지를 찾을 수 없습니다: " + assistantMessageId));
        assistantMessage.completeAgentTurn(action, content);
        chatMessageRepository.save(assistantMessage);
    }

    @Transactional
    public void markFailed(String assistantMessageId, String errorMessage) {
        ChatMessage assistantMessage = chatMessageRepository.findById(assistantMessageId)
                .orElseThrow(() -> new IllegalStateException("처리 중인 assistant 메시지를 찾을 수 없습니다: " + assistantMessageId));
        assistantMessage.fail(errorMessage);
        chatMessageRepository.save(assistantMessage);
    }
}
