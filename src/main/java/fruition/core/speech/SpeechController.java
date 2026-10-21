package fruition.core.speech;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

@Tag(name = "Speech", description = "채팅 음성 입력(음성과 전사 결과를 저장하지 않는다)과 답변 음성 듣기(완료된 합성만 30일 저장).")
@RestController
public class SpeechController {
    static final int MAX_AUDIO_BYTES = 24 * 1024 * 1024;
    private static final Set<String> AUDIO_TYPES = Set.of("audio/wav", "audio/mpeg", "audio/mp4", "audio/webm");

    private final WorkspaceAccessGuard accessGuard;
    private final SpeechTranscriptionClient client;
    private final SpeechSynthesisService synthesis;

    public SpeechController(WorkspaceAccessGuard accessGuard, SpeechTranscriptionClient client,
                            SpeechSynthesisService synthesis) {
        this.accessGuard = accessGuard;
        this.client = client;
        this.synthesis = synthesis;
    }

    @Operation(operationId = "transcribeSpeech", summary = "음성 받아쓰기",
            description = "짧은 음성을 텍스트로 바꿔 반환합니다. 결과를 질의·Agent에 자동으로 제출하지 않으며, "
                    + "음성과 텍스트를 저장하지 않습니다. 인식된 말이 없으면 text는 빈 문자열입니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "오디오 bytes 그대로(multipart 아님). 24 MiB 이하",
                    content = {
                        @Content(mediaType = "audio/webm", schema = @Schema(type = "string", format = "binary")),
                        @Content(mediaType = "audio/mp4", schema = @Schema(type = "string", format = "binary")),
                        @Content(mediaType = "audio/mpeg", schema = @Schema(type = "string", format = "binary")),
                        @Content(mediaType = "audio/wav", schema = @Schema(type = "string", format = "binary"))
                    }))
    @ApiResponses({
        @ApiResponse(responseCode = "402", description = "크레딧 부족(INSUFFICIENT_CREDIT). app.billing.enforce가 true일 때 가용 잔액이 최저 비용보다 작으면 작업을 시작하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "200", description = "전사 성공",
            content = @Content(schema = @Schema(implementation = TranscriptionResponse.class))),
        @ApiResponse(responseCode = "403", description = "워크스페이스 접근 권한 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "413", description = "24 MiB 초과",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "415", description = "지원하지 않는 오디오 형식",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "빈 음성 또는 인식할 수 없는 음성",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "음성 모델 호출 실패",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "음성 전사 서비스를 사용할 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/api/workspaces/{workspace_id}/speech/transcriptions")
    public TranscriptionResponse transcribe(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId,
            HttpServletRequest request) throws IOException {
        MediaType mediaType = audioType(request.getContentType());
        accessGuard.requireMember(workspaceId, userId);
        byte[] audio = readLimited(request.getInputStream());
        if (audio.length == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "음성이 비어 있습니다.");
        }
        return new TranscriptionResponse(client.transcribe(workspaceId, userId, mediaType, audio));
    }

    @Operation(operationId = "synthesizeSpeech", summary = "채팅 답변 음성 듣기",
            description = "완료된 assistant 답변을 음성으로 스트리밍합니다. 응답은 줄 단위 JSON(application/x-ndjson)입니다. "
                    + "{\"type\":\"audio\",\"delta\":\"<base64 PCM16 LE mono 24kHz>\"} 이벤트가 이어지고, 정확히 하나의 "
                    + "{\"type\":\"done\"} 또는 {\"type\":\"error\",\"code\":\"...\"}로 끝납니다. "
                    + "끝까지 합성된 음성은 저장돼 같은 형식으로 다시 재생되며(과금 없음) 30일 뒤 삭제됩니다. "
                    + "연결을 끊으면 합성을 멈춥니다. HTTP 상태 오류는 스트림이 시작되기 전에만 나옵니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "NDJSON 이벤트 스트림",
            content = @Content(mediaType = "application/x-ndjson", schema = @Schema(type = "string"))),
        @ApiResponse(responseCode = "400", description = "message_id 누락 또는 답변이 합성 입력 한도 초과",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "402", description = "크레딧 부족",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스·답변을 찾을 수 없음(멤버가 아니거나 내 세션의 assistant 답변이 아님)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "완료되지 않았거나 실패한 답변",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "읽을 글이 없는 답변(코드만 있는 답변 등)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "음성 합성 서비스를 사용할 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/api/workspaces/{workspace_id}/speech/synthesis")
    public void synthesize(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId,
            @Valid @RequestBody SynthesisRequest request,
            HttpServletResponse response) throws IOException {
        var body = synthesis.prepare(workspaceId, userId, request.messageId());
        response.setContentType("application/x-ndjson");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", CacheControl.noStore().getHeaderValue());
        // 서블릿 스레드에서 직접 쓴다. async 요청이 아니라 MVC 비동기 타임아웃(기본 30초)에 걸리지 않고, 쓰기 실패가 곧 끊김이다.
        body.writeTo(response.getOutputStream());
    }

    private static MediaType audioType(String contentType) {
        MediaType type;
        try {
            type = contentType == null ? null : MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException e) {
            type = null;
        }
        // 브라우저 MediaRecorder는 audio/webm;codecs=opus처럼 파라미터를 붙이므로 type/subtype만 비교해 넘긴다.
        if (type == null || !AUDIO_TYPES.contains(type.getType() + "/" + type.getSubtype())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "WAV, MP3, M4A, WebM 음성을 보내주세요.");
        }
        return new MediaType(type.getType(), type.getSubtype());
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        byte[] audio = input.readNBytes(MAX_AUDIO_BYTES + 1);
        if (audio.length > MAX_AUDIO_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "음성 파일은 24MB 이하로 보내주세요.");
        }
        return audio;
    }

    @Schema(name = "SpeechTranscriptionResponse", description = "받아쓴 텍스트")
    public record TranscriptionResponse(
            @Schema(description = "받아쓴 문장. 인식된 말이 없으면 빈 문자열", example = "검색 인덱싱은 어떻게 동작해?")
            String text) {}

    @Schema(name = "SpeechSynthesisRequest")
    public record SynthesisRequest(
            @NotBlank @JsonProperty("message_id")
            @Schema(description = "음성으로 들을 assistant 메시지 ID", example = "msg_2f0b8d3f1c9a4c4e9a3d6f7a1b2c3d4e")
            String messageId) {}
}
