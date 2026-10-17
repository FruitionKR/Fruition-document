package fruition.core.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.service.DocumentEditStateInitializer;
import fruition.core.skill.dto.SkillTestRunRequest;
import fruition.core.skill.exception.InvalidSkillTestRunException;
import fruition.core.skill.repository.PipelineSkillRequester;
import org.springframework.stereotype.Service;

/**
 * 게시 전 Skill 정의를 문서 하나에 저장하지 않고 적용해 본다(#143). 권한·문서 본문·모델을 확인해 AI에 넘길 뿐
 * 문서·Skill·Agent run을 만들거나 바꾸지 않는다. 문서는 워크스페이스 멤버면 누구나 읽을 수 있어(편집 권한 설정은
 * 쓰기만 막는다) 멤버십과 문서 존재로 읽기 권한을 판정하고, 저장하지 않으므로 팀 Skill 게시 권한은 보지 않는다.
 */
@Service
public class SkillTestRunService {

    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final DocumentRepository documentRepository;
    private final DocumentEditStateRepository editStateRepository;
    private final DocumentEditStateInitializer editStateInitializer;
    private final WorkspaceAiModelClient workspaceAiModelClient;
    private final PipelineSkillRequester requester;

    public SkillTestRunService(WorkspaceAccessGuard workspaceAccessGuard, DocumentRepository documentRepository,
                               DocumentEditStateRepository editStateRepository,
                               DocumentEditStateInitializer editStateInitializer,
                               WorkspaceAiModelClient workspaceAiModelClient, PipelineSkillRequester requester) {
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.documentRepository = documentRepository;
        this.editStateRepository = editStateRepository;
        this.editStateInitializer = editStateInitializer;
        this.workspaceAiModelClient = workspaceAiModelClient;
        this.requester = requester;
    }

    public JsonNode testRun(String workspaceId, String userId, SkillTestRunRequest request, String runId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        var document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(request.documentId(), workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(request.documentId()));
        if (document.getDocumentRole() == DocumentRole.ORIGINAL) {
            throw new InvalidSkillTestRunException("Markdown 문서만 테스트 실행할 수 있습니다.");
        }
        editStateInitializer.initializeIfNeeded(document);
        DocumentEditState state = editStateRepository.findById(document.getId())
                .orElseThrow(() -> new DocumentNotFoundException(request.documentId()));
        String markdown = request.markdown() != null ? request.markdown() : state.getMarkdown();
        validateTarget(request.target(), markdown);
        return requester.testRun(workspaceId, userId, request, state.getRevision(), markdown,
                workspaceAiModelClient.get(workspaceId), runId);
    }

    private void validateTarget(SkillTestRunRequest.Target target, String markdown) {
        if (target == null) return;
        int lineCount = markdown.split("\\n", -1).length;
        if (target.endLine() < target.startLine() || target.endLine() > lineCount) {
            throw new InvalidSkillTestRunException("적용 범위가 올바르지 않습니다.");
        }
    }
}
