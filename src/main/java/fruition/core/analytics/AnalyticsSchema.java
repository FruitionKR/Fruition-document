package fruition.core.analytics;

import java.util.*;

/** Exact per-event property allowlist; envelope fields cannot be supplied by callers. */
public final class AnalyticsSchema {
    private AnalyticsSchema() {}
    private static final Map<String, Set<String>> REQUIRED = Map.ofEntries(
        entry("document_uploaded", "document_id file_type size_bucket origin"),
        entry("wiki_ingest_requested", "ingest_id document_id operation_id trigger source"),
        entry("wiki_ingest_completed", "ingest_id document_id source trigger status doc_count pages_changed duration_sec"),
        entry("pdf_convert_completed", "conversion_id document_id status is_reconvert duration_sec"),
        entry("query_answered", "query_id conversation_id turn_index entry_point query_requested_at has_citations citation_count internal_citation_count web_citation_count cited_document_ids web_search_used duration_sec"),
        entry("query_failed", "query_id conversation_id turn_index entry_point query_requested_at failure_reason duration_sec"),
        entry("ai_edit_proposed", "proposal_id document_id edit_type"),
        entry("ai_edit_decided", "proposal_id document_id edit_type decision operation_id"),
        entry("ai_operation_rolled_back", "operation_id operation_type status restored_operation_count cascaded_operation_ids time_since_operation_sec restore_requested_at restore_attempt_id"),
        entry("skill_run", "skill_id skill_run_id scope is_creator"),
        entry("chat_exported", "export_id document_id conversation_id pair_count mode status"));
    private static final Map<String, Set<String>> OPTIONAL = Map.ofEntries(
        entry("wiki_ingest_requested", "export_id"),
        entry("wiki_ingest_completed", "operation_id export_id failure_reason first_success_ingested_at"),
        entry("pdf_convert_completed", "page_count failure_reason"),
        entry("query_answered", "$session_id cited_document_first_ingested_at"),
        entry("query_failed", "$session_id"),
        entry("ai_operation_rolled_back", "document_id failure_reason"));
    private static Map.Entry<String,Set<String>> entry(String event, String fields) {
        return Map.entry(event, Set.of(fields.split(" ")));
    }
    public static void validate(String event, Map<String,Object> properties) {
        var required = REQUIRED.get(event);
        if (required == null || !properties.keySet().containsAll(required)) throw new IllegalArgumentException("Missing analytics properties");
        var allowed = new HashSet<>(required); allowed.addAll(OPTIONAL.getOrDefault(event, Set.of()));
        if (!allowed.containsAll(properties.keySet()) || properties.containsValue(null)) throw new IllegalArgumentException("Forbidden analytics properties");
        enumValue(properties, "source", Set.of("upload", "chat", "existing_document"));
        enumValue(properties, "trigger", Set.of("auto", "manual"));
        enumValue(properties, "scope", Set.of("personal", "team"));
        enumValue(properties, "mode", Set.of("all", "selected"));
        enumValue(properties, "edit_type", Set.of("rewrite", "append", "replace", "other"));
        enumValue(properties, "decision", Set.of("accepted"));
        enumValue(properties, "failure_reason", Set.of("validation", "permission", "timeout", "provider_error", "storage_error", "internal_error", "unknown"));
        if ("wiki_ingest_completed".equals(event)) {
            requireWhen(properties, "status", "succeeded", "operation_id");
            requireWhen(properties, "source", "chat", "export_id");
        }
        if ("wiki_ingest_requested".equals(event)) requireWhen(properties, "source", "chat", "export_id");
        if ("pdf_convert_completed".equals(event)) requireWhen(properties, "status", "succeeded", "page_count");
        if (Set.of("wiki_ingest_completed", "pdf_convert_completed", "ai_operation_rolled_back").contains(event)) {
            enumValue(properties, "status", Set.of("succeeded", "failed"));
            requireWhen(properties, "status", "failed", "failure_reason");
        }
    }
    private static void requireWhen(Map<String,Object> properties, String key, String value, String required) {
        if (value.equals(properties.get(key)) && !properties.containsKey(required)) throw new IllegalArgumentException("Missing conditional analytics property");
    }
    private static void enumValue(Map<String,Object> properties, String key, Set<String> values) {
        if (properties.containsKey(key) && !values.contains(properties.get(key))) throw new IllegalArgumentException("Invalid analytics enum");
    }
    public static String failureReason(String code) {
        return switch (code == null ? "" : code) {
            case "provider_rate_limited", "provider_unavailable" -> "provider_error";
            case "deadline_exceeded" -> "timeout";
            case "input_rejected" -> "validation";
            case "internal_error", "task_failed", "agent_turn_failed" -> "internal_error";
            case "permission" -> "permission";
            case "storage_error" -> "storage_error";
            default -> "unknown";
        };
    }
}
