package fruition.core.aitask;

import java.util.List;
import java.util.Map;

/**
 * {@link AiErrorMessages}의 영어 문구. 요청 언어가 en일 때만 쓴다. 키는 한국어 목록과 같아야 한다(테스트가 확인한다).
 */
final class AiErrorMessagesEn {

    static final String INSUFFICIENT_CREDIT = "You don't have enough credits to start this task.";
    static final String AI_USAGE_LIMIT_EXCEEDED =
            "You've reached your AI usage limit, so the task wasn't started. Please try again later or tomorrow.";

    static final String QUERY_DEFAULT = "Couldn't process your question. Please try again in a moment.";
    static final String AGENT_DEFAULT = "Couldn't finish the request. Check the document and try again.";
    static final String INGEST_DEFAULT = "Adding to the wiki failed. The document is unchanged, so please try again.";
    static final String LINT_DEFAULT = "Wiki cleanup failed. Please try again in a moment.";
    static final String CONVERT_PREFIX = "PDF conversion failed. ";
    static final String CONVERT_DEFAULT = "An error occurred during conversion.";
    static final String TRANSCRIPTION_DEFAULT = "Couldn't transcribe the recording. Please upload it again.";

    static final Map<String, String> CONVERT_OVERRIDES = Map.of(
            "input_rejected", "The PDF is password-protected or damaged and can't be converted.");

    static final Map<String, String> TRANSCRIPTION_OVERRIDES = Map.of(
            "MEETING_RECORDING_TOO_LONG", "The recording is longer than 3 hours and can't be transcribed. Please split it and upload again.",
            "MEETING_RECORDING_UNREADABLE", "The recording format isn't supported or couldn't be recognized.",
            "input_rejected", "The recording format isn't supported or couldn't be recognized.",
            "MEETING_TRANSCRIPT_EMPTY", "No speech was recognized.",
            "MEETING_TRANSCRIPT_TOO_LONG", "The conversation exceeds the meeting notes limit (100,000 characters). Please split the recording and upload again.",
            "deadline_exceeded", "Transcription took too long and was stopped. Please split the recording and upload again.",
            AiErrorMessages.TRANSCRIPTION_CANCELLED, "Transcription was cancelled. Please upload the recording again.");

    static final Map<String, String> AGENT_OVERRIDES = Map.of(
            "INSUFFICIENT_CREDIT", "You don't have enough credits, so the task didn't start and the document wasn't changed.",
            "provider_rate_limited", "The AI service is busy or hit a usage limit, so the document wasn't changed. Please try again in a moment.",
            "provider_unavailable", "The AI service didn't respond or had a temporary problem, so the document wasn't changed. Please try again in a moment.",
            "input_rejected", "The AI service didn't accept the request content or length, so the document wasn't changed. Narrow the scope or adjust the request and try again.",
            "deadline_exceeded", "Processing took too long and was stopped, so the document wasn't changed. Narrow the scope or try again in a moment.",
            "interrupted_attempts_exhausted", "The server task was interrupted several times, so the request didn't finish and the document wasn't changed. Please try again in a moment.",
            "agent_turn_failed", "An unexpected problem occurred while processing the request, so the document wasn't changed. Please try again in a moment.");

