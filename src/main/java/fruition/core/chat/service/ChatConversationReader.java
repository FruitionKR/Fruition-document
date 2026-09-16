package fruition.core.chat.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.repository.ChatMessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI에게 넘길 대화 맥락을 세션에서 읽어 조립한다.
 *
 * <p>맥락은 두 겹이다. 요약은 pipeline이 턴마다 갱신해 세션에 쌓아 둔 누적본이고,
 * 최근 메시지는 그 위에 얹는 원문이다. 요약이 앞쪽 대화를 붙들고 있어 대화가 길어져도 맥락이 끊기지 않는다.
 *
 * <p>어떤 문답을 원문으로 쓸지는 사용자가 화면에서 고르지만, 어떤 형식으로 pipeline에 넘길지는 서버가 정한다.
 * 클라이언트가 만든 문자열을 그대로 실어 보내면 내용이 실제 대화와 다를 수 있고 형식도 갈린다.
 *
 * <p>선택한 pair는 항상 세션 안에서만 찾는다. 남의 세션 pair ID를 넣어도 조회되지 않는다.
 */
@Service
public class ChatConversationReader {

    /** pipeline이 받는 상한과 같다. 더 보내도 잘린다. */
    private static final int MAX_RECENT_MESSAGES = 6;
    /**
     * 최근 구간에서 읽어 올 메시지 수. 실제로 쓰는 건 6개지만, 완결되지 않았거나 내용이 빈 문답이
     * 섞여 있어 여유를 둔다. 세션 전체를 훑지 않게 하려는 상한이라 넉넉해도 된다.
     */
    private static final int RECENT_MESSAGE_WINDOW = 40;
    private static final int MAX_MESSAGE_CONTENT_LENGTH = 2000;

    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionService chatSessionService;

    public ChatConversationReader(ChatMessageRepository chatMessageRepository,
                                  ChatSessionService chatSessionService) {
        this.chatMessageRepository = chatMessageRepository;
        this.chatSessionService = chatSessionService;
    }

    /**
     * @param selectedPairIds 사용자가 고른 문답. 비어 있으면 세션의 최근 완결 문답을 쓴다.
     */
    @Transactional(readOnly = true)
    public Conversation read(String sessionId, List<String> selectedPairIds) {
        // 고른 문답이 있으면 그것만, 없으면 최근 구간만 읽는다. 어느 쪽도 세션 전체를 훑지 않는다.
        List<ChatMessage> messages = selectedPairIds == null || selectedPairIds.isEmpty()
                ? recentMessages(sessionId)
                : chatMessageRepository.findByPairIdsInTurnOrder(sessionId, selectedPairIds);
        Set<String> included = completePairIds(messages);
        // 순서는 리포지터리가 보장한다. 걸러내도 순서는 유지된다.
        List<ChatMessage> ordered = messages.stream()
                .filter(message -> included.contains(message.getPairId()))
                .toList();
        List<ChatMessage> recent = ordered.subList(Math.max(0, ordered.size() - MAX_RECENT_MESSAGES), ordered.size());

        return new Conversation(
                recent.stream().map(message -> new Message(
                        message.getRole(), truncate(message.getContent()), message.getAction(),
                        message.getRunId())).toList(),
                // 요약은 서버가 만들지 않는다. pipeline이 갱신해 세션에 쌓아 둔 것을 그대로 넘긴다.
                chatSessionService.contextSummary(sessionId));
    }

    /** 최근 구간을 대화 순서로 되돌려 읽는다. 상한에 걸려 반쪽만 들어온 문답은 뒤에서 걸러진다. */
    private List<ChatMessage> recentMessages(String sessionId) {
        List<ChatMessage> reversed = chatMessageRepository.findRecentBySessionId(
                sessionId, PageRequest.of(0, RECENT_MESSAGE_WINDOW));
        List<ChatMessage> ordered = new ArrayList<>(reversed);
        Collections.reverse(ordered);
        return ordered;
    }

    /**
     * user·assistant가 하나씩이고 둘 다 완료된 문답만 맥락으로 쓴다.
     *
     * <p>내용이 빈 메시지가 하나라도 있으면 그 문답은 통째로 뺀다. pipeline은 메시지 내용이
     * 1자 이상이라야 받고, 빈 내용이 섞이면 요청 전체를 거부한다.
     */
    private static Set<String> completePairIds(List<ChatMessage> messages) {
        return messages.stream()
                .collect(Collectors.groupingBy(ChatMessage::getPairId))
                .entrySet().stream()
                .filter(pair -> pair.getValue().size() == 2)
                .filter(pair -> pair.getValue().stream().allMatch(message -> "completed".equals(message.getStatus())))
                .filter(pair -> pair.getValue().stream()
                        .noneMatch(message -> message.getContent() == null || message.getContent().isBlank()))
                .filter(pair -> pair.getValue().stream().filter(message -> "user".equals(message.getRole())).count() == 1)
                .filter(pair -> pair.getValue().stream().filter(message -> "assistant".equals(message.getRole())).count() == 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private static String truncate(String content) {
        return content.length() <= MAX_MESSAGE_CONTENT_LENGTH
                ? content
                : content.substring(0, MAX_MESSAGE_CONTENT_LENGTH);
    }

    public record Conversation(List<Message> recentMessages, String summary) {}

    public record Message(String role, String content, String action, String runId) {}
}
