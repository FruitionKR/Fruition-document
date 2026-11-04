package fruition.core.skill.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.domain.Document;
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
        var viewer = accessPolicy.viewer(workspaceId, userId);
        var document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .filter(viewer::canRead)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (document.getDocumentRole() == DocumentRole.ORIGINAL) {
            return new SkillReferenceReadResponse(DocumentRole.ORIGINAL.name(), null, null, null, document.getDisplayName());
        }
        editStateInitializer.initializeIfNeeded(document);
        // edit_revision은 수정 계획의 기준 버전이다. 본문과 같은 행에서 함께 읽는다.
        DocumentEditState state = editStateRepository.findById(document.getId())
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        String markdown = state.getMarkdown();
        boolean canEdit = viewer.canEdit(document) && !"chat_export".equals(document.getOrigin()) && !isConverting(document);
        if (maxChars != null) {
            return truncated(markdown, maxChars, document.getDisplayName(), state.getRevision(), canEdit);
        }
        if (markdown.length() > MAX_EDITABLE_MARKDOWN_CHARACTERS) {
            throw new SkillReferenceDocumentTooLargeException();
        }
        return new SkillReferenceReadResponse(DocumentRole.EDITABLE.name(), markdown, null, null,
                document.getDisplayName(), state.getRevision(), canEdit);
    }

    /** 저장 경로와 같이 파트 문서는 부모의 변환 상태를 따른다. */
    private boolean isConverting(Document document) {
        Document converting = "convert_part".equals(document.getOrigin()) && document.getSourceDocumentId() != null
                ? documentRepository.findById(document.getSourceDocumentId()).orElse(null)
                : document;
        return converting != null && converting.isConverting();
    }

    /**
     * 앞에서 maxChars자까지 돌려준다. 글자 수는 코드 포인트로 세어 AI(Python)의 len과 맞춘다.
     * 경계가 한 글자(결합 문자·NFD 한글·국기·ZWJ 이모지·CRLF)의 중간이면 그 글자를 통째로 빼므로 maxChars보다 짧을 수 있다.
     */
    private static SkillReferenceReadResponse truncated(
            String markdown, int maxChars, String displayName, long editRevision, boolean canEdit) {
        int totalChars = markdown.codePointCount(0, markdown.length());
        if (totalChars <= maxChars) {
            return new SkillReferenceReadResponse(DocumentRole.EDITABLE.name(), markdown, false, totalChars, displayName, editRevision, canEdit);
        }
        int end = markdown.offsetByCodePoints(0, maxChars);
        BreakIterator characters = BreakIterator.getCharacterInstance(Locale.ROOT);
        characters.setText(markdown);
        if (!characters.isBoundary(end)) {
            end = characters.preceding(end);
        }
        return new SkillReferenceReadResponse(DocumentRole.EDITABLE.name(), markdown.substring(0, end), true, totalChars, displayName,
                editRevision, canEdit);
    }
}
