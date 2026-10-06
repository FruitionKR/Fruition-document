package fruition.core.meeting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.exception.DocumentLockedException;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import fruition.core.document.exception.EditLockLostException;
import fruition.core.document.exception.InvalidDocumentFilenameException;
import fruition.core.document.exception.InvalidMarkdownContentException;
import fruition.core.document.exception.MarkdownContentTooLargeException;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.repository.PostgresDocumentEditStore;
import fruition.core.document.service.DocumentEditLockService;
import fruition.core.document.service.DocumentService;
import fruition.core.document.service.AiMarkdownSanitizer;
import fruition.shared.util.DuplicateResourceName;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 회의록 초안 생성·조회와 문서 저장.
 *
 * <p>AI 호출은 요청 스레드·트랜잭션 밖에서 하고 결과는 자기 버전 행에만 쓴다. 생성 요청은 {@code generating} 버전을
 * 만들고 바로 끝나며(202), 클라이언트는 조회로 결과를 기다린다. 저장은 기존 문서 생성·본문 저장 함수를 그대로 쓰며,
 * 저장 전에 대상·본문을 기록해 같은 요청의 재시도가 같은 저장을 반복하게 한다(중복 생성·중복 추가 없음).
 */
@Service
public class MeetingNotesService {
    static final Duration GENERATION_TIMEOUT = Duration.ofMinutes(5);
    /**
     * 확정 구간을 약 1,000자까지 묶어 보낸다. 실시간 받아쓰기는 발화 하나가 구간 하나라 긴 회의가 회의록 AI의
     * 1,000구간 한도를 넘기 때문이다. 저장·순서·화면 표시는 발화 단위를 그대로 쓴다(ADR-0023 결정 7).
     */
    static final int BUNDLE_CHARS = 1_000;
    private static final Logger log = LoggerFactory.getLogger(MeetingNotesService.class);
    private static final String[][] SECTIONS = {
            {"summary", "요약"}, {"decisions", "결정 사항"}, {"action_items", "할 일"}, {"open_questions", "미결 사항"}};

