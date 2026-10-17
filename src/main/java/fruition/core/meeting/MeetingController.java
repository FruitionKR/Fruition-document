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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Tag(name = "Meetings", description = "회의 실시간 받아쓰기. 회의는 만든 사람만 조회·변경할 수 있다.")
@RestController
@RequestMapping("/api/workspaces/{workspace_id}/meetings")
public class MeetingController {
    private final MeetingService service;
    private final MeetingRecordingService recordings;
    private final MeetingTranscriptionService transcriptions;

    public MeetingController(MeetingService service, MeetingRecordingService recordings,
                             MeetingTranscriptionService transcriptions) {
        this.service = service;
        this.recordings = recordings;
        this.transcriptions = transcriptions;
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

    @Operation(operationId = "listMeetings", summary = "회의 목록",
            description = "내가 만든 회의를 최근 생성 순으로 반환합니다. 새로고침 뒤 진행 중이던 회의를 다시 여는 데 씁니다. "
                    + "전사 구간 같은 무거운 내용은 넣지 않으므로 GET /{meeting_id}로 이어서 조회합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = MeetingListResponse.class))),
        @ApiResponse(responseCode = "400", description = "허용하지 않는 status 또는 잘못된 cursor",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public MeetingListResponse list(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(description = "쉼표로 구분한 상태(open, awaiting_upload, transcribing, failed). 생략하면 전부",
                    example = "transcribing,open")
            @RequestParam(value = "status", required = false) String status,
            @Parameter(description = "이전 응답의 next_cursor. 생략하면 첫 페이지")
            @RequestParam(value = "cursor", required = false) String cursor,
            @Parameter(description = "페이지 크기. 기본 20, 최대 100", example = "20")
            @RequestParam(value = "limit", required = false) Integer limit) {
        return service.list(workspaceId, userId, status, cursor, limit);
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
                    + "이미 저장한 회의록 문서는 일반 문서라 남습니다. 받아쓰기 중이면 연결을 닫은 뒤, 전사 중이면 전사를 취소한 뒤 요청합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "삭제됨"),
        @ApiResponse(responseCode = "404", description = "회의를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "받아쓰기 연결 중 또는 전사 중",
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

    @Operation(operationId = "cancelMeetingTranscription", summary = "녹음 전사 취소",
            description = "전사 중(status=transcribing)인 녹음 파일 회의를 취소합니다. 회의는 failed가 되고 같은 원본으로 다시 올릴 수 있습니다. "
                    + "AI 작업은 취소를 요청하며, 취소 전까지 쓴 비용만 정산합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "취소됨",
            content = @Content(schema = @Schema(implementation = MeetingResponse.class))),
        @ApiResponse(responseCode = "404", description = "회의를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "전사 중인 회의가 아님(이미 끝났거나 취소됨)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{meeting_id}/transcription/cancel")
    public MeetingResponse cancelTranscription(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("meeting_id") String meetingId) {
        return transcriptions.cancel(workspaceId, userId, meetingId);
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