    static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("provider_rate_limited", "The AI service is busy and couldn't process this. Please try again in a moment."),
            Map.entry("provider_unavailable", "The AI service had a temporary problem and couldn't process this. Please try again in a moment."),
            Map.entry("input_rejected", "The AI service didn't accept the request content or length. Shorten or adjust it and try again."),
            Map.entry("deadline_exceeded", "Processing took too long and was stopped. Shorten the content or try again in a moment."),
            Map.entry("cancelled", "Processing was cancelled."),
            Map.entry("interrupted_attempts_exhausted", "The server task was interrupted several times and couldn't finish. Please try again in a moment."),
            Map.entry("dependency_failed", "A previous task failed, so this task was skipped. Check the previous task's result and try again."),
            Map.entry("markdown_edit_refused", "The AI only said it couldn't do this instead of editing, so the document wasn't changed. Make the request more specific and try again."),
            Map.entry("markdown_edit_evaluation_rejected", "The edit didn't pass the check that it matches your request, so the document wasn't changed. Be more specific about what and where to change, then try again."),
            Map.entry("markdown_edit_evaluation_invalid", "Couldn't verify the result of checking the edit, so the document wasn't changed. Please try again in a moment."),
            Map.entry("markdown_edit_response_invalid", "The AI response was cut off or malformed, so the document wasn't changed. Narrow the range or try again in a moment."),
            Map.entry("markdown_output_contract_failed", "The edit didn't pass the document format check, so the document wasn't changed. Narrow the range or make the request more specific and try again."),
            Map.entry("markdown_target_crosses_structure", "The selection covers only part of a table, code block, or formula, so the document wasn't changed. Select the whole structure and try again."),
            Map.entry("markdown_create_evaluation_rejected", "The draft didn't pass the check that it follows the skill instructions, so no new document was created. Make the request more specific or check the skill instructions and try again."),
            Map.entry("markdown_create_evaluation_invalid", "Couldn't verify the result of checking the new document draft, so no new document was created. Please try again in a moment."),
            Map.entry("markdown_create_response_invalid", "The AI response was cut off or malformed, so no new document was created. Narrow the request or try again in a moment."),
            Map.entry("markdown_create_output_contract_failed", "The draft's title or body was empty or malformed, so no new document was created. Make the request more specific and try again."),
            Map.entry("agent_turn_cancelled", "The agent task was cancelled."),
            Map.entry("agent_turn_interrupted", "The server task was interrupted, so the request didn't finish and the document wasn't changed. Please try again."),
            Map.entry("invalid_agent_turn_request", "The request couldn't be processed, so the document wasn't changed. Check the open document and selection, then send it again."),
            Map.entry("agent_not_configured", "AI tasks aren't available right now, so the document wasn't changed. Retrying won't help, so please contact your administrator."),
            Map.entry("agent_turn_route_contract_failed", "Couldn't decide how to handle the request, so the document wasn't changed. Make the request more specific and try again."),
            Map.entry("SKILL_NOT_FOUND", "The selected skill couldn't be found, so the document wasn't changed. Please choose the skill again."),
            Map.entry("SKILL_DISABLED", "The selected skill is turned off, so the document wasn't changed. Turn on the skill or choose another one."),
            Map.entry("REFERENCE_DOCUMENT_TOO_LARGE", "The reference document exceeds 30,000 characters, so the document wasn't changed. Split it or use a shorter document and try again."),
            Map.entry("invalid_llm_selection", "The selected model isn't available right now, so the document wasn't changed. Choose another model and try again."),
            Map.entry("agent_result_invalid_request", "The AI result was missing request information, so the document wasn't changed. Please try again."),
            Map.entry("agent_result_invalid_payload", "The AI result was malformed, so the document wasn't changed. Please try again."),
            Map.entry("agent_result_unsupported_action", "The AI returned an unsupported result, so the document wasn't changed. Adjust the request and try again."),
            Map.entry("agent_result_missing_canonical_markdown", "The edit body was empty, so the document wasn't changed. Please try again."),
            Map.entry("agent_result_request_mismatch", "The AI result didn't match the requested document, so the document wasn't changed. Reopen the document and try again."),
            Map.entry("document_purged", "The document was permanently deleted, so the task didn't run. Choose another document and try again."),
            Map.entry("mutation_intent_required", "Please tell me a bit more specifically what you'd like to change."),
            Map.entry("ready_markdown_migration_unrecoverable", "The previous edit couldn't be recovered, so the document wasn't changed. Send the same request again to get a new edit."),
            Map.entry("plan_out_of_scope", "The plan only had operations that move items outside the selected folder or change the folder itself, so no plan was created. Adjust the rules and try again."),
            Map.entry("tool_http_403", "You don't have permission to change this document, so this operation didn't run. Check your permissions and try again."),
            Map.entry("tool_http_404", "The target couldn't be found, so this operation didn't run. Check whether it was deleted and try again."),
            Map.entry("tool_http_409", "The content changed in the meantime, so this operation couldn't be applied. Check the latest content and try again."),
            Map.entry("tool_http_unavailable", "Couldn't connect to the document service, so this operation didn't run. Check the task result and try again."),
            Map.entry("rollback_failed", "Couldn't undo the changes. Check the document state in the task history."),
            Map.entry("rollback_child_failed", "Some of the tasks run together couldn't be undone. Check the document state in the task history."),
            Map.entry("rollback_conflict", "The content changed in the meantime, so the changes couldn't be undone automatically. Check the document state in the task history."),
            Map.entry("rollback_database_error", "A storage problem prevented undoing the changes. Check the document state in the task history."),
            Map.entry("rollback_retry_pending", "Undoing the changes is being retried automatically. Please wait a moment."),
            Map.entry("rollback_document_purged", "Changes to a permanently deleted document can't be undone. The other changes were undone."),
            Map.entry("PIPELINE_UNAVAILABLE", "Couldn't connect to the AI service. Please try again in a moment."),
            Map.entry("PIPELINE_TIMEOUT", "The query took too long, so cancellation was requested."),
            Map.entry("INSUFFICIENT_CREDIT", INSUFFICIENT_CREDIT),
            Map.entry("AI_USAGE_LIMIT_EXCEEDED", AI_USAGE_LIMIT_EXCEEDED));

    static final List<Map.Entry<String, String>> PREFIX_MESSAGES = List.of(
            Map.entry("markdown_create_", "Couldn't create a new document draft, so no new document was created. Make the request more specific and try again."),
            Map.entry("markdown_", "Couldn't create an edit, so the document wasn't changed. Make the request more specific and try again."),
            Map.entry("agent_result_", "The AI result couldn't be applied, so the document wasn't changed. Please try again."),
            Map.entry("tool_http_", "The document service couldn't process the request, so this operation didn't run. Check the task result and try again."),
            Map.entry("rollback_", "A problem occurred while undoing the changes, so some may remain. Check the document state in the task history."));

    private AiErrorMessagesEn() {
    }
}
