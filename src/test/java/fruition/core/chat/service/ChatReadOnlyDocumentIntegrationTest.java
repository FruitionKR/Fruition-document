package fruition.core.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fruition.TestcontainersConfiguration;
import fruition.core.agent.dto.AgentTurnRequest;
import fruition.core.agent.dto.AgentTurnResponse;
import fruition.core.agent.service.AgentTurnService;
import fruition.core.chat.controller.ChatSessionController;
import fruition.core.purge.DataPurgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** turn 요청의 read_only_document_id가 서버 확인을 거쳐 메시지 조회 JSON까지 오는 경로. 권한은 실제 DB 설정으로 판정한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChatReadOnlyDocumentIntegrationTest {

    private static final String AUTHOR = "user_readonly_author";

    @Autowired JdbcTemplate jdbc;
    @Autowired AgentTurnService turns;
    @Autowired ChatSessionController controller;
    @Autowired DataPurgeService purge;
    @Autowired ObjectMapper mapper;
    @Autowired StringRedisTemplate redisTemplate;

    private String workspace;
    private String user;
    private String session;

    @BeforeEach
    void setUp() {
        workspace = "ws_" + UUID.randomUUID();
        user = "user_" + UUID.randomUUID();
        session = "session_" + UUID.randomUUID();
        role(workspace, "MEMBER");
        jdbc.update("INSERT INTO chat_sessions(id, workspace_id, user_id, created_at) VALUES (?, ?, ?, now())",
                session, workspace, user);
    }

    @Test
    void viewOnlyDocumentIsStoredOnAssistantMessageAndSurvivesReload() throws Exception {
        String document = insertDocument(workspace, AUTHOR, null);
        setDocumentAccess(workspace, document, "view");

        turn(document);

        JsonNode messages = messages();
        assertThat(message(messages, "assistant").path("read_only_document_id").asText()).isEqualTo(document);
        assertThat(message(messages, "user").path("read_only_document_id").isNull()).isTrue();
    }

    @Test
    void documentViewOnlyThroughParentFolderIsStored() throws Exception {
        UUID folder = insertFolder(workspace);
        String document = insertDocument(workspace, AUTHOR, folder);
        jdbc.update("INSERT INTO document_permissions(workspace_id, folder_id, access, updated_by) VALUES (?, ?, 'view', ?)",
                workspace, folder, AUTHOR);

        turn(document);

        assertThat(storedValue().asText()).isEqualTo(document);
    }

    @Test
    void editableDocumentIsIgnored() throws Exception {
        turn(insertDocument(workspace, AUTHOR, null));

        assertThat(storedValue().isNull()).isTrue();
    }

    @Test
    void documentOwnerCanEditSoViewSettingIsIgnored() throws Exception {
        String document = insertDocument(workspace, user, null);
        setDocumentAccess(workspace, document, "view");

        turn(document);

        assertThat(storedValue().isNull()).isTrue();
    }

    @Test
    void workspaceOwnerCanEditSoViewSettingIsIgnored() throws Exception {
        role(workspace, "OWNER");
        String document = insertDocument(workspace, AUTHOR, null);
        setDocumentAccess(workspace, document, "view");

        turn(document);

        assertThat(storedValue().isNull()).isTrue();
    }

    @Test
    void documentOfAnotherWorkspaceIsIgnored() throws Exception {
        String other = "ws_" + UUID.randomUUID();
        String document = insertDocument(other, AUTHOR, null);
        setDocumentAccess(other, document, "view");

        turn(document);

        assertThat(storedValue().isNull()).isTrue();
    }

    @Test
    void deletedDocumentIsIgnored() throws Exception {
        String document = insertDocument(workspace, AUTHOR, null);
        setDocumentAccess(workspace, document, "view");
        jdbc.update("UPDATE documents SET deleted_at = now() WHERE id = ?", document);

        turn(document);

        assertThat(storedValue().isNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"doc_missing", "", "   ", "doc_\u0000nul", "doc_\ud800lone", "' OR 1=1 --", "../doc_1"})
    void missingOrMalformedIdIsIgnoredWithoutError(String id) throws Exception {
        assertThat(turn(id).status()).isEqualTo("queued");

        assertThat(storedValue().isNull()).isTrue();
    }

    @Test
    void oversizedIdIsIgnoredWithoutError() throws Exception {
        assertThat(turn("d".repeat(100_000)).status()).isEqualTo("queued");

        assertThat(storedValue().isNull()).isTrue();
    }

    /** 다른 워크스페이스 문서, 없는 문서, 진짜 보기 전용 문서의 응답이 같아야 존재 여부가 새지 않는다. */
    @Test
    void turnResponseDoesNotRevealWhetherDocumentExists() {
        String viewOnly = insertDocument(workspace, AUTHOR, null);
        setDocumentAccess(workspace, viewOnly, "view");
        String other = "ws_" + UUID.randomUUID();
        String foreign = insertDocument(other, AUTHOR, null);
        setDocumentAccess(other, foreign, "view");

        assertThat(List.of(comparable(turn(viewOnly)), comparable(turn(foreign)), comparable(turn("doc_missing"))))
                .allMatch(comparable(turn(null))::equals);
    }

    @Test
    void valueIsNotSentToAi() {
        String document = insertDocument(workspace, AUTHOR, null);
        setDocumentAccess(workspace, document, "view");

        AgentTurnResponse response = turn(document);

        String payload = jdbc.queryForObject("SELECT payload FROM ai_command_outbox WHERE run_id = ?", String.class,
                response.requestId());
        assertThat(payload).doesNotContain(document).doesNotContain("read_only");
    }

    /** 휴지통 영구 삭제(행 삭제) 뒤에도 대화는 그대로 읽힌다. FK가 없어 문서 삭제가 메시지에 막히지 않는다. */
    @Test
    void hardDeletedDocumentDoesNotBreakMessages() throws Exception {
        String document = insertDocument(workspace, AUTHOR, null);
        setDocumentAccess(workspace, document, "view");
        turn(document);

        assertThat(jdbc.update("DELETE FROM documents WHERE id = ?", document)).isOne();

        assertThat(message(messages(), "assistant").path("status").asText()).isNotBlank();
        assertThat(storedValue().asText()).isEqualTo(document);
    }

    @Test
    void userPurgeRemovesStoredValueWithSession() {
        String document = insertDocument(workspace, AUTHOR, null);
        setDocumentAccess(workspace, document, "view");
        turn(document);

        purge.purgeUser(user);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE read_only_document_id = ?",
                Integer.class, document)).isZero();
    }

    @Test
    void workspacePurgeRemovesStoredValueWithSession() {
        String document = insertDocument(workspace, AUTHOR, null);
        setDocumentAccess(workspace, document, "view");
        turn(document);

        purge.purgeWorkspace(workspace);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE read_only_document_id = ?",
                Integer.class, document)).isZero();
    }

    private AgentTurnResponse turn(String readOnlyDocumentId) {
        return turns.turn(workspace, user, new AgentTurnRequest(session, null, null, "수정해줘", "openai", "gpt-6-luna",
                null, "auto", null, null, List.of(), List.of(), List.of(), null, null, "task", null, readOnlyDocumentId));
    }

    private JsonNode comparable(AgentTurnResponse response) {
        ObjectNode node = mapper.valueToTree(response);
        node.remove("requestId");
        return node;
    }

    private JsonNode storedValue() throws Exception {
        return message(messages(), "assistant").path("read_only_document_id");
    }

    private JsonNode message(JsonNode messages, String role) {
        JsonNode found = null;
        for (JsonNode message : messages) {
            if (role.equals(message.path("role").asText())) found = message;
        }
        assertThat(found).as(role + " 메시지").isNotNull();
        return found;
    }

    private JsonNode messages() throws Exception {
        return mapper.readTree(mapper.writeValueAsString(controller.getMessages(workspace, user, session).getBody()))
                .path("messages");
    }

    private void role(String workspaceId, String role) {
        redisTemplate.opsForValue().set("authz:role:v2:" + workspaceId + ":" + user,
                "{\"role\":\"" + role + "\",\"permissions\":[]}");
    }

    private void setDocumentAccess(String workspaceId, String documentId, String access) {
        jdbc.update("INSERT INTO document_permissions(workspace_id, document_id, access, updated_by) VALUES (?, ?, ?, ?)",
                workspaceId, documentId, access, AUTHOR);
    }

    private UUID insertFolder(String workspaceId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO folders(id, workspace_id, name, sort_order, created_at, updated_at) VALUES (?, ?, ?, 0, now(), now())",
                id, workspaceId, "폴더-" + id);
        return id;
    }

    private String insertDocument(String workspaceId, String owner, UUID folder) {
        String id = "doc_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO documents(
                    id, byte_size, content_hash, filename, display_name, normalized_filename,
                    mime_type, status, uploaded_at, updated_at, user_id, workspace_id,
                    current_content_hash, current_version, document_role, sort_order, folder_id
                ) VALUES (?, 1, 'hash', ?, 'a', ?, 'text/markdown', 'completed', now(), now(), ?, ?,
                          'hash', 1, 'ORIGINAL', 0, ?)
                """, id, id + ".md", id + ".md", owner, workspaceId, folder);
        return id;
    }
}
