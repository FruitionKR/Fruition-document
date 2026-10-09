package fruition.core.notification.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.service.DocumentService;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 편집 충돌 등록·해결이 남기는 앱 안 알림과 읽음 처리를 실제 DB와 HTTP로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired DocumentService documentService;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    final String workspace = "ws_" + UUID.randomUUID();
    final String author = "user_author_" + UUID.randomUUID();
    final String member = "user_member_" + UUID.randomUUID();
    final String owner = "user_owner_" + UUID.randomUUID();

    String documentId;

    @BeforeEach
    void setUp() {
        role(author, "MEMBER");
        role(member, "MEMBER");
        role(owner, "OWNER");
        documentId = documentService.createMarkdown(workspace, owner, UUID.randomUUID().toString(),
                new MarkdownDocumentCreateRequest("회의록", "# 처음", null)).id();
        documentService.saveContent(workspace, owner, documentId, "# 서버 본", 1L, "write_" + UUID.randomUUID(), null);
    }

    @Test
    void registeredConflictNotifiesOnlyOwnersOnce() throws Exception {
        String conflictId = registerConflict("client-1");
        registerConflict("client-1");

        list(owner, "")
                .andExpect(jsonPath("$.notifications.length()").value(1))
                .andExpect(jsonPath("$.notifications[0].type").value("edit_conflict_registered"))
                .andExpect(jsonPath("$.notifications[0].read").value(false))
                .andExpect(jsonPath("$.notifications[0].payload.conflict_id").value(conflictId))
                .andExpect(jsonPath("$.notifications[0].payload.document_id").value(documentId))
                .andExpect(jsonPath("$.notifications[0].payload.document_name").value("회의록"))
                .andExpect(jsonPath("$.notifications[0].payload.author_user_id").value(author));
        list(member, "").andExpect(jsonPath("$.notifications.length()").value(0));
        list(author, "").andExpect(jsonPath("$.notifications.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE workspace_id = ?",
                Integer.class, workspace)).isEqualTo(1);
    }

    @Test
    void resolvedConflictNotifiesAuthor() throws Exception {
        String conflictId = registerConflict("client-1");

        mockMvc.perform(post("/api/workspaces/" + workspace + "/conflicts/" + conflictId + "/resolve")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("choice", "server"))))
                .andExpect(status().isOk());

        list(author, "")
                .andExpect(jsonPath("$.notifications.length()").value(1))
                .andExpect(jsonPath("$.notifications[0].type").value("edit_conflict_resolved"))
                .andExpect(jsonPath("$.notifications[0].payload.conflict_id").value(conflictId))
                .andExpect(jsonPath("$.notifications[0].payload.document_id").value(documentId))
                .andExpect(jsonPath("$.notifications[0].payload.choice").value("server"))
                .andExpect(jsonPath("$.notifications[0].payload.resolved_by").value(owner));
        list(member, "").andExpect(jsonPath("$.notifications.length()").value(0));
        // OWNER에게는 등록 알림만 보인다.
        list(owner, "").andExpect(jsonPath("$.notifications.length()").value(1));
    }

    @Test
    void readAndReadAll() throws Exception {
        registerConflict("client-1");
        registerConflict("client-2");
        String latest = notificationId(owner, 0);

        read(owner, latest).andExpect(status().isNoContent());
        read(owner, latest).andExpect(status().isNoContent());
        list(owner, "")
                .andExpect(jsonPath("$.notifications[0].read").value(true))
                .andExpect(jsonPath("$.notifications[1].read").value(false));
        list(owner, "?unread_only=true").andExpect(jsonPath("$.notifications.length()").value(1));
        list(owner, "?limit=1").andExpect(jsonPath("$.notifications.length()").value(1));

        mockMvc.perform(post("/api/workspaces/" + workspace + "/notifications/read-all")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        list(owner, "?unread_only=true").andExpect(jsonPath("$.notifications.length()").value(0));
    }

    @Test
    void cannotReadSomeoneElsesNotification() throws Exception {
        registerConflict("client-1");
        String ownerNotification = notificationId(owner, 0);

        read(member, ownerNotification)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOTIFICATION_NOT_FOUND"));
        read(owner, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_reads WHERE user_id = ?",
                Integer.class, member)).isZero();
    }

    private String registerConflict(String clientConflictId) throws Exception {
        String body = mockMvc.perform(post("/api/workspaces/" + workspace + "/documents/" + documentId + "/conflicts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "markdown", "# 작성자 본", "base_revision", 1, "client_conflict_id", clientConflictId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("id").asText();
    }

    private ResultActions list(String userId, String query) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + workspace + "/notifications" + query)
                        .header("Authorization", bearer(userId)))
                .andExpect(status().isOk());
    }

    private ResultActions read(String userId, String notificationId) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + workspace + "/notifications/" + notificationId + "/read")
                .header("Authorization", bearer(userId)));
    }

    private String notificationId(String userId, int index) throws Exception {
        JsonNode body = objectMapper.readTree(list(userId, "").andReturn().getResponse().getContentAsString());
        return body.path("notifications").path(index).path("id").asText();
    }

    private void role(String userId, String role) {
        redis.opsForValue().set("authz:role:" + workspace + ":" + userId, role);
    }

    private String bearer(String userId) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com");
    }
}
