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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Meetings", description = "회의 실시간 받아쓰기. 회의는 만든 사람만 조회·변경할 수 있다.")
@RestController
@RequestMapping("/api/workspaces/{workspace_id}/meetings/{meeting_id}/notes")
public class MeetingNotesController {
    private final MeetingNotesService service;

    public MeetingNotesController(MeetingNotesService service) {
        this.service = service;
    }

    @Operation(operationId = "generateMeetingNotes", summary = "회의록 초안 생성",
            description = "확정 전사로 요약·결정 사항·할 일·미결 사항 초안을 만들어 새 버전으로 보관합니다. "
                    + "생성은 요청 스레드 밖에서 하므로 새 버전을 generating으로 만들고 바로 응답합니다(202). "
                    + "결과는 조회(GET)로 기다리고, 생성 실패는 그 버전의 failed 상태와 error_code로 알립니다. "
                    + "같은 Idempotency-Key는 같은 버전을 돌려주고, 다시 만들려면 새 키를 씁니다. "
                    + "누락되었거나 정상 종료되지 않은 녹음이 있으면 allow_partial=true일 때만 만듭니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "같은 키 재시도로 이미 끝난 버전을 반환",
            content = @Content(schema = @Schema(implementation = MeetingNotesResponse.class))),
        @ApiResponse(responseCode = "202", description = "생성 시작(status=generating)",
            content = @Content(schema = @Schema(implementation = MeetingNotesResponse.class))),
        @ApiResponse(responseCode = "404", description = "회의를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "받아쓰기 중, 불완전 전사, 동시 생성",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "확정 전사 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<MeetingNotesResponse> generate(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("meeting_id") String meetingId,
            @Parameter(description = "요청 멱등 키", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(value = "allow_partial", defaultValue = "false") boolean allowPartial) {
        MeetingNotesResponse notes = service.generate(workspaceId, userId, meetingId, idempotencyKey, allowPartial);
        return ResponseEntity.status("generating".equals(notes.status()) ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(notes);
    }

    @Operation(operationId = "getMeetingNotes", summary = "최신 회의록 초안 조회",
            description = "가장 최근 버전을 반환합니다. 최신 버전이 실패면 마지막 성공 버전 번호를 함께 줍니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = MeetingNotesResponse.class))),
        @ApiResponse(responseCode = "404", description = "회의 또는 초안을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public MeetingNotesResponse latest(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("meeting_id") String meetingId) {
        return service.latest(workspaceId, userId, meetingId);
    }

    @Operation(operationId = "previewMeetingNotesAppend", summary = "기존 문서 끝 추가 미리보기",
            description = "현재 본문 끝에 빈 줄 하나를 두고 회의록을 붙인 전체 결과와 base_revision을 반환합니다. "
                    + "저장(apply)은 같은 입력이면 같은 규칙으로 같은 본문을 만듭니다. 아무것도 바꾸지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "미리보기",
            content = @Content(schema = @Schema(implementation = AppendPreviewResponse.class))),
        @ApiResponse(responseCode = "400", description = "추가할 문서가 지정되지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "문서를 편집할 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "회의·초안·문서를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "최신 초안이 아님 또는 이미 저장함",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{version}/append-preview")
    public AppendPreviewResponse appendPreview(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("meeting_id") String meetingId,
            @PathVariable("version") int version,
            @RequestBody(required = false) AppendPreviewRequest request) {
        AppendPreviewRequest body = request == null ? new AppendPreviewRequest(null, null) : request;
        MeetingNotesService.AppendPreview preview = service.appendPreview(
                workspaceId, userId, meetingId, version, body.documentId(), body.markdown());
        return new AppendPreviewResponse(preview.documentId(), preview.baseRevision(), preview.markdown());
    }

    @Operation(operationId = "applyMeetingNotes", summary = "회의록 저장",
            description = "초안을 새 문서로 만들거나(create) 기존 문서 끝에 추가합니다(append). 기존 문서 권한·잠금·revision 검증을 "
                    + "그대로 거치며, 같은 Idempotency-Key 재시도는 같은 저장 결과를 돌려줍니다. 한 초안 버전은 한 번 저장합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "저장 성공",
            content = @Content(schema = @Schema(implementation = MeetingNotesResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 mode·대상·base_revision 또는 Idempotency-Key",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "문서를 편집할 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "회의·초안·문서를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "최신 초안이 아님, 이미 저장함, 미리보기 이후 문서가 바뀜, 이름 중복",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{version}/apply")
    public MeetingNotesResponse apply(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("meeting_id") String meetingId,
            @PathVariable("version") int version,
            @Parameter(description = "요청 멱등 키", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ApplyRequest request) {
        return service.apply(workspaceId, userId, meetingId, version, idempotencyKey,
                new MeetingNotesService.ApplyRequest(request.mode(), request.displayName(), request.markdown(),
                        request.folderId(), request.documentId(), request.baseRevision()));
    }

    @Schema(name = "MeetingNotesAppendPreviewRequest", description = "기존 문서 끝 추가 미리보기 요청")
    public record AppendPreviewRequest(
            @JsonProperty("document_id") @Schema(description = "생략하면 회의를 시작한 문서") String documentId,
            @Schema(description = "사용자가 수정한 회의록 본문. 생략하면 초안 본문") String markdown) {}

    @Schema(name = "MeetingNotesAppendPreviewResponse", description = "기존 문서에 회의록을 붙인 전체 결과")
    public record AppendPreviewResponse(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("base_revision") @Schema(description = "apply에 그대로 보낸다") long baseRevision,
            @Schema(description = "저장될 전체 본문") String markdown) {}

    @Schema(name = "MeetingNotesApplyRequest", description = "회의록 저장 요청")
    public record ApplyRequest(
            @Schema(allowableValues = {"create", "append"}) String mode,
            @JsonProperty("display_name") @Schema(description = "create의 문서 이름. 생략하면 초안 이름") String displayName,
            @Schema(description = "사용자가 수정한 회의록 본문. 생략하면 초안 본문") String markdown,
            @JsonProperty("folder_id") @Schema(description = "create의 위치. 생략하면 루트") UUID folderId,
            @JsonProperty("document_id") @Schema(description = "append 대상. 생략하면 회의를 시작한 문서") String documentId,
            @JsonProperty("base_revision") @Schema(description = "append에 필수. append-preview의 값") Long baseRevision) {}
}
