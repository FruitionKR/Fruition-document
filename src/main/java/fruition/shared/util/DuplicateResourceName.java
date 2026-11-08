package fruition.shared.util;

import java.util.Map;
import org.hibernate.exception.ConstraintViolationException;

/** 이름 고유 제약만 사용자에게 알린다. 그 밖의 무결성 오류는 기존 오류 처리에 맡긴다. */
public final class DuplicateResourceName {
    private static final Map<String, String> MESSAGES = Map.of(
            "uq_document_tree_active_name", "같은 폴더에 같은 이름의 파일 또는 폴더가 이미 있습니다. 다른 이름을 사용해 주세요.",
            "uq_documents_skill_reference_name", "같은 이름의 스킬 참고 문서가 이미 있습니다. 다른 이름을 사용해 주세요.",
            "uq_documents_active_name", "같은 워크스페이스에 같은 파일명이 이미 있습니다. 다른 이름을 사용해 주세요.",
            "uq_folders_active_name", "같은 워크스페이스에 같은 폴더명이 이미 있습니다. 다른 이름을 사용해 주세요.");

    /** 영어 요청의 같은 안내. 문서·폴더 이름 문구는 access-svc와 같은 문장이다. */
    private static final Map<String, String> ENGLISH_MESSAGES = Map.of(
            MESSAGES.get("uq_document_tree_active_name"),
            "A file or folder with the same name already exists in this folder. Please use a different name.",
            MESSAGES.get("uq_documents_skill_reference_name"),
            "A skill reference document with the same name already exists. Please use a different name.",
            MESSAGES.get("uq_documents_active_name"),
            "A file with the same name already exists in this workspace. Please use a different name.",
            MESSAGES.get("uq_folders_active_name"),
            "A folder with the same name already exists in this workspace. Please use a different name.");

    private DuplicateResourceName() {}

    /** {@link #message}가 만든 한국어 문구의 영어. 모르는 문구면 null이다. */
    public static String englishMessage(String koreanMessage) {
        return koreanMessage == null ? null : ENGLISH_MESSAGES.get(koreanMessage);
    }

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
