package fruition.core.aitask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AiErrorMessagesTest {

    private static final String DOCUMENT_UNCHANGED = "문서를 바꾸지 않았어요.";
    private static final String NEW_DOCUMENT_NOT_CREATED = "새 문서를 만들지 않았어요.";
    private static final Pattern INTERNAL_VALUE = Pattern.compile(
            "[a-z0-9]+_[a-z0-9_]+|[A-Z]+_[A-Z_]+|(?i:gpt|claude|gemini|llm|prompt|traceback|exception|http)");

    /** 코드별로 사용자에게 보일 문구 전체. 문구를 바꾸면 여기서 드러난다. */
    private static Map<String, String> agentMessages() {
        var messages = new LinkedHashMap<String, String>();
        messages.put("markdown_edit_refused", "AI가 고친 내용 대신 처리할 수 없다는 답만 내놓아 문서를 바꾸지 않았어요. 요청을 더 구체적으로 적어 다시 시도해 주세요.");
        messages.put("markdown_edit_evaluation_rejected", "만든 편집안이 요청에 맞는지 확인하는 검사를 통과하지 못해 문서를 바꾸지 않았어요. 바꿀 내용과 범위를 더 구체적으로 적어 다시 시도해 주세요.");
        messages.put("markdown_edit_evaluation_invalid", "편집안을 검사한 결과를 확인하지 못해 문서를 바꾸지 않았어요. 잠시 후 다시 시도해 주세요.");
        messages.put("markdown_edit_response_invalid", "AI 응답이 잘렸거나 형식이 맞지 않아 문서를 바꾸지 않았어요. 고칠 범위를 줄이거나 잠시 후 다시 시도해 주세요.");
        messages.put("markdown_output_contract_failed", "만든 편집안이 문서 형식을 지키는지 확인하는 검사를 통과하지 못해 문서를 바꾸지 않았어요. 고칠 범위를 줄이거나 요청을 더 구체적으로 적어 다시 시도해 주세요.");
        messages.put("markdown_target_crosses_structure", "선택한 범위가 표·코드 블록·수식 같은 구조의 일부에만 걸쳐 있어 문서를 바꾸지 않았어요. 구조 전체가 들어가도록 범위를 다시 선택해 주세요.");
        messages.put("markdown_create_evaluation_rejected", "만든 초안이 Skill 지침을 지키는지 확인하는 검사를 통과하지 못해 새 문서를 만들지 않았어요. 요청을 더 구체적으로 적거나 Skill 지침을 확인한 뒤 다시 시도해 주세요.");
        messages.put("markdown_create_evaluation_invalid", "새 문서 초안을 검사한 결과를 확인하지 못해 새 문서를 만들지 않았어요. 잠시 후 다시 시도해 주세요.");
        messages.put("markdown_create_response_invalid", "AI 응답이 잘렸거나 형식이 맞지 않아 새 문서를 만들지 않았어요. 요청 범위를 줄이거나 잠시 후 다시 시도해 주세요.");
        messages.put("markdown_create_output_contract_failed", "만든 초안의 제목이나 본문이 비어 있거나 형식이 맞지 않아 새 문서를 만들지 않았어요. 요청을 더 구체적으로 적어 다시 시도해 주세요.");
        messages.put("provider_rate_limited", "AI 서비스 요청이 많거나 이용 한도에 걸려 문서를 바꾸지 않았어요. 잠시 후 다시 시도해 주세요.");
        messages.put("provider_unavailable", "AI 서비스가 응답하지 않거나 일시적인 문제가 있어 문서를 바꾸지 않았어요. 잠시 후 다시 시도해 주세요.");
        messages.put("input_rejected", "AI 서비스가 요청 내용이나 길이를 받아들이지 않아 문서를 바꾸지 않았어요. 범위를 줄이거나 요청을 고쳐 다시 시도해 주세요.");
        messages.put("deadline_exceeded", "처리 시간이 너무 오래 걸려 중단했고 문서를 바꾸지 않았어요. 범위를 줄이거나 잠시 후 다시 시도해 주세요.");
        messages.put("interrupted_attempts_exhausted", "서버 작업이 여러 번 중단되어 요청을 끝내지 못했고 문서를 바꾸지 않았어요. 잠시 후 다시 시도해 주세요.");
        messages.put("agent_turn_failed", "요청을 처리하다 예상하지 못한 문제가 생겨 문서를 바꾸지 않았어요. 잠시 후 다시 시도해 주세요.");
        messages.put("agent_turn_cancelled", "Agent 작업이 취소되었습니다.");
        messages.put("agent_turn_interrupted", "서버 작업이 중단되어 요청을 끝내지 못했고 문서를 바꾸지 않았어요. 다시 시도해 주세요.");
        messages.put("invalid_agent_turn_request", "요청 정보를 처리할 수 없어 문서를 바꾸지 않았어요. 열린 문서와 선택 범위를 확인한 뒤 다시 보내 주세요.");
        messages.put("agent_not_configured", "지금은 AI 작업 기능을 쓸 수 없는 상태라 문서를 바꾸지 않았어요. 다시 시도해도 같으니 관리자에게 문의해 주세요.");
        messages.put("agent_turn_route_contract_failed", "요청을 어떻게 처리할지 정하지 못해 문서를 바꾸지 않았어요. 요청을 더 구체적으로 적어 다시 시도해 주세요.");
        messages.put("SKILL_NOT_FOUND", "선택한 Skill을 찾을 수 없어 문서를 바꾸지 않았어요. Skill을 다시 선택해 주세요.");
        messages.put("SKILL_DISABLED", "선택한 Skill이 꺼져 있어 문서를 바꾸지 않았어요. Skill을 켜거나 다른 Skill을 선택해 주세요.");
        messages.put("REFERENCE_DOCUMENT_TOO_LARGE", "편집할 참조 문서가 30,000자를 넘어 문서를 바꾸지 않았어요. 문서를 나누거나 더 짧은 문서로 다시 시도해 주세요.");
        messages.put("invalid_llm_selection", "선택한 모델을 지금 쓸 수 없어 문서를 바꾸지 않았어요. 다른 모델을 선택해 다시 시도해 주세요.");
        messages.put("agent_result_invalid_request", "AI 결과에 요청 정보가 빠져 있어 문서를 바꾸지 않았어요. 다시 시도해 주세요.");
        messages.put("agent_result_invalid_payload", "AI 결과의 형식이 맞지 않아 문서를 바꾸지 않았어요. 다시 시도해 주세요.");
        messages.put("agent_result_unsupported_action", "AI가 지원하지 않는 처리 결과를 내놓아 문서를 바꾸지 않았어요. 요청을 고쳐 다시 시도해 주세요.");
        messages.put("agent_result_missing_canonical_markdown", "편집안 본문이 비어 있어 문서를 바꾸지 않았어요. 다시 시도해 주세요.");
        messages.put("agent_result_request_mismatch", "AI 결과가 요청한 문서와 맞지 않아 문서를 바꾸지 않았어요. 문서를 다시 열고 요청해 주세요.");
        messages.put("ready_markdown_migration_unrecoverable", "이전 편집안을 복구할 수 없어 문서를 바꾸지 않았어요. 같은 요청을 다시 보내 새 편집안을 받아 주세요.");
        messages.put("document_purged", "영구 삭제된 문서라 실행하지 않았습니다. 다른 문서를 골라 다시 요청해 주세요.");
        messages.put("mutation_intent_required", "어떤 변경을 원하시는지 조금 더 구체적으로 알려 주세요.");
        messages.put("plan_out_of_scope", "선택한 폴더 밖으로 옮기거나 선택한 폴더 자체를 바꾸는 작업만 있어 정리 계획을 만들지 못했어요. 규칙을 조정해 다시 시도해 주세요.");
        messages.put("dependency_failed", "앞선 작업이 실패해 이 작업은 건너뛰었습니다. 앞선 작업의 결과를 확인한 뒤 다시 요청해 주세요.");
        messages.put("tool_http_403", "이 문서를 바꿀 권한이 없어 이 작업을 실행하지 못했어요. 권한을 확인한 뒤 다시 요청해 주세요.");
        messages.put("tool_http_404", "대상을 찾을 수 없어 이 작업을 실행하지 못했어요. 삭제되었는지 확인한 뒤 다시 요청해 주세요.");
        messages.put("tool_http_409", "그 사이 내용이 바뀌어 이 작업을 적용하지 못했어요. 최신 내용을 확인한 뒤 다시 요청해 주세요.");
        messages.put("tool_http_unavailable", "문서 서비스에 연결하지 못해 이 작업을 실행하지 못했어요. 작업 결과를 확인한 뒤 다시 요청해 주세요.");
        messages.put("rollback_failed", "변경을 되돌리지 못했어요. 작업 기록에서 문서 상태를 확인해 주세요.");
        messages.put("rollback_child_failed", "함께 실행한 작업 중 일부를 되돌리지 못했어요. 작업 기록에서 문서 상태를 확인해 주세요.");
        messages.put("rollback_conflict", "그 사이 내용이 바뀌어 변경을 자동으로 되돌리지 못했어요. 작업 기록에서 문서 상태를 확인해 주세요.");
        messages.put("rollback_database_error", "저장소 문제로 변경을 되돌리지 못했어요. 작업 기록에서 문서 상태를 확인해 주세요.");
        messages.put("rollback_retry_pending", "변경을 되돌리는 작업을 자동으로 다시 시도하고 있어요. 잠시 기다려 주세요.");
        messages.put("rollback_document_purged", "영구 삭제된 문서의 변경은 되돌릴 수 없습니다. 나머지 변경은 되돌렸습니다.");
        messages.put("PIPELINE_UNAVAILABLE", "AI 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        return messages;
    }

    @Test
    void everyAgentCodeHasItsOwnMessage() {
        agentMessages().forEach((code, message) ->
                assertThat(AiErrorMessages.agent(code)).as(code).isEqualTo(message));
    }

    @Test
    void messagesNeverExposeInternalValues() {
        agentMessages().keySet().forEach(code -> assertThat(INTERNAL_VALUE.matcher(AiErrorMessages.agent(code)).find())
                .as("%s: %s", code, AiErrorMessages.agent(code)).isFalse());
        List.of("markdown_new", "markdown_create_new", "agent_result_new", "tool_http_500", "rollback_new", "unknown_new", "")
                .forEach(code -> assertThat(INTERNAL_VALUE.matcher(AiErrorMessages.agent(code)).find())
                        .as(code).isFalse());
    }

    @Test
    void messagesStayShortAndNeverOfferCredit() {
        agentMessages().forEach((code, message) -> {
            assertThat(message.split("(?<=[.])\\s+")).as(code).hasSizeLessThanOrEqualTo(2);
            assertThat(message).as(code).doesNotContain("크레딧", "충전");
        });
    }

    @Test
    void failedEditsSayTheDocumentDidNotChange() {
        agentMessages().forEach((code, message) -> {
            if (code.startsWith("markdown_create_")) {
                assertThat(message).as(code).contains(NEW_DOCUMENT_NOT_CREATED);
            } else if (code.startsWith("markdown_") || code.startsWith("agent_result_")) {
                assertThat(message).as(code).contains(DOCUMENT_UNCHANGED);
            }
        });
    }

    @Test
    void refusalEvaluationAndEvaluatorFailuresAreToldApart() {
        assertThat(List.of("markdown_edit_refused", "markdown_edit_evaluation_rejected",
                "markdown_edit_evaluation_invalid", "markdown_edit_response_invalid", "markdown_output_contract_failed")
                .stream().map(AiErrorMessages::agent).distinct()).hasSize(5);
    }

    @Test
    void retryIsNotOfferedWhenRetryCannotHelp() {
        assertThat(AiErrorMessages.agent("agent_not_configured")).contains("관리자");
        assertThat(AiErrorMessages.agent("markdown_target_crosses_structure")).doesNotContain("다시 시도");
        assertThat(AiErrorMessages.agent("SKILL_DISABLED")).doesNotContain("다시 시도");
        assertThat(AiErrorMessages.agent("REFERENCE_DOCUMENT_TOO_LARGE")).contains("나누거나");
    }

    @Test
    void newCodesFallBackToPrefixDefault() {
        assertThat(AiErrorMessages.agent("markdown_edit_target_missing"))
                .isEqualTo("편집안을 만들지 못해 문서를 바꾸지 않았어요. 요청을 더 구체적으로 적어 다시 시도해 주세요.");
        assertThat(AiErrorMessages.agent("markdown_create_new_reason"))
                .isEqualTo("새 문서 초안을 만들지 못해 새 문서를 만들지 않았어요. 요청을 더 구체적으로 적어 다시 시도해 주세요.");
        assertThat(AiErrorMessages.agent("agent_result_new_reason"))
                .isEqualTo("AI 결과를 적용할 수 없어 문서를 바꾸지 않았어요. 다시 시도해 주세요.");
        assertThat(AiErrorMessages.agent("tool_http_500"))
                .isEqualTo("문서 서비스가 요청을 처리하지 못해 이 작업을 실행하지 못했어요. 작업 결과를 확인한 뒤 다시 요청해 주세요.");
    }

    @Test
    void rollbackCodeWithOperationIdUsesPrefixDefault() {
        assertThat(AiErrorMessages.agent("rollback_snapshot_invalid:op-1"))
                .isEqualTo("변경을 되돌리는 중 문제가 생겨 일부가 남아 있을 수 있어요. 작업 기록에서 문서 상태를 확인해 주세요.")
                .doesNotContain("op-1");
    }

    @Test
    void documentStateClaimStaysOnAgentSurface() {
        for (String code : List.of("provider_rate_limited", "provider_unavailable", "input_rejected",
                "deadline_exceeded", "interrupted_attempts_exhausted")) {
            assertThat(AiErrorMessages.query(code)).as(code).doesNotContain("문서를 바꾸지");
            assertThat(AiErrorMessages.ingest(code)).as(code).doesNotContain("문서를 바꾸지");
            assertThat(AiErrorMessages.lint(code)).as(code).doesNotContain("문서를 바꾸지");
        }
    }

    @Test
    void sharedCodesKeepOtherFeatureTexts() {
        assertThat(AiErrorMessages.query("provider_rate_limited"))
                .isEqualTo("AI 서비스 요청이 많아 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        assertThat(AiErrorMessages.ingest("deadline_exceeded")).contains("시간이 초과");
        assertThat(AiErrorMessages.query("input_rejected")).contains("요청 내용이나 길이");
    }

    @Test
    void unknownBlankAndGenericCodesFallBackToFeatureDefault() {
        assertThat(AiErrorMessages.query("ValueError: secret detail")).isEqualTo("질의를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        assertThat(AiErrorMessages.agent(null)).isEqualTo("요청을 끝까지 처리하지 못했어요. 문서 내용을 확인한 뒤 다시 시도해 주세요.");
        assertThat(AiErrorMessages.agent("internal_error")).isEqualTo("요청을 끝까지 처리하지 못했어요. 문서 내용을 확인한 뒤 다시 시도해 주세요.");
        assertThat(AiErrorMessages.agent("task_failed")).isEqualTo("요청을 끝까지 처리하지 못했어요. 문서 내용을 확인한 뒤 다시 시도해 주세요.");
        assertThat(AiErrorMessages.agent("unknown_new_code")).doesNotContain("unknown_new_code", "문서를 바꾸지");
        assertThat(AiErrorMessages.ingest("internal_error")).isEqualTo("위키 편입에 실패했습니다. 문서는 그대로 있으니 다시 시도해 주세요.");
        assertThat(AiErrorMessages.lint("something_new")).isEqualTo("위키 정리에 실패했습니다. 잠시 후 다시 시도해 주세요.");
    }

    @Test
    void convertAndTranscriptionShareCommonTextsAndKeepOwnDefaults() {
        assertThat(AiErrorMessages.convert("provider_rate_limited"))
                .isEqualTo("PDF 변환에 실패했습니다. " + AiErrorMessages.query("provider_rate_limited"));
        assertThat(AiErrorMessages.convert("input_rejected")).contains("암호가 걸렸거나");
        assertThat(AiErrorMessages.convert("Traceback secret")).isEqualTo("PDF 변환에 실패했습니다. 변환 중 오류가 발생했습니다.");
        assertThat(AiErrorMessages.transcription("provider_unavailable")).isEqualTo(AiErrorMessages.query("provider_unavailable"));
        assertThat(AiErrorMessages.transcription("MEETING_RECORDING_TOO_LONG")).contains("3시간");
        assertThat(AiErrorMessages.transcription("deadline_exceeded")).contains("녹음을 나눠");
        assertThat(AiErrorMessages.transcription("internal_error")).isEqualTo("녹음 파일을 글로 옮기지 못했습니다. 다시 올려 주세요.");
    }

    @Test
    void creditCodeKeepsItsText() {
        assertThat(AiErrorMessages.ingest("INSUFFICIENT_CREDIT")).isEqualTo(AiErrorMessages.INSUFFICIENT_CREDIT);
    }
}
