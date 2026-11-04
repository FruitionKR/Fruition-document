package fruition.core.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.agent.dto.AgentAutoApprovalSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAutoApprovalPolicyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FOLDER = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_FOLDER = "22222222-2222-2222-2222-222222222222";
    private static final String MOVED_FOLDER = "33333333-3333-3333-3333-333333333333";
    private static final AgentAutoApprovalSettings ALL_ON = new AgentAutoApprovalSettings(true, true);
    private static final AgentAutoApprovalSettings ORGANIZE_ONLY = new AgentAutoApprovalSettings(false, true);
    private static final AgentAutoApprovalSettings CONTENT_ONLY = new AgentAutoApprovalSettings(true, false);

    /** 모든 대상을 편집할 수 있는 사용자. */
    private static final AgentAutoApprovalPolicy.TargetPermissions EDITOR = permissions(Set.of(), Set.of());

    private static AgentAutoApprovalPolicy.TargetPermissions permissions(Set<String> forbiddenDocuments,
                                                                        Set<String> forbiddenFolders) {
        return permissions(forbiddenDocuments, forbiddenFolders, null);
    }

    /** @param current 옮길 문서·폴더가 지금 있는 폴더. null이면 최상위. */
    private static AgentAutoApprovalPolicy.TargetPermissions permissions(Set<String> forbiddenDocuments,
                                                                        Set<String> forbiddenFolders,
                                                                        String current) {
        return new AgentAutoApprovalPolicy.TargetPermissions() {
            @Override
            public boolean canEditDocument(String documentId) {
                return !forbiddenDocuments.contains(documentId);
            }

            @Override
            public boolean canEditFolder(UUID folderId) {
                return folderId == null || !forbiddenFolders.contains(folderId.toString());
            }

            @Override
            public UUID folderOfDocument(String documentId) {
                return current == null ? null : UUID.fromString(current);
            }

            @Override
            public UUID parentOfFolder(UUID folderId) {
                return current == null ? null : UUID.fromString(current);
            }
        };
    }

    private static JsonNode run(String... operations) throws Exception {
        return MAPPER.readTree("""
                {"id":"run-1","status":"awaiting_approval","plan":{"version":3,"operation_hash":"%s",
                 "status":"awaiting_approval","operations":[%s]}}
                """.formatted("h".repeat(64), String.join(",", operations)));
    }

    private static String moveDocument(String documentId, String folderId) {
        return """
                {"id":"op_move","tool_name":"move_document","target_id":"%s",
                 "arguments":{"document_id":"%s","folder_id":"%s","position":null,"base_version":1}}
                """.formatted(documentId, documentId, folderId);
    }

    private static String editDocument(String documentId) {
        return """
                {"id":"op_edit","tool_name":"apply_document_edit","target_id":"%s","arguments":{"document_id":"%s"}}
                """.formatted(documentId, documentId);
    }

    @Test
    void defaultSettingsKeepManualApproval() throws Exception {
        var decision = AgentAutoApprovalPolicy.decide(AgentAutoApprovalSettings.DISABLED,
                run(moveDocument("doc_1", FOLDER)), EDITOR);

        assertThat(decision.autoApprove()).isFalse();
        assertThat(decision.reason()).isEqualTo("disabled");
    }

    @Test
    void enabledCategoryAutoApprovesTheReviewedPlanVersionAndHash() throws Exception {
        var decision = AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY, run(moveDocument("doc_1", FOLDER)), EDITOR);

        assertThat(decision.autoApprove()).isTrue();
        assertThat(decision.planVersion()).isEqualTo(3);
        assertThat(decision.operationHash()).isEqualTo("h".repeat(64));
    }

    @Test
    void disabledCategoryStillRequiresApprovalEvenWhenOtherOperationsAreEnabled() throws Exception {
        var organizeOnly = AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY,
                run(moveDocument("doc_1", FOLDER), editDocument("doc_2")), EDITOR);
        var contentOnly = AgentAutoApprovalPolicy.decide(CONTENT_ONLY, run(moveDocument("doc_1", FOLDER)), EDITOR);

        assertThat(organizeOnly.autoApprove()).isFalse();
        assertThat(organizeOnly.reason()).isEqualTo("category_disabled");
        assertThat(contentOnly.autoApprove()).isFalse();
        assertThat(AgentAutoApprovalPolicy.decide(CONTENT_ONLY, run(editDocument("doc_2")), EDITOR).autoApprove())
                .isTrue();
    }

    /** 삭제(휴지통)·빈 폴더 삭제처럼 분류에 없는 도구와 앞으로 생길 도구는 모두 켜도 수동이다. */
    @ParameterizedTest
    @ValueSource(strings = {"delete_document", "delete_folder", "purge_document", "share_document"})
    void toolsOutsideKnownCategoriesAreNeverAutoApproved(String toolName) throws Exception {
        var decision = AgentAutoApprovalPolicy.decide(ALL_ON, run(moveDocument("doc_1", FOLDER), """
                {"id":"op_x","tool_name":"%s","target_id":"doc_1","arguments":{"document_id":"doc_1"}}
                """.formatted(toolName)), EDITOR);

        assertThat(decision.autoApprove()).isFalse();
        assertThat(decision.reason()).isEqualTo("unsupported_tool");
    }

    @Test
    void autoApprovalNeverBypassesTargetPermissions() throws Exception {
        var forbiddenDocument = AgentAutoApprovalPolicy.decide(ALL_ON,
                run(moveDocument("doc_1", FOLDER)), permissions(Set.of("doc_1"), Set.of()));
        var forbiddenDestination = AgentAutoApprovalPolicy.decide(ALL_ON,
                run(moveDocument("doc_1", OTHER_FOLDER)), permissions(Set.of(), Set.of(OTHER_FOLDER)));
        var forbiddenEdit = AgentAutoApprovalPolicy.decide(ALL_ON,
                run(editDocument("doc_2")), permissions(Set.of("doc_2"), Set.of()));

        assertThat(forbiddenDocument.autoApprove()).isFalse();
        assertThat(forbiddenDocument.reason()).isEqualTo("forbidden");
        assertThat(forbiddenDestination.autoApprove()).isFalse();
        assertThat(forbiddenEdit.autoApprove()).isFalse();
    }

    @Test
    void folderOperationsCheckTargetAndParentAndAcceptFoldersCreatedByThePlan() throws Exception {
        String createFolder = """
                {"id":"op_1","tool_name":"create_folder","target_id":null,
                 "arguments":{"name":"2026","parent_folder_id":"%s"}}
                """.formatted(FOLDER);
        String moveIntoNew = """
                {"id":"op_2","tool_name":"move_document","target_id":"doc_1","depends_on":["op_1"],
                 "arguments":{"document_id":"doc_1","folder_id":{"$operation_result":"op_1"},"base_version":1}}
                """;
        String renameFolder = """
                {"id":"op_3","tool_name":"rename_folder","target_id":"%s","arguments":{"folder_id":"%s","name":"x"}}
                """.formatted(OTHER_FOLDER, OTHER_FOLDER);

        assertThat(AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY, run(createFolder, moveIntoNew, renameFolder), EDITOR)
                .autoApprove()).isTrue();
        assertThat(AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY, run(createFolder, moveIntoNew),
                permissions(Set.of(), Set.of(FOLDER))).autoApprove()).isFalse();
        assertThat(AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY, run(renameFolder),
                permissions(Set.of(), Set.of(OTHER_FOLDER))).autoApprove()).isFalse();
    }

    private static String moveFolder(String parent) {
        return """
                {"id":"op_move","tool_name":"move_folder","target_id":"%s",
                 "arguments":{"folder_id":"%s","parent_folder_id":%s,"position":null,"base_version":1}}
                """.formatted(MOVED_FOLDER, MOVED_FOLDER, parent == null ? "null" : "\"" + parent + "\"");
    }

    private static String moveDocumentToRoot(String documentId) {
        return """
                {"id":"op_move","tool_name":"move_document","target_id":"%s",
                 "arguments":{"document_id":"%s","folder_id":null,"position":null,"base_version":1}}
                """.formatted(documentId, documentId);
    }

    /** 실행 규칙과 같다. 문서 소유자라 문서를 편집할 수 있어도 출발 폴더를 편집할 수 없으면 꺼낼 수 없다. */
    @Test
    void moveDocumentChecksSourceFolder() throws Exception {
        var outOfView = permissions(Set.of(), Set.of(FOLDER), FOLDER);

        var toRoot = AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY, run(moveDocumentToRoot("doc_1")), outOfView);
        var toOther = AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY, run(moveDocument("doc_1", OTHER_FOLDER)), outOfView);

        assertThat(toRoot.autoApprove()).isFalse();
        assertThat(toRoot.reason()).isEqualTo("forbidden");
        assertThat(toOther.reason()).isEqualTo("forbidden");
        assertThat(AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY, run(moveDocumentToRoot("doc_1")),
                permissions(Set.of(), Set.of(OTHER_FOLDER), FOLDER)).autoApprove()).isTrue();
    }

    @Test
    void moveFolderChecksItselfSourceAndDestination() throws Exception {
        var sourceForbidden = permissions(Set.of(), Set.of(FOLDER), FOLDER);
        var destinationForbidden = permissions(Set.of(), Set.of(OTHER_FOLDER), FOLDER);
        var selfForbidden = permissions(Set.of(), Set.of(MOVED_FOLDER), FOLDER);

        assertThat(AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY, run(moveFolder(null)), sourceForbidden).reason())
                .isEqualTo("forbidden");
        assertThat(AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY, run(moveFolder(OTHER_FOLDER)), destinationForbidden).reason())
                .isEqualTo("forbidden");
        assertThat(AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY, run(moveFolder(OTHER_FOLDER)), selfForbidden).reason())
                .isEqualTo("forbidden");
        assertThat(AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY, run(moveFolder(OTHER_FOLDER)),
                permissions(Set.of(), Set.of(), FOLDER)).autoApprove()).isTrue();
    }

    /** 같은 폴더 안 순서 변경은 넣기·꺼내기가 아니라 실행 때처럼 대상의 권한만 본다. */
    @Test
    void reorderWithinSameFolderChecksOnlyTheTarget() throws Exception {
        var viewFolder = permissions(Set.of(), Set.of(FOLDER), FOLDER);

        assertThat(AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY, run(moveDocument("doc_1", FOLDER)), viewFolder).autoApprove())
                .isTrue();
        assertThat(AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY, run(moveFolder(FOLDER)), viewFolder).autoApprove())
                .isTrue();
        assertThat(AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY, run(moveDocument("doc_1", FOLDER)),
                permissions(Set.of("doc_1"), Set.of(FOLDER), FOLDER)).autoApprove()).isFalse();
    }

    /** 같은 계획이 만든 폴더로 옮겨도 출발 폴더는 본다. */
    @Test
    void movingIntoPlannedFolderStillChecksSource() throws Exception {
        String moveIntoNew = """
                {"id":"op_2","tool_name":"move_document","target_id":"doc_1",
                 "arguments":{"document_id":"doc_1","folder_id":{"$operation_result":"op_1"},"base_version":1}}
                """;

        assertThat(AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY, run(moveIntoNew),
                permissions(Set.of(), Set.of(FOLDER), FOLDER)).reason()).isEqualTo("forbidden");
        assertThat(AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY, run(moveIntoNew), EDITOR).autoApprove()).isTrue();
    }

    @Test
    void createDocumentAtRootChecksRootAndNeedsContentCategory() throws Exception {
        String createAtRoot = """
                {"id":"op_1","tool_name":"create_document","target_id":null,
                 "arguments":{"display_name":"요약","folder_id":null,"content_artifact_id":"a","content_hash":"h"}}
                """;

        assertThat(AgentAutoApprovalPolicy.decide(CONTENT_ONLY, run(createAtRoot), EDITOR).autoApprove()).isTrue();
        assertThat(AgentAutoApprovalPolicy.decide(ORGANIZE_ONLY, run(createAtRoot), EDITOR).autoApprove()).isFalse();
    }

    /** AI 검토가 중복 경고를 달았거나 기본 선택에서 뺀 작업이 있으면 사람이 골라야 한다. */
    @Test
    void plansWithReviewWarningsStayManual() throws Exception {
        String warned = """
                {"id":"op_w","tool_name":"move_document","target_id":"doc_1",
                 "arguments":{"document_id":"doc_1","folder_id":"%s"},
                 "warning":{"type":"duplicate_content","message":"내용이 거의 같은 문서가 있어요."},
                 "suggested_selected":false}
                """.formatted(FOLDER);
        String unselected = """
                {"id":"op_u","tool_name":"move_document","target_id":"doc_2",
                 "arguments":{"document_id":"doc_2","folder_id":"%s"},"suggested_selected":false}
                """.formatted(FOLDER);

        assertThat(AgentAutoApprovalPolicy.decide(ALL_ON, run(warned), EDITOR).reason()).isEqualTo("needs_review");
        assertThat(AgentAutoApprovalPolicy.decide(ALL_ON, run(unselected), EDITOR).autoApprove()).isFalse();
    }

    @Test
    void onlyPlansAwaitingApprovalAreConsidered() throws Exception {
        JsonNode executing = MAPPER.readTree("""
                {"id":"run-1","status":"executing","plan":{"version":1,"operation_hash":"h","status":"approved",
                 "operations":[{"id":"op","tool_name":"move_document","target_id":"doc_1","arguments":{}}]}}
                """);
        JsonNode noPlan = MAPPER.readTree("{\"id\":\"run-1\",\"status\":\"awaiting_approval\"}");

        assertThat(AgentAutoApprovalPolicy.decide(ALL_ON, executing, EDITOR).reason()).isEqualTo("not_awaiting_approval");
        assertThat(AgentAutoApprovalPolicy.decide(ALL_ON, noPlan, EDITOR).autoApprove()).isFalse();
    }
}
