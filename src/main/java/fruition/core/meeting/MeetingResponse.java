package fruition.core.meeting;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "회의 상태와 받아쓴 내용. 새로고침·연결 끊김 뒤 화면 복구에 쓴다.")
public record MeetingResponse(
        @JsonProperty("meeting_id") @Schema(example = "mtg_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83") String meetingId,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("document_id") @Schema(description = "회의록 저장 대상 문서. 없으면 새 문서로 저장한다.") String documentId,
        @Schema(allowableValues = {"live", "upload"}) String source,
        @Schema(allowableValues = {"open", "awaiting_upload", "transcribing", "failed"}) String status,
        @JsonProperty("live_connected") @Schema(description = "지금 실시간 받아쓰기 연결이 있는지") boolean liveConnected,
        @JsonProperty("has_recording") @Schema(description = "녹음 원본이 있는지") boolean hasRecording,
        @Schema(description = "녹음 파일 전사 실패 사유. 없으면 null") String error,
        @JsonProperty("transcript_complete")
        @Schema(description = "확정 전 구간이 없고 모든 연결이 정상 종료됐는지. false면 일부 전사만 있다.")
        boolean transcriptComplete,
        List<StreamItem> streams,
        List<SegmentItem> segments,
        @JsonProperty("created_at") Instant createdAt) {

    @Schema(name = "MeetingStream", description = "받아쓰기 연결 한 번")
    public record StreamItem(
            @JsonProperty("stream_order") int streamOrder,
            @JsonProperty("end_reason")
            @Schema(description = "finished(정상 종료), interrupted(끊김), failed(오류). 진행 중이면 null",
                    allowableValues = {"finished", "interrupted", "failed"})
            String endReason) {}

    @Schema(name = "MeetingSegment", description = "발화 순서대로 정렬된 전사 구간")
    public record SegmentItem(
            @Schema(example = "s1_item_a1") String id,
            int position,
            @Schema(description = "확정 전이면 null") String text,
            @Schema(allowableValues = {"completed", "pending"}) String status) {}
}
