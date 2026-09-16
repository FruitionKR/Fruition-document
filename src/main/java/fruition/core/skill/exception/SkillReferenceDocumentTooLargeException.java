package fruition.core.skill.exception;

public class SkillReferenceDocumentTooLargeException extends RuntimeException {
    public SkillReferenceDocumentTooLargeException() {
        super("EDITABLE 참조 문서는 30,000자 이하여야 합니다.");
    }
}
