package fruition.core.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.service.DocumentEditLockService;
import fruition.core.document.service.DocumentService;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 열린 문서가 잠겨 turn이 거절될 때 응답이 어느 문서인지 말하는지 실제 DB와 HTTP 응답으로 본다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AgentTurnLockedDocumentIntegrationTest {

    private static final String BODY = "# 제목\n\n본문";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired DocumentService documents;
    @Autowired DocumentEditLockService locks;
    @Autowired JwtTokenProvider jwt;
    @MockBean PipelineWikiStateRequester wikiState;

    private final String workspace = "ws_" + UUID.randomUUID();
    private final String holder = "user_holder_" + UUID.randomUUID();
    private final String requester = "user_requester_" + UUID.randomUUID();
    private final String session = "session_" + UUID.randomUUID();
    private String opened;
    private String attached;

    @BeforeEach
    void setUp() {
        role(holder);
        role(requester);
        when(wikiState.documentContext(anyString(), anyString()))
                .thenReturn(new PipelineWikiStateRequester.DocumentWikiContext(List.of(), List.of(), null));
        jdbc.update("INSERT INTO chat_sessions(id, workspace_id, user_id, created_at) VALUES (?, ?, ?, now())",
                session, workspace, requester);
        opened = markdown("잠긴 회의록");
        attached = markdown("첨부 문서");
    }

    @Test
    void lockedOpenDocumentIsRejectedWithItsNameEvenWhenEditingAttachment() throws Exception {
        locks.acquire(workspace, holder, opened);

        turn(opened, List.of(attached))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_EDIT_LOCKED"))
                .andExpect(jsonPath("$.error.locked_document_name").value("잠긴 회의록"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE session_id = ?", Long.class, session))
                .isZero();
    }

    @Test
    void lockedAttachmentAloneDoesNotRejectTurn() throws Exception {
        locks.acquire(workspace, holder, attached);

        turn(opened, List.of(attached)).andExpect(status().isAccepted());
    }

    private ResultActions turn(String documentId, List<String> references) throws Exception {
        long version = jdbc.queryForObject("SELECT current_version FROM documents WHERE id = ?", Long.class, documentId);
        return mockMvc.perform(post("/api/workspaces/" + workspace + "/agent/turn")
                .header("Authorization", bearer(requester))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "session_id", session,
                        "message", "첨부 문서를 고쳐 줘",
                        "mode", "task",
                        "documentId", documentId,
                        "baseVersion", version,
                        "editorSnapshot", Map.of("markdown", BODY),
                        "reference_document_ids", references))));
    }

    private String markdown(String name) {
        return documents.createMarkdown(workspace, holder, UUID.randomUUID().toString(),
                new MarkdownDocumentCreateRequest(name, BODY, null)).id();
    }

    private void role(String userId) {
        redis.opsForValue().set("authz:role:v2:" + workspace + ":" + userId, "{\"role\":\"MEMBER\",\"permissions\":[]}");
    }

    private String bearer(String userId) {
        return "Bearer " + jwt.generateAccessToken(userId, userId + "@example.com");
    }
}
