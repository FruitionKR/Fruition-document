package fruition.core.document.exception;

public class ConflictAlreadyResolvedException extends RuntimeException {
    public ConflictAlreadyResolvedException() {
        super("이미 해결된 충돌입니다.");
    }
}
