package fruition.core.authz;

/** 워크스페이스 멤버이지만 작업에 필요한 권한 항목이 없다. 403 WORKSPACE_PERMISSION_REQUIRED로 매핑된다. */
public class WorkspacePermissionRequiredException extends RuntimeException {
    public WorkspacePermissionRequiredException(String permission) {
        super("이 작업에는 '" + permission + "' 권한이 필요합니다. 워크스페이스 OWNER에게 요청하세요.");
    }
}
