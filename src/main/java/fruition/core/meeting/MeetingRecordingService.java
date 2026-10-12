package fruition.core.meeting;

import fruition.core.usage.service.CreditService;
import fruition.shared.util.DirectMultipartUpload;
import fruition.shared.util.MultipartStorage;
import fruition.shared.util.StorageProperties;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.media.Schema;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 회의 녹음 원본의 업로드·재생 URL·회의 삭제. 원본은 회의가 삭제될 때까지 보관한다.
 *
 * <p>브라우저가 S3에 multipart로 직접 올리고({@link DirectMultipartUpload}) 서버는 확정만 한다.
 * 녹음 파일 회의는 확정하면 전사 대기(transcribing)가 되고 {@link MeetingTranscriptionService}가 AI에 전사를 맡긴다.
 * 실시간 회의는 받아쓰기가 이미 끝났으므로 원본만 보관한다.
 */
@Service
public class MeetingRecordingService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MeetingRecordingService.class);
    /** 22 kHz 모노 16비트 WAV 3시간(약 475 MB)을 담는 크기. 길이 한도(3시간)는 AI가 확인한다. */
    static final long MAX_BYTES = 500L * 1024 * 1024;
    static final Duration URL_TTL = Duration.ofMinutes(5);
    private static final Map<String, String> EXTENSIONS = Map.of(
            "audio/wav", "wav", "audio/x-wav", "wav", "audio/mpeg", "mp3",
            "audio/mp4", "m4a", "audio/x-m4a", "m4a", "audio/webm", "webm");

    public record RecordingUrl(String url, Instant expiresAt) {}
    @Schema(name = "MeetingRecordingUploadStartRequest")
    public record StartRequest(String content_type, long size) {}
    @Schema(name = "MeetingRecordingUploadStartResponse")
    public record StartResponse(String ticket, long part_size, int part_count) {}
    @Schema(name = "MeetingRecordingUploadPartsRequest")
    public record PartsRequest(String ticket, int first_part, int count) {}
    @Schema(name = "MeetingRecordingUploadPartUrl")
    public record PartUrl(int part_number, String url) {}
    @Schema(name = "MeetingRecordingUploadPartsResponse")
    public record PartsResponse(List<PartUrl> parts) {}
    @Schema(name = "MeetingRecordingUploadCompleteRequest")
    public record CompleteRequest(String ticket) {}

    private final MeetingService meetingService;
    private final MeetingRepository repository;
    private final MeetingLiveLock liveLock;
    private final MinioClient minio;
    private final StorageProperties storage;
    private final DirectMultipartUpload upload;
    private final MeetingTranscriptionService transcription;
    private final TransactionTemplate transaction;
    private final CreditService credits;

    public MeetingRecordingService(MeetingService meetingService, MeetingRepository repository,
                                   MeetingLiveLock liveLock, MinioClient minio, StorageProperties storage,
                                   MultipartStorage multipart, MeetingTranscriptionService transcription,
                                   TransactionTemplate transaction, CreditService credits, @Value("${app.jwt.secret}") String secret) throws Exception {
        this.meetingService = meetingService;
        this.repository = repository;
        this.liveLock = liveLock;
        this.minio = minio;
        this.storage = storage;
        this.upload = new DirectMultipartUpload(minio, multipart, storage, secret, "meeting-recording-ticket");
        this.transcription = transcription;
        this.transaction = transaction;
        this.credits = credits;
    }

    public StartResponse start(String workspaceId, String userId, String meetingId, StartRequest request) throws Exception {
        MeetingRepository.Meeting meeting = meetingService.requireOwned(workspaceId, userId, meetingId);
        String contentType = audioType(request.content_type());
        requireSize(request.size(), request.size());
        requireUploadable(meeting);
        // 500 MiB를 올린 뒤에야 거절되지 않도록 전사를 시작할 수 있는 잔액인지 먼저 본다(예약은 확정 때).
        if ("upload".equals(meeting.source())) {
            credits.checkAdmission(userId, "meeting_transcription");
        }
        // 업로드마다 새 키에 쓴다. 겹친 업로드가 전사 중인 원본을 덮어쓰지 않고, 교체된 이전 원본은 확정 때 지운다.
        String key = "meetings/" + meetingId + "/recording-" + UUID.randomUUID() + "." + EXTENSIONS.get(contentType);
        var started = upload.start(userId, workspaceId, key, contentType, request.size(),
                Map.of("meeting", meetingId, "contentType", contentType));
        return new StartResponse(started.ticket(), started.partSize(), started.partCount());
    }

    public PartsResponse partUrls(String workspaceId, String userId, String meetingId, PartsRequest request) throws Exception {
        Claims claims = verify(workspaceId, userId, meetingId, request.ticket());
        List<String> urls = upload.partUrls(claims, request.first_part(), request.count());
        List<PartUrl> parts = new ArrayList<>();
        for (int index = 0; index < urls.size(); index++) {
            parts.add(new PartUrl(request.first_part() + index, urls.get(index)));
        }
        return new PartsResponse(parts);
    }

    public void abort(String workspaceId, String userId, String meetingId, CompleteRequest request) throws Exception {
        upload.abort(verify(workspaceId, userId, meetingId, request.ticket()));
    }

    /**
     * 올린 조각을 조립해 크기를 확인하고 원본으로 기록한다. 녹음 파일 회의는 같은 트랜잭션에서 전사 command를 outbox에 넣고
     * transcribing이 된다. 확정 응답이 유실돼 다시 불러도 이미 기록된 원본이면 현재 회의를 돌려준다.
     */
    public MeetingResponse complete(String workspaceId, String userId, String meetingId, CompleteRequest request) throws Exception {
        Claims claims = verify(workspaceId, userId, meetingId, request.ticket());
        String key = claims.get("key", String.class);
        String contentType = claims.get("contentType", String.class);
        MeetingRepository.Meeting meeting = meetingService.requireOwned(workspaceId, userId, meetingId);
        if (key.equals(meeting.recordingKey())) {
            return meetingService.get(workspaceId, userId, meetingId);
        }
        requireUploadable(meeting);
        long size = upload.assemble(claims).size();
        try {
            requireSize(size, ((Number) claims.get("size")).longValue());
        } catch (MeetingException e) {
            removeQuietly(key);
            throw e;
        }
        boolean transcribe = "upload".equals(meeting.source());
        String runId = transcribe ? MeetingTranscriptionService.newRunId(meetingId) : null;
        Optional<MeetingRepository.RecordingSwap> swap;
        try {
            swap = transaction.execute(status -> {
                var saved = repository.saveRecording(meetingId, key, contentType, size, allowedStatuses(meeting),
                        transcribe ? "transcribing" : "open", !transcribe, runId);
                if (saved.isPresent() && transcribe) {
                    transcription.begin(runId, meeting, key, contentType);
                }
                return saved;
            });
        } catch (CreditService.InsufficientCreditException e) {
            removeQuietly(key);  // 크레딧을 채운 뒤 다시 올리게 한다. 기록되지 않은 원본(음성 개인정보)을 남기지 않는다.
            throw e;
        }
        if (swap.isEmpty()) {
            removeQuietly(key);  // 다른 요청이 먼저 상태를 바꿨다. 방금 조립한 객체는 어디에도 기록되지 않았다.
            throw notAllowed();
        }
        if (swap.get().previousKey() != null) {
            removeQuietly(swap.get().previousKey());  // 재업로드로 교체된 이전 원본(음성 개인정보)을 남기지 않는다
        }
        return meetingService.get(workspaceId, userId, meetingId);
    }

    /** 조립된 객체의 실제 크기를 확인한다. 상한 이하이고 비어 있지 않으며 처음 알린 크기와 같아야 한다. */
    static void requireSize(long size, long declared) {
        if (size <= 0) {
            throw new MeetingException(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_RECORDING_EMPTY", "녹음 파일이 비어 있습니다.");
        }
        if (size > MAX_BYTES) {
            throw new MeetingException(HttpStatus.PAYLOAD_TOO_LARGE, "MEETING_RECORDING_TOO_LARGE", "녹음 파일은 500 MiB 이하로 올려 주세요.");
        }
        if (size != declared) {
            throw new MeetingException(HttpStatus.BAD_REQUEST, "MEETING_RECORDING_SIZE_MISMATCH", "올린 파일 크기가 처음 알린 크기와 다릅니다.");
        }
    }

    private Claims verify(String workspaceId, String userId, String meetingId, String ticket) {
        Claims claims = upload.verify(workspaceId, userId, ticket);
        if (!meetingId.equals(claims.get("meeting", String.class))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 회의의 업로드를 사용할 수 없습니다.");
        }
        return claims;
    }

    private static List<String> allowedStatuses(MeetingRepository.Meeting meeting) {
        return "upload".equals(meeting.source()) ? List.of("awaiting_upload", "failed") : List.of("open");
    }

    /** 녹음 파일 회의는 올리기 전이거나 실패한 때, 실시간 회의는 받아쓰기가 끝나 원본이 아직 없을 때만 올릴 수 있다. */
    private void requireUploadable(MeetingRepository.Meeting meeting) {
        boolean upload = "upload".equals(meeting.source());
        if (!allowedStatuses(meeting).contains(meeting.status()) || (!upload && meeting.recordingKey() != null)) {
            throw notAllowed();
        }
        if (!upload && liveLock.isHeld(meeting.id())) {
            throw new MeetingException(HttpStatus.CONFLICT, "MEETING_LIVE_IN_USE", "받아쓰기가 끝난 뒤 녹음 원본을 올릴 수 있습니다.");
        }
    }

    private static MeetingException notAllowed() {
        return new MeetingException(HttpStatus.CONFLICT, "MEETING_RECORDING_NOT_ALLOWED", "지금은 녹음 원본을 올릴 수 없습니다.");
    }

    public RecordingUrl recordingUrl(String workspaceId, String userId, String meetingId) {
        MeetingRepository.Meeting meeting = meetingService.requireOwned(workspaceId, userId, meetingId);
        if (meeting.recordingKey() == null) {
            throw new MeetingException(HttpStatus.NOT_FOUND, "MEETING_RECORDING_NOT_FOUND", "녹음 원본이 없습니다.");
        }
        try {
            String url = minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(storage.getBucket()).object(meeting.recordingKey()).method(Method.GET)
                    .expiry((int) URL_TTL.toSeconds())
                    .extraQueryParams(Map.of("response-content-type", meeting.recordingContentType(),
                            "response-content-disposition", "inline"))
                    .build());
            return new RecordingUrl(url, Instant.now().plus(URL_TTL));
        } catch (Exception e) {
            throw new MeetingException(HttpStatus.SERVICE_UNAVAILABLE, "MEETING_RECORDING_STORAGE_FAILED", "녹음 재생 주소를 만들지 못했습니다.");
        }
    }

    /**
     * 원본을 먼저 지우고 DB를 지운다. 원본이 남는 쪽의 실패가 개인정보 문제이므로 원본 삭제가 실패하면 전체를 실패시킨다.
     * 저장소 호출 동안 DB 잠금이나 트랜잭션을 잡지 않는다. 대신 읽어 둔 키와 같을 때만 행을 지우므로,
     * 그사이 재업로드로 키가 바뀌었으면 새 원본을 남긴 채 409로 끝난다(다시 요청하면 지워진다).
     * 전사 중이거나 실시간 받아쓰기 중이면 거절한다.
     */
    public void delete(String workspaceId, String userId, String meetingId) {
        MeetingRepository.Meeting meeting = meetingService.requireOwned(workspaceId, userId, meetingId);
        if ("transcribing".equals(meeting.status())) {
            throw new MeetingException(HttpStatus.CONFLICT, "MEETING_TRANSCRIBING", "전사가 끝나거나 취소한 뒤 삭제해 주세요.");
        }
        if (liveLock.isHeld(meetingId)) {
            throw new MeetingException(HttpStatus.CONFLICT, "MEETING_LIVE_IN_USE", "받아쓰기 연결을 닫은 뒤 삭제해 주세요.");
        }
        String recordingKey = meeting.recordingKey();
        if (recordingKey != null) {
            try {
                minio.removeObject(RemoveObjectArgs.builder().bucket(storage.getBucket()).object(recordingKey).build());
            } catch (Exception e) {
                throw new MeetingException(HttpStatus.SERVICE_UNAVAILABLE, "MEETING_RECORDING_STORAGE_FAILED",
                        "녹음 원본을 삭제하지 못했습니다. 다시 시도해 주세요.");
            }
        }
        if (repository.deleteIfUnchanged(meetingId, recordingKey) == 0 && repository.findById(meetingId).isPresent()) {
            throw new MeetingException(HttpStatus.CONFLICT, "MEETING_RECORDING_CHANGED",
                    "삭제 중에 녹음 원본이 바뀌었습니다. 다시 시도해 주세요.");
        }
    }

    /** 추적에서 빠진 객체 정리. 실패하면 수동 정리를 위해 키를 남긴다(요청 결과에는 영향 없음). */
    private void removeQuietly(String key) {
        try {
            minio.removeObject(RemoveObjectArgs.builder().bucket(storage.getBucket()).object(key).build());
        } catch (Exception e) {
            log.warn("[회의 녹음 원본 정리 실패] key={}", key, e);
        }
    }

    /** 브라우저 MediaRecorder는 audio/webm;codecs=opus처럼 파라미터를 붙이므로 type/subtype만 본다. */
    private static String audioType(String contentType) {
        String raw = contentType == null ? "" : contentType;
        String type = raw.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.containsKey(type)) {
            throw new MeetingException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MEETING_RECORDING_UNSUPPORTED",
                    "WAV, MP3, M4A, WebM 녹음 파일만 올릴 수 있습니다.");
        }
        return switch (type) {
            case "audio/x-wav" -> "audio/wav";
            case "audio/x-m4a" -> "audio/mp4";
            default -> type;
        };
    }
}
