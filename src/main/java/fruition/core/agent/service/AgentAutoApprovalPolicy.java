package fruition.core.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.agent.dto.AgentAutoApprovalSettings;

import java.util.Map;
import java.util.UUID;

/**
 * AgentRun 계획을 사용자 확인 없이 승인해도 되는지 정한다. 상태를 갖지 않는 판정 규칙이다.
 *
 * <p>계획 전체가 다음을 모두 만족할 때만 자동 승인한다. 하나라도 어긋나면 지금처럼 승인 화면에서 사용자가 고른다.
 * <ul>
 *   <li>모든 작업의 도구가 알려진 분류에 속하고, 그 분류를 사용자가 켰다. 분류에 없는 도구(문서·폴더 삭제 같은
 *       취소 보상용 도구, 앞으로 추가될 도구)는 항상 수동이다(fail-closed).</li>
 *   <li>AI 검토가 경고(이름·내용 중복 등)를 달거나 기본 선택에서 뺀 작업이 없다. 사람이 골라야 하는 계획이다.</li>
 *   <li>요청한 사용자가 모든 대상 문서·폴더와 옮길 위치를 편집할 수 있다. 자동 승인은 확인 단계만 건너뛸 뿐
 *       권한을 넓히지 않는다. 실행할 때도 도구마다 같은 권한 검사를 다시 한다.</li>
 * </ul>
 */
public final class AgentAutoApprovalPolicy {

    /** 승인 기록(agent_approvals.approved_by)에 남는 자동 승인 주체. */
    public static final String AUTO_APPROVED_BY = "auto:user-setting";

    public enum Category { CONTENT_EDIT, ORGANIZE }

    private static final Map<String, Category> TOOL_CATEGORIES = Map.of(
            "apply_document_edit", Category.CONTENT_EDIT,
            "create_document", Category.CONTENT_EDIT,
            "create_folder", Category.ORGANIZE,
            "rename_folder", Category.ORGANIZE,
            "move_folder", Category.ORGANIZE,
            "rename_document", Category.ORGANIZE,
            "move_document", Category.ORGANIZE);

    /** 요청한 사용자가 대상을 편집할 수 있는지. 폴더 null은 워크스페이스 최상위다. */
    public interface TargetPermissions {
        boolean canEditDocument(String documentId);

        boolean canEditFolder(UUID folderId);
    }

    /**
     * @param reason 판단 근거. approved·disabled·not_awaiting_approval·unsupported_tool·category_disabled·
     *               needs_review·forbidden 중 하나. 로그와 테스트에 쓴다.
     */
    public record Decision(boolean autoApprove, String reason, int planVersion, String operationHash) {
        static Decision manual(String reason) {
            return new Decision(false, reason, 0, null);
        }
    }

    private AgentAutoApprovalPolicy() {
    }

    public static Decision decide(AgentAutoApprovalSettings settings, JsonNode run, TargetPermissions permissions) {
        if (settings == null || !settings.anyEnabled()) return Decision.manual("disabled");
        JsonNode plan = run == null ? null : run.path("plan");
        if (plan == null || !"awaiting_approval".equals(run.path("status").asText())
                || !"awaiting_approval".equals(plan.path("status").asText())
                || !plan.path("operations").isArray() || plan.path("operations").isEmpty()) {
            return Decision.manual("not_awaiting_approval");
        }
        for (JsonNode operation : plan.path("operations")) {
            Category category = TOOL_CATEGORIES.get(operation.path("tool_name").asText());
            if (category == null) return Decision.manual("unsupported_tool");
            if (!enabled(settings, category)) return Decision.manual("category_disabled");
            if (needsReview(operation)) return Decision.manual("needs_review");
        }
        for (JsonNode operation : plan.path("operations")) {
            if (!permitted(operation, permissions)) return Decision.manual("forbidden");
        }
        return new Decision(true, "approved", plan.path("version").asInt(), plan.path("operation_hash").asText());
    }

    private static boolean enabled(AgentAutoApprovalSettings settings, Category category) {
        return switch (category) {
            case CONTENT_EDIT -> Boolean.TRUE.equals(settings.contentEdit());
            case ORGANIZE -> Boolean.TRUE.equals(settings.organize());
        };
    }

    private static boolean needsReview(JsonNode operation) {
        JsonNode warning = operation.get("warning");
        return (warning != null && !warning.isNull())
                || operation.path("suggested_selected").isBoolean() && !operation.path("suggested_selected").booleanValue();
    }

    private static boolean permitted(JsonNode operation, TargetPermissions permissions) {
        JsonNode arguments = operation.path("arguments");
        return switch (operation.path("tool_name").asText()) {
            case "apply_document_edit", "rename_document" ->
                    permissions.canEditDocument(target(operation, "document_id"));
            case "move_document" -> permissions.canEditDocument(target(operation, "document_id"))
                    && destination(arguments.get("folder_id"), permissions);
            case "create_document" -> destination(arguments.get("folder_id"), permissions);
            case "create_folder" -> destination(arguments.get("parent_folder_id"), permissions);
            case "rename_folder" -> folder(target(operation, "folder_id"), permissions);
            case "move_folder" -> folder(target(operation, "folder_id"), permissions)
                    && destination(arguments.get("parent_folder_id"), permissions);
            default -> false;
        };
    }

    private static String target(JsonNode operation, String argument) {
        JsonNode target = operation.get("target_id");
        if (target != null && target.isTextual()) return target.asText();
        JsonNode fromArguments = operation.path("arguments").get(argument);
        return fromArguments != null && fromArguments.isTextual() ? fromArguments.asText() : null;
    }

    /** 옮기거나 만들 위치. 같은 계획이 만드는 폴더($operation_result)는 그 폴더의 상위를 이미 확인했다. */
    private static boolean destination(JsonNode folder, TargetPermissions permissions) {
        if (folder == null || folder.isNull()) return permissions.canEditFolder(null);
        if (folder.isObject() && folder.has("$operation_result")) return true;
        return folder.isTextual() && folder(folder.asText(), permissions);
    }

    private static boolean folder(String folderId, TargetPermissions permissions) {
        if (folderId == null) return false;
        try {
            return permissions.canEditFolder(UUID.fromString(folderId));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
