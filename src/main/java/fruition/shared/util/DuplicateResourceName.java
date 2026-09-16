package fruition.shared.util;

import java.util.Map;
import org.hibernate.exception.ConstraintViolationException;

/** 이름 고유 제약만 사용자에게 알린다. 그 밖의 무결성 오류는 기존 오류 처리에 맡긴다. */
public final class DuplicateResourceName {
    private static final Map<String, String> MESSAGES = Map.of(
            "uq_documents_active_name", "같은 워크스페이스에 같은 파일명이 이미 있습니다. 다른 이름을 사용해 주세요.",
            "uq_folders_active_name", "같은 워크스페이스에 같은 폴더명이 이미 있습니다. 다른 이름을 사용해 주세요.",
            "uq_workspaces_owner_active_name", "소유한 워크스페이스에 같은 이름이 이미 있습니다. 다른 이름을 사용해 주세요.");

    private DuplicateResourceName() {}

    public static String message(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                String message = MESSAGES.get(violation.getConstraintName());
                if (message != null) return message;
            }
        }
        return null;
    }
}