    private final MeetingService meetingService;
    private final MeetingNotesRepository notes;
    private final MeetingNotesClient client;
    private final DocumentService documentService;
    private final DocumentEditLockService documentEditRules;
    private final PostgresDocumentEditStore editStore;
    private final ObjectMapper objectMapper;
    /**
     * 생성은 요청 스레드 밖에서 한다. 한 Pod에서 동시 4건까지만 만들고 큐를 두지 않아, 넘치면 기다리지 않고 바로
     * 거절해 그 버전을 {@code failed}로 남긴다(generating으로 멈춰 있는 행을 만들지 않는다).
     */
    private final ThreadPoolExecutor generator = new ThreadPoolExecutor(0, 4, 60, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "meeting-notes-gen");
                thread.setDaemon(true);
                return thread;
            });

    /** 종료 시 진행 중 생성은 버린다. 남은 generating은 조회의 5분 정리가 failed로 바꾼다. */
    @PreDestroy
    void shutdownGenerator() {
        generator.shutdownNow();
    }

    public MeetingNotesService(MeetingService meetingService, MeetingNotesRepository notes, MeetingNotesClient client,
                               DocumentService documentService, DocumentEditLockService documentEditRules,
                               PostgresDocumentEditStore editStore, ObjectMapper objectMapper) {
        this.meetingService = meetingService;
        this.notes = notes;
        this.client = client;
        this.documentService = documentService;
        this.documentEditRules = documentEditRules;
        this.editStore = editStore;
        this.objectMapper = objectMapper;
    }

    public record AppendPreview(String documentId, long baseRevision, String markdown) {}

    public record ApplyRequest(String mode, String displayName, String markdown, UUID folderId,
                               String documentId, Long baseRevision) {}

    public MeetingNotesResponse generate(String workspaceId, String userId, String meetingId,
                                         String requestId, boolean allowPartial) {
        requireKey(requestId);
        MeetingResponse meeting = meetingService.get(workspaceId, userId, meetingId);
        var replay = notes.findByRequest(meetingId, requestId);
        if (replay.isPresent()) {
            return toResponse(replay.get());
        }
        if (meeting.liveConnected()) {
            throw new MeetingException(HttpStatus.CONFLICT, "MEETING_LIVE_IN_USE", "받아쓰기가 끝난 뒤 회의록을 만들 수 있습니다.");
        }
        List<Bundle> bundles = bundles(meeting.segments());
        List<Map<String, String>> segments = bundles.stream()
                .map(bundle -> Map.of("id", bundle.id(), "text", bundle.text()))
                .toList();
        if (segments.isEmpty()) {
            throw new MeetingException(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_TRANSCRIPT_EMPTY", "확정된 전사가 없습니다.");
        }
        if (!meeting.transcriptComplete() && !allowPartial) {
            throw new MeetingException(HttpStatus.CONFLICT, "MEETING_TRANSCRIPT_INCOMPLETE",
                    "누락되었거나 정상 종료되지 않은 녹음이 있습니다. 일부 전사로 만들려면 allow_partial=true로 요청하세요.");
        }
        boolean partial = !meeting.transcriptComplete();
        int version = notes.insertGenerating(meetingId, requestId, partial, json(segments))
                .orElseGet(() -> notes.findByRequest(meetingId, requestId)
                        .map(MeetingNotesRepository.Note::version)
                        .orElseThrow(() -> new MeetingException(HttpStatus.CONFLICT, "MEETING_NOTES_BUSY",
                                "다른 회의록 생성 요청과 겹쳤습니다. 다시 시도해 주세요.")));
        var current = notes.find(meetingId, version).orElseThrow();
        if (!"generating".equals(current.status())) {
            return toResponse(current);  // 같은 요청 ID의 동시 재시도
        }
        try {
            generator.execute(() -> generate(workspaceId, userId, meeting.displayName(), meetingId, version,
                    segments, bundles));
        } catch (RejectedExecutionException e) {
            notes.markFailed(meetingId, version, "MEETING_NOTES_BUSY");
            return toResponse(notes.find(meetingId, version).orElseThrow());
        }
        return toResponse(current);
    }

    /**
     * 배경에서 초안을 만든다. {@code markReady}·{@code markFailed}는 {@code generating}인 자기 버전 행만 바꾸므로
     * 늦게 끝난 이전 생성이 새 초안이나 정리된 행을 덮어쓰지 않는다. 실패는 그 버전의 {@code failed}로만 알린다.
     */
    private void generate(String workspaceId, String userId, String displayName, String meetingId, int version,
                          List<Map<String, String>> segments, List<Bundle> bundles) {
        try {
            JsonNode draft = client.preview(workspaceId, userId, displayName, segments);
            notes.markReady(meetingId, version, json(stored(draft, bundles)));
        } catch (MeetingException e) {
            notes.markFailed(meetingId, version, e.getCode());
        } catch (RuntimeException e) {
            log.warn("[회의록 초안 생성 실패] meetingId={} version={}", meetingId, version, e);
            notes.markFailed(meetingId, version, "MEETING_NOTES_FAILED");
        }
    }

    public MeetingNotesResponse latest(String workspaceId, String userId, String meetingId) {
        meetingService.requireOwned(workspaceId, userId, meetingId);
        notes.expireGenerating(meetingId, Instant.now().minus(GENERATION_TIMEOUT));
        return toResponse(notes.latest(meetingId).orElseThrow(() ->
                new MeetingException(HttpStatus.NOT_FOUND, "MEETING_NOTES_NOT_FOUND", "회의록 초안이 없습니다.")));
    }

    public AppendPreview appendPreview(String workspaceId, String userId, String meetingId, int version,
                                       String documentId, String editedMarkdown) {
        MeetingRepository.Meeting meeting = meetingService.requireOwned(workspaceId, userId, meetingId);
        MeetingNotesRepository.Note note = requireLatestReady(meetingId, version);
        String target = targetDocument(documentId, meeting);
        documentEditRules.requireEditableOwned(workspaceId, userId, target);
        DocumentEditState state = editState(target);
        return new AppendPreview(target, state.getRevision(),
                merge(state.getMarkdown(), body(note, editedMarkdown)));
    }

    public MeetingNotesResponse apply(String workspaceId, String userId, String meetingId, int version,
                                      String requestId, ApplyRequest request) {
        requireKey(requestId);
        MeetingRepository.Meeting meeting = meetingService.requireOwned(workspaceId, userId, meetingId);
        MeetingNotesRepository.Note note = notes.find(meetingId, version).orElseThrow(() ->
                new MeetingException(HttpStatus.NOT_FOUND, "MEETING_NOTES_NOT_FOUND", "회의록 초안이 없습니다."));
        if (note.applyRequestId() != null) {
            if (!note.applyRequestId().equals(requestId)) {
                throw new MeetingException(HttpStatus.CONFLICT, "MEETING_NOTES_ALREADY_APPLIED", "이미 저장한 회의록 초안입니다.");
            }
            if (!sameRequest(note, request, meeting)) {
                throw new MeetingException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT",
                        "같은 Idempotency-Key로 다른 저장 요청을 보냈습니다.");
            }
            return save(workspaceId, userId, note);  // 같은 요청의 재시도: 기록해 둔 대상·본문으로 같은 저장을 반복한다
        }
        requireLatestReady(meetingId, version);
        String mode = request.mode();
        if ("create".equals(mode)) {
            String name = request.displayName() != null && !request.displayName().isBlank()
                    ? request.displayName().strip() : result(note).path("display_name").asText();
            claim(note, requestId, mode, null, null, name, request.folderId(), body(note, request.markdown()));
        } else if ("append".equals(mode)) {
            String target = targetDocument(request.documentId(), meeting);
            if (request.baseRevision() == null) {
                throw new MeetingException(HttpStatus.BAD_REQUEST, "INVALID_MEETING_NOTES_APPLY", "base_revision이 필요합니다.");
            }
            documentEditRules.requireEditableOwned(workspaceId, userId, target);
            DocumentEditState state = editState(target);
            if (state.getRevision() != request.baseRevision()) {
                throw new MeetingException(HttpStatus.CONFLICT, "DOCUMENT_REVISION_CHANGED",
                        "미리보기 이후 문서가 바뀌었습니다. 새 미리보기를 확인해 주세요.");
            }
            claim(note, requestId, mode, target, request.baseRevision(), null, null,
                    merge(state.getMarkdown(), body(note, request.markdown())));
        } else {
            throw new MeetingException(HttpStatus.BAD_REQUEST, "INVALID_MEETING_NOTES_APPLY", "mode는 create 또는 append여야 합니다.");
        }
        return save(workspaceId, userId, notes.find(meetingId, version).orElseThrow());
    }

    /**
     * 재시도가 처음 기록한 요청과 같은지 본다(기존 IdempotencyService와 같은 규칙). append의 기록 본문은
     * 기존 본문과 합친 결과라, 요청한 회의록 본문으로 끝나는지로 비교한다.
     */
    private boolean sameRequest(MeetingNotesRepository.Note note, ApplyRequest request, MeetingRepository.Meeting meeting) {
        if (!Objects.equals(note.applyMode(), request.mode())) {
            return false;
        }
        String notesBody = body(note, request.markdown());
        if ("create".equals(request.mode())) {
            String name = request.displayName() != null && !request.displayName().isBlank()
                    ? request.displayName().strip() : result(note).path("display_name").asText();
            return Objects.equals(note.applyDisplayName(), name) && Objects.equals(note.applyFolderId(), request.folderId())
                    && Objects.equals(note.applyMarkdown(), notesBody);
        }
        String target = request.documentId() != null && !request.documentId().isBlank()
                ? request.documentId() : meeting.documentId();
        return Objects.equals(note.applyDocumentId(), target)
                && Objects.equals(note.applyBaseRevision(), request.baseRevision())
                && note.applyMarkdown() != null && note.applyMarkdown().endsWith(notesBody + "\n");
    }

    private void claim(MeetingNotesRepository.Note note, String requestId, String mode, String documentId,
                       Long baseRevision, String displayName, UUID folderId, String markdown) {
        if (!notes.claimApply(note.meetingId(), note.version(), requestId, mode, documentId, baseRevision,
                displayName, folderId, markdown)) {
            throw new MeetingException(HttpStatus.CONFLICT, "MEETING_NOTES_ALREADY_APPLIED", "이미 저장 중이거나 저장한 회의록 초안입니다.");
        }
    }

    /**
     * 기록된 요청대로 저장한다. 기존 저장 함수의 멱등성(생성 멱등 키, revision_write_id)으로 재시도가 같은 결과를 돌려준다.
     * 문서가 바뀌지 않은 거절이면 선점을 풀어 새 요청으로 다시 저장할 수 있게 한다.
     */
    private MeetingNotesResponse save(String workspaceId, String userId, MeetingNotesRepository.Note note) {
        String saveKey = UUID.nameUUIDFromBytes((note.meetingId() + ":" + note.version() + ":" + note.applyRequestId())
                .getBytes(StandardCharsets.UTF_8)).toString();
        String documentId;
        try {
            if ("create".equals(note.applyMode())) {
                documentId = documentService.createMarkdown(workspaceId, userId, saveKey,
                        new MarkdownDocumentCreateRequest(note.applyDisplayName(), note.applyMarkdown(),
                                note.applyFolderId())).id();
            } else {
                // Agent 적용 표·source를 쓰지 않는다. 사용자가 수락한 본문 저장 경로다.
                documentService.saveContent(workspaceId, userId, note.applyDocumentId(), note.applyMarkdown(),
                        note.applyBaseRevision(), saveKey, null);
                documentId = note.applyDocumentId();
            }
        } catch (RuntimeException e) {
            if (!"applied".equals(note.status()) && isRejection(e)) {
                notes.releaseApply(note.meetingId(), note.version(), note.applyRequestId());
            }
            throw e;
        }
        notes.markApplied(note.meetingId(), note.version(), note.applyRequestId(), documentId);
        return toResponse(notes.find(note.meetingId(), note.version()).orElseThrow());
    }

    /**
     * 문서가 바뀌지 않았음이 확실한 거절만 선점을 푼다(revision 충돌·권한·잠금·본문 검증·이름 중복).
     * 연결·서버 오류는 저장 여부를 알 수 없으므로 선점을 유지하고 같은 요청의 재시도에 맡긴다.
     */
    private static boolean isRejection(RuntimeException e) {
        return e instanceof DocumentVersionConflictException || e instanceof DocumentNotFoundException
                || e instanceof DocumentWriteForbiddenException || e instanceof DocumentLockedException
                || e instanceof EditLockLostException || e instanceof InvalidMarkdownContentException
                || e instanceof MarkdownContentTooLargeException || e instanceof InvalidDocumentFilenameException
                || DuplicateResourceName.message(e) != null;
    }

    /**
     * 사용자가 본 버전이 마지막으로 성공한 초안이어야 한다. 더 새 초안이 있으면 거절한다.
     * 다시 만들기가 실패·진행 중이어도 마지막 성공 초안은 그대로 저장할 수 있다.
     */
    private MeetingNotesRepository.Note requireLatestReady(String meetingId, int version) {
        MeetingNotesRepository.Note note = notes.find(meetingId, version).orElseThrow(() ->
                new MeetingException(HttpStatus.NOT_FOUND, "MEETING_NOTES_NOT_FOUND", "회의록 초안이 없습니다."));
        if ("applied".equals(note.status())) {
            throw new MeetingException(HttpStatus.CONFLICT, "MEETING_NOTES_ALREADY_APPLIED", "이미 저장한 회의록 초안입니다.");
        }
        if (!"ready".equals(note.status()) || !Objects.equals(notes.lastReadyVersion(meetingId).orElse(null), version)) {
            throw new MeetingException(HttpStatus.CONFLICT, "MEETING_NOTES_OUTDATED", "최신 회의록 초안을 확인해 주세요.");
        }
        return note;
    }

    private static String targetDocument(String documentId, MeetingRepository.Meeting meeting) {
        String target = documentId != null && !documentId.isBlank() ? documentId : meeting.documentId();
        if (target == null) {
            throw new MeetingException(HttpStatus.BAD_REQUEST, "INVALID_MEETING_NOTES_APPLY", "회의록을 추가할 문서를 지정해 주세요.");
        }
        return target;
    }

    private DocumentEditState editState(String documentId) {
        return editStore.findState(documentId).orElseThrow(() ->
                new MeetingException(HttpStatus.CONFLICT, "DOCUMENT_NOT_EDITABLE", "편집 가능한 Markdown 본문을 찾을 수 없습니다."));
    }

    /** 미리보기·저장·재시도 비교가 모두 이 본문을 쓴다. AI 초안이라 원시 HTML·위험한 링크를 걷어낸다. */
    private String body(MeetingNotesRepository.Note note, String editedMarkdown) {
        return AiMarkdownSanitizer.sanitize(
                editedMarkdown != null && !editedMarkdown.isBlank() ? editedMarkdown.strip() : render(result(note)));
    }

    /** 기존 본문 끝에 빈 줄 하나를 두고 붙인다. 미리보기와 저장이 같은 규칙을 쓴다. */
    static String merge(String current, String notesMarkdown) {
        String base = current == null ? "" : current.stripTrailing();
        return base.isEmpty() ? notesMarkdown + "\n" : base + "\n\n" + notesMarkdown + "\n";
    }

    /** AI 응답의 markdown은 근거 ID를 괄호로 붙이므로 쓰지 않고 항목 배열로 다시 만든다. */
    static String render(JsonNode result) {
        StringBuilder markdown = new StringBuilder("# ").append(result.path("display_name").asText("회의록").strip());
        for (String[] section : SECTIONS) {
            markdown.append("\n\n## ").append(section[1]);
            JsonNode items = result.path(section[0]);
            if (!items.isArray() || items.isEmpty()) {
                markdown.append("\n- 확인된 내용 없음");
            }
            for (JsonNode item : items) {
                markdown.append("\n- ").append(item.path("text").asText().strip());
            }
        }
        return markdown.toString();
    }

    /**
     * AI에 보낼 묶음 하나. {@code id}는 묶인 첫 구간의 ID라, AI가 돌려준 근거 ID도 실제 저장된 구간을 가리킨다.
     */
    record Bundle(String id, String text, List<String> segmentIds) {}

    /**
     * 확정 구간을 발화 순서대로 약 {@link #BUNDLE_CHARS}자까지 이어 묶는다. 이어진 두 묶음의 합은 항상 이 길이를
     * 넘으므로 100,000자 전사는 묶음 약 100개 이하가 되어 회의록 AI의 1,000구간 한도에 걸리지 않는다.
     * 이미 약 1,000자 단위로 저장된 파일 전사는 묶이지 않고 그대로 간다.
     */
    static List<Bundle> bundles(List<MeetingResponse.SegmentItem> segments) {
        List<Bundle> result = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<String> ids = new ArrayList<>();
        for (MeetingResponse.SegmentItem segment : segments) {
            if (segment.text() == null || segment.text().isBlank()) {
                continue;
            }
            String sentence = segment.text().strip();
            if (!ids.isEmpty() && text.length() + 1 + sentence.length() > BUNDLE_CHARS) {
                result.add(new Bundle(ids.get(0), text.toString(), List.copyOf(ids)));
                text.setLength(0);
                ids.clear();
            }
            if (!ids.isEmpty()) {
                text.append(' ');
            }
            text.append(sentence);
            ids.add(segment.id());
        }
        if (!ids.isEmpty()) {
            result.add(new Bundle(ids.get(0), text.toString(), List.copyOf(ids)));
        }
        return result;
    }

    /**
     * 저장할 결과: 이름과 네 배열만 둔다(AI markdown은 버린다). 근거는 묶음 ID로 돌아오므로 묶인 원래 구간
     * ID들로 되돌려 저장한다. 모르는 ID는 그대로 둔다.
     */
    private ObjectNode stored(JsonNode draft, List<Bundle> bundles) {
        Map<String, List<String>> sources = new LinkedHashMap<>();
        bundles.forEach(bundle -> sources.put(bundle.id(), bundle.segmentIds()));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("display_name", draft.path("display_name").asText("회의록"));
        for (String[] section : SECTIONS) {
            ArrayNode items = objectMapper.createArrayNode();
            for (JsonNode item : draft.path(section[0])) {
                LinkedHashSet<String> refs = new LinkedHashSet<>();
                item.path("source_segment_ids").forEach(ref ->
                        refs.addAll(sources.getOrDefault(ref.asText(), List.of(ref.asText()))));
                ArrayNode ids = objectMapper.createArrayNode();
                refs.forEach(ids::add);
                items.addObject().put("text", item.path("text").asText()).set("source_segment_ids", ids);
            }
            result.set(section[0], items);
        }
        return result;
    }

    private JsonNode result(MeetingNotesRepository.Note note) {
        try {
            return note.result() == null ? objectMapper.createObjectNode() : objectMapper.readTree(note.result());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private MeetingNotesResponse toResponse(MeetingNotesRepository.Note note) {
        JsonNode result = result(note);
        boolean hasResult = note.result() != null;
        Integer lastReady = "failed".equals(note.status())
                ? notes.lastReadyVersion(note.meetingId()).orElse(null) : null;
        MeetingNotesResponse.Applied applied = "applied".equals(note.status())
                ? new MeetingNotesResponse.Applied(note.applyMode(), note.applyDocumentId(), note.appliedAt()) : null;
        return new MeetingNotesResponse(note.meetingId(), note.version(), note.status(), note.partial(),
                hasResult ? result.path("display_name").asText() : null,
                hasResult ? render(result) : null,
                items(result, "summary"), items(result, "decisions"), items(result, "action_items"),
                items(result, "open_questions"), applied, note.errorCode(), lastReady);
    }

    private static List<MeetingNotesResponse.Item> items(JsonNode result, String key) {
        List<MeetingNotesResponse.Item> items = new ArrayList<>();
        for (JsonNode item : result.path(key)) {
            List<String> refs = new ArrayList<>();
            item.path("source_segment_ids").forEach(ref -> refs.add(ref.asText()));
            items.add(new MeetingNotesResponse.Item(item.path("text").asText(), refs));
        }
        return items;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new MeetingException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key는 1자 이상 255자 이하여야 합니다.");
        }
    }
}
