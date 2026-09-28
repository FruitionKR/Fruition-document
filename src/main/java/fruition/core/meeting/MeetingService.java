package fruition.core.meeting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.service.DocumentEditLockService;
import fruition.shared.idempotency.IdempotencyService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 회의 생성·조회와 실시간 받아쓰기 ticket 발급. 회의는 만든 사람만 볼 수 있다. */
@Service
public class MeetingService {
    static final Duration TICKET_TTL = Duration.ofSeconds(60);
    private static final String DEFAULT_NAME = "회의록";

    public record Ticket(String userId, String workspaceId, String meetingId) {}

    public record LiveTicket(String ticket, Instant expiresAt) {}

    private final MeetingRepository repository;
    private final MeetingLiveLock liveLock;
    private final WorkspaceAccessGuard accessGuard;
    private final DocumentEditLockService documentEditRules;
    private final IdempotencyService idempotencyService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public MeetingService(MeetingRepository repository, MeetingLiveLock liveLock, WorkspaceAccessGuard accessGuard,
                          DocumentEditLockService documentEditRules, IdempotencyService idempotencyService,
                          StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.repository = repository;
        this.liveLock = liveLock;
        this.accessGuard = accessGuard;
        this.documentEditRules = documentEditRules;
        this.idempotencyService = idempotencyService;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MeetingResponse create(String workspaceId, String userId, String idempotencyKey,
                                  String displayName, String source, String documentId) {
        String name = displayName == null || displayName.isBlank() ? DEFAULT_NAME : displayName.strip();
        if (name.length() > 200) {
            throw new MeetingException(HttpStatus.BAD_REQUEST, "INVALID_MEETING", "회의 이름은 200자 이하여야 합니다.");
        }
        if (!"live".equals(source) && !"upload".equals(source)) {
            throw new MeetingException(HttpStatus.BAD_REQUEST, "INVALID_MEETING", "source는 live 또는 upload여야 합니다.");
        }
        String scope = "POST:/api/workspaces/" + workspaceId + "/meetings";
        String hash = idempotencyService.requestHash(name, source, String.valueOf(documentId));
        return idempotencyService.execute(userId, scope, idempotencyKey, hash, MeetingResponse.class, 201,
                MeetingResponse::meetingId, () -> {
                    accessGuard.requireMember(workspaceId, userId);
                    if (documentId != null) {
                        documentEditRules.requireEditableOwned(workspaceId, userId, documentId);
                    }
                    MeetingRepository.Meeting meeting = new MeetingRepository.Meeting(
                            "mtg_" + UUID.randomUUID().toString().replace("-", ""), workspaceId, userId, name,
                            documentId, source, "live".equals(source) ? "open" : "awaiting_upload", Instant.now());
                    repository.insert(meeting);
                    return toResponse(meeting);
                });
    }

    @Transactional(readOnly = true)
    public MeetingResponse get(String workspaceId, String userId, String meetingId) {
        return toResponse(requireOwned(workspaceId, userId, meetingId));
    }

    public LiveTicket issueTicket(String workspaceId, String userId, String meetingId) {
        MeetingRepository.Meeting meeting = requireOwned(workspaceId, userId, meetingId);
        if (!"live".equals(meeting.source()) || !"open".equals(meeting.status())) {
            throw new MeetingException(HttpStatus.CONFLICT, "MEETING_NOT_OPEN", "실시간 받아쓰기를 시작할 수 없는 회의입니다.");
        }
        if (liveLock.isHeld(meetingId)) {
            throw new MeetingException(HttpStatus.CONFLICT, "MEETING_LIVE_IN_USE", "이미 다른 곳에서 받아쓰는 중입니다.");
        }
        String ticket = "wst_" + UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(ticketKey(ticket), write(new Ticket(userId, workspaceId, meetingId)), TICKET_TTL);
        return new LiveTicket(ticket, Instant.now().plus(TICKET_TTL));
    }

    /** handshake에서 한 번만 쓴다. 다른 회의의 ticket이거나 그사이 멤버가 아니게 됐으면 거절한다. */
    public Optional<Ticket> consumeTicket(String ticket, String meetingId) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        String raw = redis.opsForValue().getAndDelete(ticketKey(ticket));
        if (raw == null) {
            return Optional.empty();
        }
        Ticket parsed = read(raw);
        if (!parsed.meetingId().equals(meetingId)
                || "NONE".equals(accessGuard.getRole(parsed.workspaceId(), parsed.userId()))) {
            return Optional.empty();
        }
        return Optional.of(parsed);
    }

    /** 회의록 기능도 같은 소유 범위를 쓴다. 다른 사용자에게는 404로 존재를 숨긴다. */
    MeetingRepository.Meeting requireOwned(String workspaceId, String userId, String meetingId) {
        accessGuard.requireMember(workspaceId, userId);
        return repository.findOwned(meetingId, workspaceId, userId).orElseThrow(MeetingException::notFound);
    }

    private MeetingResponse toResponse(MeetingRepository.Meeting meeting) {
        boolean live = liveLock.isHeld(meeting.id());
        // 종료 사유가 비었는데 잠금도 없으면, 기록하기 전에 인스턴스가 죽은 연결이다.
        List<MeetingResponse.StreamItem> streams = repository.streams(meeting.id()).stream()
                .map(stream -> new MeetingResponse.StreamItem(stream.order(),
                        stream.endReason() == null && !live ? "interrupted" : stream.endReason()))
                .toList();
        List<MeetingResponse.SegmentItem> segments = repository.segments(meeting.id()).stream()
                .map(segment -> new MeetingResponse.SegmentItem(segment.id(), segment.position(), segment.text(),
                        segment.text() == null ? "pending" : "completed"))
                .toList();
        boolean complete = !live
                && segments.stream().noneMatch(segment -> segment.text() == null)
                && streams.stream().allMatch(stream -> "finished".equals(stream.endReason()));
        return new MeetingResponse(meeting.id(), meeting.displayName(), meeting.documentId(), meeting.source(),
                meeting.status(), live, complete, streams, segments, meeting.createdAt());
    }

    private String write(Ticket ticket) {
        try {
            return objectMapper.writeValueAsString(ticket);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Ticket read(String raw) {
        try {
            return objectMapper.readValue(raw, Ticket.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String ticketKey(String ticket) {
        return "speech:ticket:" + ticket;
    }
}
