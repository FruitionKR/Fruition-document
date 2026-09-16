package fruition.core.skill.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.service.DocumentEditStateInitializer;
import fruition.core.skill.dto.SkillReferenceReadResponse;
import fruition.core.skill.exception.SkillReferenceDocumentTooLargeException;
import org.springframework.stereotype.Service;

@Service
public class SkillReferenceService {

    private static final int MAX_EDITABLE_MARKDOWN_CHARACTERS = 30000;

    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final DocumentRepository documentRepository;
    private final DocumentEditStateRepository editStateRepository;
    private final DocumentEditStateInitializer editStateInitializer;

    public SkillReferenceService(
            WorkspaceAccessGuard workspaceAccessGuard,
            DocumentRepository documentRepository,
            DocumentEditStateRepository editStateRepository,
            DocumentEditStateInitializer editStateInitializer
    ) {
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.documentRepository = documentRepository;
        this.editStateRepository = editStateRepository;
        this.editStateInitializer = editStateInitializer;
    }

    public SkillReferenceReadResponse read(
            String workspaceId, String userId, String documentId
    ) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        var document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (document.getDocumentRole() == DocumentRole.ORIGINAL) {
            return new SkillReferenceReadResponse(DocumentRole.ORIGINAL.name(), null);
        }
        editStateInitializer.initializeIfNeeded(document);
        String markdown = editStateRepository.findById(document.getId())
                .map(DocumentEditState::getMarkdown)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (markdown.length() > MAX_EDITABLE_MARKDOWN_CHARACTERS) {
            throw new SkillReferenceDocumentTooLargeException();
        }
        return new SkillReferenceReadResponse(DocumentRole.EDITABLE.name(), markdown);
    }
}
