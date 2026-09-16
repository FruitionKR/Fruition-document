package fruition.core.authz;

/**
 * core(문서 서비스) 소유의 워크스페이스 접근 거부 예외.
 * 멤버가 아니면 워크스페이스 존재 여부를 숨기기 위해 404로 매핑된다(fail-closed).
 */
public class WorkspaceNotFoundException extends RuntimeException {
    public WorkspaceNotFoundException(String id) {
        super("워크스페이스를 찾을 수 없습니다: id=" + id);
    }
}
