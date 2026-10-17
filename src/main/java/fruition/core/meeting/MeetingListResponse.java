package fruition.core.meeting;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "내 회의 목록. 최근 생성 순이다.")
public record MeetingListResponse(
        List<Item> items,
        @JsonProperty("next_cursor") @Schema(description = "다음 페이지 cursor. 마지막 페이지면 null", nullable = true)
        String nextCursor) {

    @Schema(name = "MeetingListItem", description = "회의 한 건 요약")
    public record Item(
            @JsonProperty("meeting_id") @Schema(example = "mtg_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83") String meetingId,
            @JsonProperty("display_name") @Schema(example = "출시 회의") String displayName,
            @Schema(allowableValues = {"live", "upload"}) String source,
            @Schema(allowableValues = {"open", "awaiting_upload", "transcribing", "failed"}) String status,
            @JsonProperty("has_recording") @Schema(description = "녹음 원본이 있는지") boolean hasRecording,
            @JsonProperty("has_notes") @Schema(description = "만들어졌거나 저장된 회의록 초안이 있는지") boolean hasNotes,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt) {}
}
