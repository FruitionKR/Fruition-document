package fruition.core.skill.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.service.DocumentEditStateInitializer;
import fruition.core.skill.dto.SkillTestRunRequest;
import fruition.core.skill.exception.InvalidSkillTestRunException;
import fruition.core.skill.repository.PipelineSkillRequester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillTestRunServiceTest {

    private static final WorkspaceAiModelClient.AiModelSelection MODEL =
            new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-6-luna");

    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock DocumentRepository documentRepository;
    @Mock DocumentEditStateRepository editStateRepository;
    @Mock DocumentEditStateInitializer editStateInitializer;
    @Mock WorkspaceAiModelClient workspaceAiModelClient;
    @Mock PipelineSkillRequester requester;
    @Mock Document document;
    private SkillTestRunService service;

    @BeforeEach
    void setUp() {
        service = new SkillTestRunService(workspaceAccessGuard, documentRepository, editStateRepository,
                editStateInitializer, workspaceAiModelClient, requester);
    }

    @Test
    void testRun_usesSavedMarkdownAndRevisionWhenEditorBodyIsMissing() {
        givenEditableDocument("# 연구 개요\n본문\n# 방법\n내용", 7);
        var request = request(null, null);

        service.testRun("ws_1", "user_1", request, "run_1");

        verify(requester).testRun("ws_1", "user_1", request, 7, "# 연구 개요\n본문\n# 방법\n내용", MODEL, "run_1");
    }

    @Test
    void testRun_prefersUnsavedEditorMarkdownAndValidatesTargetAgainstIt() {
        givenEditableDocument("# 저장본", 3);
        var request = request("# 편집 중\n한 줄\n두 줄", new SkillTestRunRequest.Target("selection", 2, 3));

        service.testRun("ws_1", "user_1", request, "run_1");

        verify(requester).testRun("ws_1", "user_1", request, 3, "# 편집 중\n한 줄\n두 줄", MODEL, "run_1");
    }

    @Test
    void testRun_rejectsTargetOutsideDocumentBeforeCallingAi() {
        givenEditableDocument("한 줄\n두 줄", 1);

        for (var target : List.of(new SkillTestRunRequest.Target("selection", 1, 3),
                new SkillTestRunRequest.Target("selection", 2, 1))) {
            assertThatThrownBy(() -> service.testRun("ws_1", "user_1", request(null, target), "run_1"))
                    .isInstanceOf(InvalidSkillTestRunException.class);
        }
        verifyNoInteractions(requester);
    }

    @Test
    void testRun_rejectsOriginalDocument() {
        when(document.getDocumentRole()).thenReturn(DocumentRole.ORIGINAL);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.testRun("ws_1", "user_1", request(null, null), "run_1"))
                .isInstanceOf(InvalidSkillTestRunException.class);
        verifyNoInteractions(requester, editStateInitializer);
    }

    @Test
    void testRun_missingOrOtherWorkspaceDocumentIsNotFound() {
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.testRun("ws_1", "user_1", request(null, null), "run_1"))
                .isInstanceOf(DocumentNotFoundException.class);
        verifyNoInteractions(requester);
    }

    @Test
    void testRun_requiresMembershipBeforeReadingDocument() {
        doThrow(new WorkspaceNotFoundException("ws_1"))
                .when(workspaceAccessGuard).requireMember("ws_1", "user_1");

        assertThatThrownBy(() -> service.testRun("ws_1", "user_1", request(null, null), "run_1"))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verifyNoInteractions(documentRepository, requester);
    }

    private void givenEditableDocument(String markdown, long revision) {
        when(document.getId()).thenReturn("doc_1");
        when(document.getDocumentRole()).thenReturn(DocumentRole.EDITABLE);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById("doc_1"))
                .thenReturn(Optional.of(new DocumentEditState("doc_1", markdown, "hash", revision)));
        org.mockito.Mockito.lenient().when(workspaceAiModelClient.get("ws_1")).thenReturn(MODEL);
    }

    private SkillTestRunRequest request(String markdown, SkillTestRunRequest.Target target) {
        return new SkillTestRunRequest("research-summary", "연구 요약", "'연구 개요' 섹션만 고친다.",
                List.of("document-edit"), "doc_1", markdown, target);
    }
}
