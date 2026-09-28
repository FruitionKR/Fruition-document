package fruition.core.meeting;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "회의록 초안. markdown은 document가 항목 배열로 만든 본문이며 근거 ID를 넣지 않는다.")
public record MeetingNotesResponse(
        @JsonProperty("meeting_id") String meetingId,
        int version,
        @Schema(allowableValues = {"generating", "ready", "failed", "applied"}) String status,
        @Schema(description = "일부 전사만으로 만든 초안인지") boolean partial,
        @JsonProperty("display_name") String displayName,
        String markdown,
        List<Item> summary,
        List<Item> decisions,
        @JsonProperty("action_items") List<Item> actionItems,
        @JsonProperty("open_questions") List<Item> openQuestions,
        @Schema(description = "저장 후 결과. 저장 전이면 null") Applied applied,
        @JsonProperty("error_code")
        @Schema(description = "실패 원인 코드. 제공자 원문은 노출하지 않는다") String errorCode,
        @JsonProperty("last_ready_version")
        @Schema(description = "최신 버전이 실패일 때 마지막으로 성공한 버전") Integer lastReadyVersion) {

    @Schema(name = "MeetingNoteItem", description = "회의록 항목과 근거 전사 구간")
    public record Item(
            String text,
            @JsonProperty("source_segment_ids") List<String> sourceSegmentIds) {}

    @Schema(name = "MeetingNotesApplied", description = "회의록 저장 결과")
    public record Applied(
            @Schema(allowableValues = {"create", "append"}) String mode,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("applied_at") Instant appliedAt) {}
}
