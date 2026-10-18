package fruition.core.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.aitask.repository.PipelineTaskCancellationClient;
import fruition.core.document.service.AiCommandOutboxPublisher;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 회의 목록: 만든 사람의 회의만, 최근 생성 순으로, status 필터·cursor 페이지로 준다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MeetingListIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean AiCommandOutboxPublisher outboxPublisher;
    @MockBean PipelineTaskCancellationClient pipelineCancellation;

    private String userId;
    private String otherUserId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_" + suffix;
        otherUserId = "other_" + suffix;
        workspaceId = "ws_" + suffix;
        for (String user : List.of(userId, otherUserId)) {
            redisTemplate.opsForValue().set("authz:role:v2:" + workspaceId + ":" + user,
                    "{\"role\":\"MEMBER\",\"permissions\":[]}");
        }
    }

    @Test
    void list_returnsOnlyOwnMeetingsNewestFirstWithoutHeavyFields() throws Exception {
        Instant base = Instant.parse("2026-10-15T01:00:00Z");
        insertMeeting("mtg_old", userId, "open", base, null);
        insertMeeting("mtg_new", userId, "transcribing", base.plusSeconds(60), "meetings/mtg_new/recording.m4a");
        insertMeeting("mtg_other", otherUserId, "open", base.plusSeconds(120), null);
        jdbc.update("""
                INSERT INTO meeting_notes (meeting_id, version, generation_request_id, status, partial,
                                           segment_snapshot, created_at, updated_at)
                VALUES (?, 1, 'g1', 'ready', false, '[]', now(), now())
                """, "mtg_old_" + workspaceId);

        JsonNode body = list("", userId);

        assertThat(body.path("items")).extracting(item -> item.path("meeting_id").asText())
                .containsExactly("mtg_new", "mtg_old");
        assertThat(body.path("next_cursor").isNull()).isTrue();
        JsonNode newest = body.path("items").get(0);
        assertThat(newest.path("status").asText()).isEqualTo("transcribing");
        assertThat(newest.path("has_recording").asBoolean()).isTrue();
        assertThat(newest.path("has_notes").asBoolean()).isFalse();
        assertThat(newest.has("segments")).isFalse();
        assertThat(body.path("items").get(1).path("has_notes").asBoolean()).isTrue();
        // 다른 사용자에게는 자기 회의만 보인다.
        assertThat(list("", otherUserId).path("items")).extracting(item -> item.path("meeting_id").asText())
                .containsExactly("mtg_other");
    }

    @Test
    void list_filtersByStatusAndPagesWithCursor() throws Exception {
        Instant base = Instant.parse("2026-10-15T01:00:00Z");
        // 같은 created_at도 id로 순서를 정해 페이지 경계에서 빠지거나 겹치지 않는다.
        insertMeeting("mtg_a", userId, "open", base, null);
        insertMeeting("mtg_b", userId, "open", base, null);
        insertMeeting("mtg_c", userId, "failed", base.plusSeconds(1), null);
        insertMeeting("mtg_d", userId, "transcribing", base.plusSeconds(2), null);

        assertThat(list("?status=transcribing,open", userId).path("items"))
                .extracting(item -> item.path("meeting_id").asText())
                .containsExactly("mtg_d", "mtg_b", "mtg_a");

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = list("?limit=2" + (cursor == null ? "" : "&cursor=" + cursor), userId);
            assertThat(page.path("items").size()).isLessThanOrEqualTo(2);
            page.path("items").forEach(item -> seen.add(item.path("meeting_id").asText()));
            cursor = page.path("next_cursor").isNull() ? null : page.path("next_cursor").asText();
        } while (cursor != null);
        assertThat(seen).containsExactly("mtg_d", "mtg_c", "mtg_b", "mtg_a");
    }

    @Test
    void list_rejectsUnknownStatusAndMalformedCursor() throws Exception {
        mockMvc.perform(get(base() + "?status=open,done").header("Authorization", bearer(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MEETING"));
        mockMvc.perform(get(base() + "?cursor=garbage").header("Authorization", bearer(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MEETING"));
    }

    private void insertMeeting(String id, String createdBy, String status, Instant createdAt, String recordingKey) {
        jdbc.update("""
                INSERT INTO meetings (id, workspace_id, created_by, display_name, source, status, recording_key,
                                      created_at, updated_at)
                VALUES (?, ?, ?, '회의', 'upload', ?, ?, ?, ?)
                """, id + "_" + workspaceId, workspaceId, createdBy, status, recordingKey,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private JsonNode list(String query, String user) throws Exception {
        var response = mockMvc.perform(get(base() + query).header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        // 테스트끼리 id가 겹치지 않게 붙인 workspace 접미사를 떼어 비교한다.
        body.path("items").forEach(item -> ((com.fasterxml.jackson.databind.node.ObjectNode) item).put("meeting_id",
                item.path("meeting_id").asText().replace("_" + workspaceId, "")));
        return body;
    }

    private String base() {
        return "/api/workspaces/" + workspaceId + "/meetings";
    }

    private String bearer(String user) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(user, user + "@example.com");
    }
}
