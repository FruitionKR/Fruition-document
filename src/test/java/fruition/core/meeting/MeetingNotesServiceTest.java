package fruition.core.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingNotesServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void notesMarkdown_usesStoredAiMarkdown() throws Exception {
        var result = objectMapper.readTree("""
                {"display_name":"출시 회의","markdown":"# 출시 회의\\n\\n### 회의 요약\\n- 정했다.",
                 "summary":[{"text":"정했다.","source_segment_ids":["s1"]}]}
                """);

        assertThat(MeetingNotesService.notesMarkdown(result)).isEqualTo("# 출시 회의\n\n### 회의 요약\n- 정했다.");
    }

    @Test
    void notesMarkdown_fallsBackToLegacyRenderWithoutMarkdown() throws Exception {
        var result = objectMapper.readTree("""
                {"display_name":"출시 회의","markdown":"  ",
                 "summary":[{"text":"정했다.","source_segment_ids":["s1"]}],"decisions":[],"action_items":[],
                 "open_questions":[]}
                """);

        assertThat(MeetingNotesService.notesMarkdown(result))
                .isEqualTo(MeetingNotesService.render(result))
                .startsWith("# 출시 회의\n\n## 요약\n- 정했다.");
    }

    @Test
    void meetingDate_isKoreanDateOfMeetingCreation() {
        // UTC 10월 19일 15:30은 한국 시간 10월 20일 00:30이다.
        var meeting = new MeetingResponse("mtg_1", "회의", null, "live", "open", false, false, null, true,
                List.of(), List.of(), Instant.parse("2026-10-19T15:30:00Z"));

        assertThat(MeetingNotesService.meetingDate(meeting)).isEqualTo("2026-10-20");
    }
}
