package fruition.core.meeting;

import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 회의 녹음 원본을 브라우저가 S3에 직접 multipart로 올리는 API. 문서 직접 업로드와 같은 절차·이름을 쓴다. */
@Tag(name = "Meetings", description = "회의 실시간 받아쓰기. 회의는 만든 사람만 조회·변경할 수 있다.")
@RestController
@RequestMapping("/api/workspaces/{workspace_id}/meetings/{meeting_id}/recording/uploads")
public class MeetingRecordingUploadController {
    private final MeetingRecordingService recordings;

    public MeetingRecordingUploadController(MeetingRecordingService recordings) {
        this.recordings = recordings;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> rejected(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(
                ErrorResponse.of("MEETING_UPLOAD_REJECTED", error.getReason()));
    }

    @Operation(operationId = "startMeetingRecordingUpload", summary = "녹음 원본 직접 업로드 시작",
            description = "wav·mp3·m4a·webm, 500 MiB 이하. 반환한 ticket으로 조각별 URL을 받아 S3에 직접 올린 뒤 complete를 호출합니다.")
    @PostMapping
    public MeetingRecordingService.StartResponse start(@PathVariable("workspace_id") String workspace,
            @PathVariable("meeting_id") String meeting, @AuthenticationPrincipal String user,
            @RequestBody MeetingRecordingService.StartRequest request) throws Exception {
        return recordings.start(workspace, user, meeting, request);
    }

    @Operation(operationId = "getMeetingRecordingUploadParts", summary = "녹음 조각 업로드 URL 발급",
            description = "first_part부터 count(1–32)개 조각의 15분짜리 presigned PUT URL을 반환합니다.")
    @PostMapping("/parts")
    public MeetingRecordingService.PartsResponse parts(@PathVariable("workspace_id") String workspace,
            @PathVariable("meeting_id") String meeting, @AuthenticationPrincipal String user,
            @RequestBody MeetingRecordingService.PartsRequest request) throws Exception {
        return recordings.partUrls(workspace, user, meeting, request);
    }

    @Operation(operationId = "abortMeetingRecordingUpload", summary = "녹음 직접 업로드 중단")
    @PostMapping("/abort")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abort(@PathVariable("workspace_id") String workspace, @PathVariable("meeting_id") String meeting,
            @AuthenticationPrincipal String user, @RequestBody MeetingRecordingService.CompleteRequest request)
            throws Exception {
        recordings.abort(workspace, user, meeting, request);
    }

    @Operation(operationId = "completeMeetingRecordingUpload", summary = "녹음 직접 업로드 확정",
            description = "조각을 조립해 크기를 확인하고 원본으로 기록합니다. 녹음 파일 회의(source=upload)는 전사를 시작합니다(202, status=transcribing). "
                    + "실시간 회의(source=live)는 원본만 보관합니다(200).")
    @PostMapping("/complete")
    public ResponseEntity<MeetingResponse> complete(@PathVariable("workspace_id") String workspace,
            @PathVariable("meeting_id") String meeting, @AuthenticationPrincipal String user,
            @RequestBody MeetingRecordingService.CompleteRequest request) throws Exception {
        MeetingResponse response = recordings.complete(workspace, user, meeting, request);
        return ResponseEntity.status("transcribing".equals(response.status()) ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(response);
    }
}
