package fruition.core.skill.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.DocumentEditStateInitializer;
import fruition.core.skill.dto.SkillReferenceReadResponse;
import fruition.core.skill.exception.SkillReferenceDocumentTooLargeException;
import org.springframework.stereotype.Service;

import java.text.BreakIterator;
import java.util.Locale;

@Service
public class SkillReferenceService {

    private static final int MAX_EDITABLE_MARKDOWN_CHARACTERS = 30000;

    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final DocumentRepository documentRepository;
    private final DocumentEditStateRepository editStateRepository;
    private final DocumentEditStateInitializer editStateInitializer;
    private final DocumentAccessPolicy accessPolicy;

    public SkillReferenceService(
            WorkspaceAccessGuard workspaceAccessGuard,
            DocumentRepository documentRepository,
            DocumentEditStateRepository editStateRepository,
            DocumentEditStateInitializer editStateInitializer,
            DocumentAccessPolicy accessPolicy
    ) {
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.documentRepository = documentRepository;
        this.editStateRepository = editStateRepository;
        this.editStateInitializer = editStateInitializer;
        this.accessPolicy = accessPolicy;
    }

    public SkillReferenceReadResponse read(
            String workspaceId, String userId, String documentId, Integer maxChars
    ) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        var document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .filter(accessPolicy.viewer(workspaceId, userId)::canRead)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (document.getDocumentRole() == DocumentRole.ORIGINAL) {
            return new SkillReferenceReadResponse(DocumentRole.ORIGINAL.name(), null, null, null, document.getDisplayName());
        }
        editStateInitializer.initializeIfNeeded(document);
        String markdown = editStateRepository.findById(document.getId())
                .map(DocumentEditState::getMarkdown)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (maxChars != null) {
            return truncated(markdown, maxChars, document.getDisplayName());
        }
        if (markdown.length() > MAX_EDITABLE_MARKDOWN_CHARACTERS) {
            throw new SkillReferenceDocumentTooLargeException();
        }
        return new SkillReferenceReadResponse(DocumentRole.EDITABLE.name(), markdown, null, null, document.getDisplayName());
    }

    /**
     * 앞에서 maxChars자까지 돌려준다. 글자 수는 코드 포인트로 세어 AI(Python)의 len과 맞춘다.
     * 경계가 한 글자(결합 문자·NFD 한글·국기·ZWJ 이모지·CRLF)의 중간이면 그 글자를 통째로 빼므로 maxChars보다 짧을 수 있다.
     */
    private static SkillReferenceReadResponse truncated(String markdown, int maxChars, String displayName) {
        int totalChars = markdown.codePointCount(0, markdown.length());
        if (totalChars <= maxChars) {
            return new SkillReferenceReadResponse(DocumentRole.EDITABLE.name(), markdown, false, totalChars, displayName);
        }
        int end = markdown.offsetByCodePoints(0, maxChars);
        BreakIterator characters = BreakIterator.getCharacterInstance(Locale.ROOT);
        characters.setText(markdown);
        if (!characters.isBoundary(end)) {
            end = characters.preceding(end);
        }
        return new SkillReferenceReadResponse(DocumentRole.EDITABLE.name(), markdown.substring(0, end), true, totalChars, displayName);
    }
}
