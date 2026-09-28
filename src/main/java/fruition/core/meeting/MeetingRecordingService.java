package fruition.core.meeting;

import fruition.shared.util.StorageProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 회의 녹음 원본의 업로드·재생 URL·회의 삭제. 원본은 회의가 삭제될 때까지 보관한다.
 *
 * <p>녹음 파일 회의는 업로드하면 전사 대기(transcribing)가 되고 {@link MeetingTranscriptionWorker}가 처리한다.
 * 실시간 회의는 받아쓰기가 이미 끝났으므로 원본만 보관한다.
 */
@Service
public class MeetingRecordingService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MeetingRecordingService.class);
    static final long MAX_BYTES = 24L * 1024 * 1024;
    static final Duration URL_TTL = Duration.ofMinutes(5);
    private static final Map<String, String> EXTENSIONS = Map.of(
            "audio/wav", "wav", "audio/x-wav", "wav", "audio/mpeg", "mp3",
            "audio/mp4", "m4a", "audio/x-m4a", "m4a", "audio/webm", "webm");

    public record RecordingUrl(String url, Instant expiresAt) {}

    private final MeetingService meetingService;
    private final MeetingRepository repository;
    private final MeetingLiveLock liveLock;
    private final MinioClient minio;
    private final StorageProperties storage;

    public MeetingRecordingService(MeetingService meetingService, MeetingRepository repository,
                                   MeetingLiveLock liveLock, MinioClient minio, StorageProperties storage) {
        this.meetingService = meetingService;
        this.repository = repository;
        this.liveLock = liveLock;
        this.minio = minio;
        this.storage = storage;
    }

    public MeetingResponse upload(String workspaceId, String userId, String meetingId, MultipartFile file) {
        MeetingRepository.Meeting meeting = meetingService.requireOwned(workspaceId, userId, meetingId);
        String contentType = audioType(file);
        if (file.isEmpty()) {
            throw new MeetingException(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_RECORDING_EMPTY", "녹음 파일이 비어 있습니다.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new MeetingException(HttpStatus.PAYLOAD_TOO_LARGE, "MEETING_RECORDING_TOO_LARGE", "녹음 파일은 24 MiB 이하로 올려 주세요.");
        }
        boolean upload = "upload".equals(meeting.source());
        List<String> allowed = upload ? List.of("awaiting_upload", "failed") : List.of("open");
        if (!allowed.contains(meeting.status()) || (!upload && meeting.recordingKey() != null)) {
            throw new MeetingException(HttpStatus.CONFLICT, "MEETING_RECORDING_NOT_ALLOWED", "지금은 녹음 원본을 올릴 수 없습니다.");
        }
        if (!upload && liveLock.isHeld(meetingId)) {
            throw new MeetingException(HttpStatus.CONFLICT, "MEETING_LIVE_IN_USE", "받아쓰기가 끝난 뒤 녹음 원본을 올릴 수 있습니다.");
        }
        // 업로드마다 새 키에 쓴다. 겹친 업로드가 전사 중인 원본을 덮어쓰지 않고, 교체된 이전 원본은 아래에서 지운다.
        String key = "meetings/" + meetingId + "/recording-" + UUID.randomUUID() + "." + EXTENSIONS.get(contentType);
        try (InputStream input = file.getInputStream()) {
            minio.putObject(PutObjectArgs.builder().bucket(storage.getBucket()).object(key)
                    .stream(input, file.getSize(), -1).contentType(contentType).build());
        } catch (Exception e) {
            throw new MeetingException(HttpStatus.SERVICE_UNAVAILABLE, "MEETING_RECORDING_STORAGE_FAILED", "녹음 파일을 저장하지 못했습니다.");
        }
        var swap = repository.saveRecording(meetingId, key, contentType, file.getSize(), allowed,
                upload ? "transcribing" : "open", !upload);
        if (swap.isEmpty()) {
            removeQuietly(key);  // 다른 요청이 먼저 상태를 바꿨다. 방금 쓴 객체는 어디에도 기록되지 않았다.
            throw new MeetingException(HttpStatus.CONFLICT, "MEETING_RECORDING_NOT_ALLOWED", "지금은 녹음 원본을 올릴 수 없습니다.");
        }
        if (swap.get().previousKey() != null) {
            removeQuietly(swap.get().previousKey());  // 재업로드로 교체된 이전 원본(음성 개인정보)을 남기지 않는다
        }
        return meetingService.get(workspaceId, userId, meetingId);
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
     * 실시간 받아쓰기 중이면 연결을 닫은 뒤 다시 요청해야 한다.
     */
    public void delete(String workspaceId, String userId, String meetingId) {
        MeetingRepository.Meeting meeting = meetingService.requireOwned(workspaceId, userId, meetingId);
        if (liveLock.isHeld(meetingId)) {
            throw new MeetingException(HttpStatus.CONFLICT, "MEETING_LIVE_IN_USE", "받아쓰기 연결을 닫은 뒤 삭제해 주세요.");
        }
        if (meeting.recordingKey() != null) {
            try {
                minio.removeObject(RemoveObjectArgs.builder().bucket(storage.getBucket()).object(meeting.recordingKey()).build());
            } catch (Exception e) {
                throw new MeetingException(HttpStatus.SERVICE_UNAVAILABLE, "MEETING_RECORDING_STORAGE_FAILED",
                        "녹음 원본을 삭제하지 못했습니다. 다시 시도해 주세요.");
            }
        }
        repository.delete(meetingId);
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
    private static String audioType(MultipartFile file) {
        String raw = file.getContentType() == null ? "" : file.getContentType();
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
