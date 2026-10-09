package fruition.core.document.exception;

public class EditConflictNotFoundException extends RuntimeException {
    public EditConflictNotFoundException() {
        super("편집 충돌을 찾을 수 없습니다.");
    }
}
