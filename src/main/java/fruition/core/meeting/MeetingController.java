package fruition.core.meeting;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Tag(name = "Meetings", description = "회의 실시간 받아쓰기. 회의는 만든 사람만 조회·변경할 수 있다.")
@RestController
@RequestMapping("/api/workspaces/{workspace_id}/meetings")
public class MeetingController {
    private final MeetingService service;
    private final MeetingRecordingService recordings;

    public MeetingController(MeetingService service, MeetingRecordingService recordings) {
        this.service = service;
        this.recordings = recordings;
    }

    @Operation(operationId = "createMeeting", summary = "회의 생성",
            description = "받아쓰기 회의를 만듭니다. document_id를 주면 그 문서를 회의록 저장 대상으로 기억하며, "
                    + "사용자가 편집할 수 있는 자기 Markdown 문서여야 합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "생성됨",
            content = @Content(schema = @Schema(implementation = MeetingResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 이름·source 또는 Idempotency-Key",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "대상 문서를 편집할 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스 또는 대상 문서를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Idempotency-Key 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<MeetingResponse> create(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(description = "요청 멱등 키", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(
                workspaceId, userId, idempotencyKey, request.displayName(), request.source(), request.documentId()));
    }

    @Operation(operationId = "getMeeting", summary = "회의 조회", description = "회의 상태, 받아쓰기 연결 기록, 발화 순서대로 정렬된 전사 구간을 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = MeetingResponse.class))),
        @ApiResponse(responseCode = "404", description = "회의를 찾을 수 없음(다른 사용자의 회의 포함)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{meeting_id}")
    public MeetingResponse get(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("meeting_id") String meetingId) {
        return service.get(workspaceId, userId, meetingId);
    }

    @Operation(operationId = "issueMeetingLiveTicket", summary = "실시간 받아쓰기 ticket 발급",
            description = "WS /api/meetings/{meeting_id}/live?ticket= 접속에 쓰는 60초짜리 일회용 ticket을 발급합니다. "
                    + "브라우저 WebSocket은 Authorization 헤더를 보낼 수 없어 ticket으로 인증합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "발급 성공",
            content = @Content(schema = @Schema(implementation = LiveTicketResponse.class))),
        @ApiResponse(responseCode = "404", description = "회의를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "실시간 회의가 아니거나 이미 다른 곳에서 받아쓰는 중",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{meeting_id}/live-tickets")
    public LiveTicketResponse issueTicket(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("meeting_id") String meetingId) {
        MeetingService.LiveTicket ticket = service.issueTicket(workspaceId, userId, meetingId);
        return new LiveTicketResponse(ticket.ticket(), ticket.expiresAt());
    }

    @Operation(operationId = "deleteMeeting", summary = "회의 삭제",
            description = "회의·전사·회의록 초안·녹음 원본을 삭제합니다. 원본을 먼저 지우며, 실패하면 아무것도 지우지 않습니다. "
                    + "이미 저장한 회의록 문서는 일반 문서라 남습니다. 받아쓰기 중이면 연결을 닫은 뒤 요청합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "삭제됨"),
        @ApiResponse(responseCode = "404", description = "회의를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "받아쓰기 연결 중",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "녹음 원본 삭제 실패",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{meeting_id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("meeting_id") String meetingId) {
        recordings.delete(workspaceId, userId, meetingId);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "uploadMeetingRecording", summary = "녹음 원본 업로드",
            description = "녹음 파일 회의(source=upload)는 원본을 저장하고 전사를 시작합니다(202, status=transcribing). "
                    + "실시간 회의(source=live)는 받아쓰기가 끝난 뒤 브라우저가 녹음한 원본을 보관만 합니다(200). "
                    + "wav·mp3·m4a·webm, 24 MiB 이하. 원본은 회의 삭제 전까지 보관합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "실시간 회의 원본 저장",
            content = @Content(schema = @Schema(implementation = MeetingResponse.class))),
        @ApiResponse(responseCode = "202", description = "녹음 파일 전사 시작",
            content = @Content(schema = @Schema(implementation = MeetingResponse.class))),
        @ApiResponse(responseCode = "404", description = "회의를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "허용되지 않는 상태, 원본이 이미 있음, 받아쓰기 중",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "413", description = "24 MiB 초과",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "415", description = "지원하지 않는 형식",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "빈 파일",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping(path = "/{meeting_id}/recording", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MeetingResponse> uploadRecording(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("meeting_id") String meetingId,
            @RequestPart("file") MultipartFile file) {
        MeetingResponse meeting = recordings.upload(workspaceId, userId, meetingId, file);
        return ResponseEntity.status("transcribing".equals(meeting.status()) ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(meeting);
    }

    @Operation(operationId = "getMeetingRecordingUrl", summary = "녹음 원본 재생 주소",
            description = "5분 동안 유효한 presigned GET 주소를 반환합니다. <audio src>로 바로 재생합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "발급 성공",
            content = @Content(schema = @Schema(implementation = RecordingUrlResponse.class))),
        @ApiResponse(responseCode = "404", description = "회의 또는 원본이 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{meeting_id}/recording-url")
    public RecordingUrlResponse recordingUrl(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("meeting_id") String meetingId) {
        MeetingRecordingService.RecordingUrl url = recordings.recordingUrl(workspaceId, userId, meetingId);
        return new RecordingUrlResponse(url.url(), url.expiresAt());
    }

    @Schema(name = "MeetingRecordingUrlResponse", description = "녹음 원본 재생 주소")
    public record RecordingUrlResponse(
            String url,
            @JsonProperty("expires_at") Instant expiresAt) {}

    @Schema(name = "MeetingCreateRequest", description = "회의 생성 요청")
    public record CreateRequest(
            @JsonProperty("display_name") @Schema(description = "1–200자. 생략하면 회의록", example = "출시 회의")
            String displayName,
            @Schema(description = "live(실시간 받아쓰기) 또는 upload(녹음 파일)", allowableValues = {"live", "upload"},
                    example = "live")
            String source,
            @JsonProperty("document_id") @Schema(description = "선택. 회의록을 끝에 추가할 문서")
            String documentId) {}

    @Schema(name = "MeetingLiveTicketResponse", description = "실시간 받아쓰기 일회용 접속권")
    public record LiveTicketResponse(
            @Schema(example = "wst_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83") String ticket,
            @JsonProperty("expires_at") Instant expiresAt) {}
}
