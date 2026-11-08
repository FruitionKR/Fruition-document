package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.meeting.MeetingException;
import fruition.core.meeting.MeetingResponse;
import fruition.core.meeting.MeetingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpMeetingToolsTest {
    private static final String MEETING = "mtg_" + "a".repeat(32);
    private final MeetingService meetings = mock(MeetingService.class);
    private final McpProperties props = McpTokenVerifierTest.props();  // 페이지당 10구간, 10,000바이트
    private final McpMeetingTools tools = new McpMeetingTools(meetings, new McpCursorCodec(new ObjectMapper(), props), props);
    private final McpIdentity owner = identity("owner");

    @Test void transcriptPagesByBlockLimitAndFailsExplicitlyOnOversizedSegment() {
        List<MeetingResponse.SegmentItem> segments = new ArrayList<>();
        for (int i = 0; i < 11; i++) segments.add(new MeetingResponse.SegmentItem("s" + i, i, "말 " + i, "completed"));
        segments.add(new MeetingResponse.SegmentItem("s11", 11, null, "pending"));
        when(meetings.get("workspace", "owner", MEETING)).thenReturn(meeting(segments, false));

        @SuppressWarnings("unchecked")
        var first = (Map<String, Object>) tools.invoke(owner, "get_meeting_transcript", args(null));
        assertThat((List<?>) first.get("segments")).hasSize(10);
        assertThat(first.get("transcript_complete")).isEqualTo(false);
        assertThat(first.get("segment_count")).isEqualTo(12);
        assertThat(first.get("next_cursor")).isNotNull();
        @SuppressWarnings("unchecked")
        var second = (Map<String, Object>) tools.invoke(owner, "get_meeting_transcript",
                args((String) first.get("next_cursor")));
        assertThat(((List<?>) second.get("segments")).stream().map(s -> ((Map<?, ?>) s).get("segment_id")).toList())
                .isEqualTo(List.of("s10", "s11"));
        assertThat(((Map<?, ?>) ((List<?>) second.get("segments")).get(1)).get("text")).isNull();
        assertThat(second.get("next_cursor")).isNull();

        // 다른 사용자(같은 워크스페이스)에게 발급된 cursor는 쓸 수 없고, 그 사용자의 회의 조회는 404로 숨겨진다.
        var other = identity("other");
        when(meetings.get("workspace", "other", MEETING))
                .thenThrow(new MeetingException(HttpStatus.NOT_FOUND, "MEETING_NOT_FOUND", "x"));
        assertThatThrownBy(() -> tools.invoke(other, "get_meeting_transcript", args((String) first.get("next_cursor"))))
                .isInstanceOfSatisfying(McpToolException.class, e -> assertThat(e.code()).isEqualTo("HANDLE_NOT_FOUND"));
        assertThatThrownBy(() -> tools.invoke(other, "get_meeting_transcript", args(null)))
                .isInstanceOfSatisfying(McpToolException.class, e -> assertThat(e.code()).isEqualTo("RESOURCE_NOT_FOUND"));

        when(meetings.get("workspace", "owner", MEETING)).thenReturn(meeting(
                List.of(new MeetingResponse.SegmentItem("big", 0, "x".repeat(10_001), "completed")), true));
        assertThatThrownBy(() -> tools.invoke(owner, "get_meeting_transcript", args(null)))
                .isInstanceOfSatisfying(McpToolException.class, e -> assertThat(e.code()).isEqualTo("RESPONSE_LIMIT_EXCEEDED"));
        assertThatThrownBy(() -> tools.invoke(owner, "get_meeting_transcript",
                Map.of("meeting_id", MEETING, "cursor", "c", "extra", 1))).isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, Object> args(String cursor) {
        var args = new java.util.HashMap<String, Object>();
        args.put("meeting_id", MEETING); args.put("cursor", cursor);
        return args;
    }

    private static MeetingResponse meeting(List<MeetingResponse.SegmentItem> segments, boolean complete) {
        return new MeetingResponse(MEETING, "주간 회의", "doc_1", "live", "open", false, false, null, null,
                complete, List.of(), segments, Instant.parse("2026-11-09T00:00:00Z"));
    }

    private static McpIdentity identity(String user) {
        return new McpIdentity(user, "workspace", "https://client.example/client.json", "grant-" + user, 1,
                Set.of("read"), "MEMBER", Set.of());
    }
}
