package fruition.core.chat.controller;

import fruition.core.chat.domain.ChatMessageReference;
import fruition.core.chat.domain.ChatPartialWiki;
import fruition.core.chat.dto.ChatMessageRelatedPageResponse;
import fruition.core.chat.dto.ChatMessageResponse;
import fruition.core.chat.dto.ChatMessagesResponse;
import fruition.core.chat.dto.ChatSessionCreateRequest;
import fruition.core.chat.dto.ChatSessionListResponse;
import fruition.core.chat.dto.ChatSessionRenameRequest;
import fruition.core.chat.dto.ChatSessionResponse;
import fruition.core.chat.repository.ChatMessageReferenceRepository;
import fruition.core.chat.repository.ChatMessageRelatedPageRepository;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.repository.ChatPartialWikiRepository;
import fruition.core.chat.service.ChatSessionService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/chat/sessions")
@Tag(name = "Chat Sessions", description = "채팅 세션 및 메시지 기록 API")
public class ChatSessionController {

    private final ChatSessionService chatSessionService;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageReferenceRepository referenceRepository;
    private final ChatMessageRelatedPageRepository relatedPageRepository;
    private final ChatPartialWikiRepository chatPartialWikiRepository;

    public ChatSessionController(ChatSessionService chatSessionService,
                                 ChatMessageRepository chatMessageRepository,
                                 ChatMessageReferenceRepository referenceRepository,
                                 ChatMessageRelatedPageRepository relatedPageRepository,
                                 ChatPartialWikiRepository chatPartialWikiRepository) {
        this.chatSessionService = chatSessionService;
        this.chatMessageRepository = chatMessageRepository;
        this.referenceRepository = referenceRepository;
        this.relatedPageRepository = relatedPageRepository;
        this.chatPartialWikiRepository = chatPartialWikiRepository;
    }

    @Operation(summary = "채팅 세션 생성", description = "워크스페이스당 최대 10개까지 생성할 수 있습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "생성 성공",
            content = @Content(schema = @Schema(implementation = ChatSessionResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "세션 개수 제한 초과",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<ChatSessionResponse> create(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @RequestBody(required = false) ChatSessionCreateRequest request) {
        ChatSessionCreateRequest safeRequest = request != null ? request : new ChatSessionCreateRequest(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(chatSessionService.create(workspaceId, userId, safeRequest));
    }

    @Operation(summary = "채팅 세션 목록 조회", description = "가장 최근 메시지 순으로 정렬해 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = ChatSessionListResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<ChatSessionListResponse> list(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(chatSessionService.list(workspaceId, userId));
    }

    @Operation(summary = "채팅 세션 제목 변경", description = "지정한 채팅 세션의 제목을 변경합니다. 목록 정렬 기준인 last_message_at은 바뀌지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "변경 성공",
            content = @Content(schema = @Schema(implementation = ChatSessionResponse.class))),
        @ApiResponse(responseCode = "400", description = "제목이 비었거나 255자를 초과함",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "세션 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{session_id}")
    public ResponseEntity<ChatSessionResponse> rename(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "채팅 세션 ID", example = "session_abc12345")
            @PathVariable("session_id") String sessionId,
            @Valid @RequestBody ChatSessionRenameRequest request) {
        return ResponseEntity.ok(chatSessionService.rename(workspaceId, userId, sessionId, request));
    }

    @Operation(summary = "채팅 세션 삭제", description = "워크스페이스에서 지정한 채팅 세션과 해당 세션의 메시지 기록을 삭제합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "삭제 성공"),
        @ApiResponse(responseCode = "404", description = "세션 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{session_id}")
    public ResponseEntity<Void> delete(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "채팅 세션 ID", example = "session_abc12345")
            @PathVariable("session_id") String sessionId) {
        chatSessionService.delete(workspaceId, userId, sessionId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "채팅 메시지 기록 조회", description = "세션 내 채팅 메시지를 생성 순서대로 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = ChatMessagesResponse.class))),
        @ApiResponse(responseCode = "404", description = "세션 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{session_id}/messages")
    public ResponseEntity<ChatMessagesResponse> getMessages(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "채팅 세션 ID", example = "session_abc12345")
            @PathVariable("session_id") String sessionId) {
        chatSessionService.verifyOwnedSession(workspaceId, userId, sessionId);

        var messages = chatMessageRepository.findAllBySessionIdInTurnOrder(sessionId);
        List<String> messageIds = messages.stream().map(m -> m.getId()).toList();

        // N+1 방지: 메시지 ID 목록으로 references와 related_pages를 한 번에 조회한다.
        Map<String, List<ChatMessageReference>> refsByMessageId = referenceRepository
                .findAllByChatMessage_IdIn(messageIds).stream()
                .collect(Collectors.groupingBy(ChatMessageReference::getChatMessageId));

        Map<String, List<ChatMessageRelatedPageResponse>> relatedPagesByMessageId = relatedPageRepository
                .findAllByChatMessage_IdIn(messageIds).stream()
                .collect(Collectors.groupingBy(
                        p -> p.getChatMessageId(),
                        Collectors.mapping(p -> new ChatMessageRelatedPageResponse(
                                p.getWikiPageId(), p.getPageType(), p.getTitle(), p.getSlug(),
                                p.getRelevanceScore() != null ? p.getRelevanceScore() : 0.0,
                                p.getRole(),
                                p.getDepth() != null ? p.getDepth() : 0,
                                p.getRank() != null ? p.getRank() : 0
                        ), Collectors.toList())
                ));

        // 문답별 위키 페이지 목록(1:N). 같은 문답을 여러 번 내보내면 page가 늘어난다.
        Map<String, List<String>> partialPagesByPairId = chatPartialWikiRepository.findAllBySessionId(sessionId).stream()
                .collect(Collectors.groupingBy(
                        ChatPartialWiki::getPairId,
                        Collectors.mapping(ChatPartialWiki::getWikiPageId, Collectors.toList())));

        List<ChatMessageResponse> responses = messages.stream()
                .map(m -> {
                    var refs = refsByMessageId.getOrDefault(m.getId(), List.of()).stream()
                            .map(r -> new fruition.core.chat.dto.ChatMessageReference(
                                    r.getId(), r.getReferenceType(),
                                    r.getRank(), r.getDocumentId(),
                                    r.getSourceBlockIds(),
                                    r.getQuote(),
                                    r.getSourceRefs()
                            ))
                            .toList();
                    var relatedPages = relatedPagesByMessageId.getOrDefault(m.getId(), List.of());
                    var partialWikiPageIds = partialPagesByPairId.getOrDefault(m.getPairId(), List.of());
                    return new ChatMessageResponse(m.getId(), m.getPairId(), m.getRole(), m.getContent(), m.getStatus(), m.getCreatedAt(),
                            relatedPages, refs, partialWikiPageIds, m.getErrorMessage(),
                            m.getAiProvider(), m.getAiModel(), m.getWebSearchEnabled(),
                            m.getRunId(), m.getAction());
                })
                .toList();

        return ResponseEntity.ok(new ChatMessagesResponse(responses));
    }
}
