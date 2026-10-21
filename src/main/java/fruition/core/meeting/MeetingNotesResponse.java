package fruition.core.meeting;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "회의록 초안. markdown은 AI가 만든 시안 구성 본문(날짜·참석자, 회의 요약, 주요 논의, 결정 사항, 해야 할 일, "
        + "다음 일정)이며 근거 ID를 넣지 않는다. 이전 AI 응답으로 만든 초안은 document가 네 배열로 만든 옛 구성이다.")
public record MeetingNotesResponse(
        @JsonProperty("meeting_id") String meetingId,
        int version,
        @Schema(allowableValues = {"generating", "ready", "failed", "applied"}) String status,
        @Schema(description = "일부 전사만으로 만든 초안인지") boolean partial,
        @JsonProperty("display_name") String displayName,
        String markdown,
        @JsonProperty("meeting_date")
        @Schema(description = "회의 날짜(예: 2026-10-19). 없거나 이전 초안이면 null") String meetingDate,
        @Schema(description = "참석자 이름. 확인되지 않았거나 이전 초안이면 빈 배열") List<String> attendees,
        List<Item> summary,
        @Schema(description = "주요 논의의 주제별 묶음. 이전 초안이면 빈 배열") List<DiscussionTopic> discussion,
        List<Item> decisions,
        @JsonProperty("action_items") List<ActionItem> actionItems,
        @JsonProperty("next_schedule")
        @Schema(description = "다음 일정. 이전 초안이면 빈 배열") List<Item> nextSchedule,
        @JsonProperty("open_questions")
        @Schema(description = "미결 사항. 시안 본문에는 싣지 않지만 배열로 계속 돌려준다") List<Item> openQuestions,
        @Schema(description = "저장 후 결과. 저장 전이면 null") Applied applied,
        @JsonProperty("error_code")
        @Schema(description = "실패 원인 코드. 제공자 원문은 노출하지 않는다") String errorCode,
        @JsonProperty("last_ready_version")
        @Schema(description = "최신 버전이 실패일 때 마지막으로 성공한 버전") Integer lastReadyVersion) {

    @Schema(name = "MeetingNoteItem", description = "회의록 항목과 근거 전사 구간")
    public record Item(
            String text,
            @JsonProperty("source_segment_ids") List<String> sourceSegmentIds) {}

    @Schema(name = "MeetingNoteActionItem", description = "할 일과 담당자·기한, 근거 전사 구간")
    public record ActionItem(
            String text,
            @Schema(description = "발언으로 확인된 담당자. 없으면 null") String owner,
            @Schema(description = "발언으로 확인된 기한. 없으면 null") String due,
            @JsonProperty("source_segment_ids") List<String> sourceSegmentIds) {}

    @Schema(name = "MeetingNoteDiscussionTopic", description = "주요 논의의 주제 하나와 그 항목")
    public record DiscussionTopic(String topic, List<Item> items) {}

    @Schema(name = "MeetingNotesApplied", description = "회의록 저장 결과")
    public record Applied(
            @Schema(allowableValues = {"create", "append"}) String mode,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("applied_at") Instant appliedAt) {}
}
