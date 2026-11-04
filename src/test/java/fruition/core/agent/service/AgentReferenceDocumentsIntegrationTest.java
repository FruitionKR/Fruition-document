package fruition.core.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.agent.dto.AgentTurnRequest;
import fruition.core.agent.dto.AgentTurnResponse;
import fruition.core.agent.exception.InvalidAgentTurnRequestException;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.DocumentService;
import fruition.core.skill.exception.SkillReferenceDocumentTooLargeException;
import fruition.core.skill.service.SkillReferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

/** reference_document_ids가 실제 DB의 문서·권한으로 판정되어 ai command까지 가는 경로와, AI가 본문을 읽는 내부 API. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AgentReferenceDocumentsIntegrationTest {

    private static final String AUTHOR = "user_reference_author";
    private static final String REJECTED = "고른 문서 중 사용할 수 없는 문서가 있습니다. 다시 골라 주세요.";

    @Autowired JdbcTemplate jdbc;
    @Autowired AgentTurnService turns;
    @Autowired SkillReferenceService references;
    @Autowired DocumentEditStateRepository editStates;
    @Autowired DocumentService documents;
    @Autowired ObjectMapper mapper;
    @Autowired StringRedisTemplate redisTemplate;
    @SpyBean DocumentAccessPolicy accessPolicy;

    private String workspace;
    private String user;
    private String session;

    @BeforeEach
    void setUp() {
        workspace = "ws_" + UUID.randomUUID();
        user = "user_" + UUID.randomUUID();
        session = "session_" + UUID.randomUUID();
        redisTemplate.opsForValue().set("authz:role:v2:" + workspace + ":" + user,
                "{\"role\":\"MEMBER\",\"permissions\":[]}");
        jdbc.update("INSERT INTO chat_sessions(id, workspace_id, user_id, created_at) VALUES (?, ?, ?, now())",
                session, workspace, user);
    }

    @Test
    void commandCarriesValidatedIdsWithoutBody() throws Exception {
        String first = markdown().insert();
        String second = markdown().insert();
        saveBody(first, "# 첨부 본문 표식");

        JsonNode command = command(turn(List.of(first, second)));

        assertThat(mapper.convertValue(command.path("reference_document_ids"), List.class))
                .containsExactly(first, second);
        assertThat(command.toString()).doesNotContain("첨부 본문 표식");
    }

    @Test
    void commandCarriesEmptyArrayWhenNothingAttached() throws Exception {
        JsonNode ids = command(turn(List.of())).path("reference_document_ids");

        assertThat(ids.isArray()).isTrue();
        assertThat(ids).isEmpty();
    }

    @Test
    void viewOnlyDocumentPassesAndItsBodyIsReadable() {
        String document = markdown().insert();
        jdbc.update("INSERT INTO document_permissions(workspace_id, document_id, access, updated_by) VALUES (?, ?, 'view', ?)",
                workspace, document, AUTHOR);
        saveBody(document, "# 보기 전용 본문");

        assertThat(turn(List.of(document)).status()).isEqualTo("queued");
        assertThat(references.read(workspace, user, document, null).markdown()).isEqualTo("# 보기 전용 본문");
    }

    /** turn은 긴 문서를 거절하지 않고, AI는 max_chars로 앞부분을 읽는다. 없으면 skill 경로처럼 413이다. */
    @Test
    void longDocumentPassesAndIsReadTruncatedOnlyWithMaxChars() {
        String document = markdown().insert();
        saveBody(document, "가".repeat(30_001));

        assertThat(turn(List.of(document)).status()).isEqualTo("queued");
        var truncated = references.read(workspace, user, document, 100);
        assertThat(truncated.markdown()).hasSize(100);
        assertThat(truncated.truncated()).isTrue();
        assertThat(truncated.totalChars()).isEqualTo(30_001);
        assertThatThrownBy(() -> references.read(workspace, user, document, null))
                .isInstanceOf(SkillReferenceDocumentTooLargeException.class);
    }

    @Test
    void documentViewOnlyThroughParentFolderPasses() {
        UUID folder = UUID.randomUUID();
        jdbc.update("INSERT INTO folders(id, workspace_id, name, sort_order, created_at, updated_at) VALUES (?, ?, ?, 0, now(), now())",
                folder, workspace, "폴더-" + folder);
        jdbc.update("INSERT INTO document_permissions(workspace_id, folder_id, access, updated_by) VALUES (?, ?, 'view', ?)",
                workspace, folder, AUTHOR);
        String document = markdown().folder(folder).insert();

        assertThat(turn(List.of(document)).status()).isEqualTo("queued");
    }

    /** 회의록 저장(MeetingNotesService)과 같은 생성 경로로 만들어 실제 역할·본문으로 판정한다. */
    @Test
    void savedMeetingNotePassesAndItsBodyIsReadable() {
        String document = documents.createMarkdown(workspace, user, UUID.randomUUID().toString(),
                new MarkdownDocumentCreateRequest("회의록", "# 회의 내용", null), Document.MEETING_NOTES_ORIGIN).id();

        assertThat(turn(List.of(document)).status()).isEqualTo("queued");
        var read = references.read(workspace, user, document, null);
        assertThat(read.markdown()).contains("# 회의 내용");
        assertThat(read.displayName()).isEqualTo("회의록");
    }

    /** 채팅 내보내기 문서는 본문 편집만 잠겨 있다. 읽을 수 있으므로 근거로 쓸 수 있다. */
    @Test
    void chatExportDocumentPassesAndItsBodyIsReadable() {
        String document = markdown().origin("chat_export").insert();
        saveBody(document, "# 내보낸 대화");

        assertThat(turn(List.of(document)).status()).isEqualTo("queued");
        assertThat(references.read(workspace, user, document, null).markdown()).isEqualTo("# 내보낸 대화");
    }

    @Test
    void finishedPdfConversionPasses() {
        String document = markdown().origin("convert").insert();

        assertThat(turn(List.of(document)).status()).isEqualTo("queued");
    }

    /** 변환 뒤 ingest가 실패해도 변환 본문은 그대로다. 사용자가 보는 본문으로 답할 수 있다. */
    @Test
    void conversionWithBodyPassesEvenIfProcessingFailedLater() {
        String document = markdown().origin("convert").status("failed").insert();
        saveBody(document, "# 변환된 본문");

        assertThat(turn(List.of(document)).status()).isEqualTo("queued");
    }

    @Test
    void conversionInProgressIsRejected() {
        String document = markdown().origin("convert").status("processing").runId("convert:run").insert();
        saveBody(document, Document.CONVERT_PLACEHOLDER_MARKDOWN);

        assertRejected(List.of(document));
    }

    @Test
    void conversionFailedBeforeAnyBodyIsRejected() {
        String document = markdown().origin("convert").status("failed").insert();
        saveBody(document, Document.CONVERT_PLACEHOLDER_MARKDOWN);

        assertRejected(List.of(document));
    }

    @Test
    void originalPdfIsRejected() {
        String document = new Row().filename("a.pdf").mimeType("application/pdf").role("ORIGINAL").insert();

        assertRejected(List.of(document));
    }

    @Test
    void documentOfAnotherWorkspaceIsRejected() {
        String document = markdown().workspace("ws_" + UUID.randomUUID()).insert();

        assertRejected(List.of(document));
    }

    @Test
    void deletedDocumentIsRejected() {
        String document = markdown().insert();
        jdbc.update("UPDATE documents SET deleted_at = now() WHERE id = ?", document);

        assertRejected(List.of(document));
    }

    /** 지금 저장되는 값은 edit·view뿐이라 멤버는 다 읽는다. 읽기 판정이 닫히는 값이 생겨도 turn과 본문 읽기가 함께 막혀야 한다. */
    @Test
    void documentUserCannotReadIsRejectedAndItsBodyIsNotReturned() {
        String document = markdown().insert();
        saveBody(document, "# 읽으면 안 되는 본문");
        doReturn(new DocumentAccessPolicy.Viewer(user, false, false, Map.of(document, "none"), Map.of(), Map.of()))
                .when(accessPolicy).viewer(workspace, user);

        assertRejected(List.of(document));
        assertThatThrownBy(() -> references.read(workspace, user, document, null))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> references.read(workspace, user, document, 100))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    /** 앞의 문서가 괜찮아도 하나가 어긋나면 조용히 빼지 않고 요청 전체를 거절한다. */
    @Test
    void oneUnusableDocumentRejectsWholeRequest() {
        assertRejected(List.of(markdown().insert(), "doc_missing"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"doc_missing", "doc_\u0000nul", "doc_\ud800lone", "' OR 1=1 --", "../doc_1"})
    void missingOrMalformedIdIsRejectedWithSameMessage(String id) {
        assertRejected(List.of(id));
    }

    @Test
    void oversizedIdIsRejectedWithSameMessage() {
        assertRejected(List.of("d".repeat(100_000)));
    }

    private void assertRejected(List<String> ids) {
        assertThatThrownBy(() -> turn(ids))
                .isInstanceOf(InvalidAgentTurnRequestException.class)
                .hasMessage(REJECTED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_command_outbox WHERE message_key = ?", Integer.class, session))
                .as("거절한 요청은 command를 남기지 않는다").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE session_id = ?", Integer.class, session))
                .as("거절한 요청은 말풍선을 남기지 않는다").isZero();
    }

    private AgentTurnResponse turn(List<String> ids) {
        return turns.turn(workspace, user, new AgentTurnRequest(session, null, null, "참고해서 답해줘", "openai", "gpt-6-luna",
                null, "auto", null, null, List.of(), List.of(), List.of(), null, null, "task", null, null, ids));
    }

    private JsonNode command(AgentTurnResponse response) throws Exception {
        return mapper.readTree(jdbc.queryForObject("SELECT payload FROM ai_command_outbox WHERE run_id = ?", String.class,
                response.requestId()));
    }

    private void saveBody(String documentId, String markdown) {
        editStates.save(new DocumentEditState(documentId, markdown, "hash", 1));
    }

    private Row markdown() {
        return new Row();
    }

    /** 문서 행 하나. 기본값은 이 워크스페이스의 다른 사람이 만든 처리 완료 Markdown 문서다. */
    private final class Row {
        private String workspaceId = workspace;
        private String filename = "note.md";
        private String mimeType = "text/markdown";
        private String role = "EDITABLE";
        private String status = "completed";
        private String origin;
        private String runId;
        private UUID folder;

        Row workspace(String value) { workspaceId = value; return this; }
        Row filename(String value) { filename = value; return this; }
        Row mimeType(String value) { mimeType = value; return this; }
        Row role(String value) { role = value; return this; }
        Row status(String value) { status = value; return this; }
        Row origin(String value) { origin = value; return this; }
        Row runId(String value) { runId = value; return this; }
        Row folder(UUID value) { folder = value; return this; }

        String insert() {
            String id = "doc_" + UUID.randomUUID().toString().replace("-", "");
            String uniqueName = id + "-" + filename;
            jdbc.update("""
                    INSERT INTO documents(
                        id, byte_size, content_hash, filename, display_name, normalized_filename,
                        mime_type, status, uploaded_at, updated_at, user_id, workspace_id,
                        current_content_hash, current_version, document_role, sort_order, folder_id,
                        origin, pipeline_run_id
                    ) VALUES (?, 1, 'hash', ?, 'a', ?, ?, ?, now(), now(), ?, ?, 'hash', 1, ?, 0, ?, ?, ?)
                    """, id, uniqueName, uniqueName, mimeType, status, AUTHOR, workspaceId, role, folder,
                    origin, runId);
            return id;
        }
    }
}
