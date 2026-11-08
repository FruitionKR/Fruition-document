package fruition.core.aitask;

import fruition.shared.i18n.ResponseLanguage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 실패 코드를 사용자에게 보일 문구로 바꾸는 유일한 목록이다. 영어 문구는 {@link AiErrorMessagesEn}에 같은 키로 둔다.
 *
 * <p>ai는 실패 원인을 코드로만 보내고 예외 원문은 자기 로그에만 남긴다. 화면·SSE·저장 컬럼에
 * 나가는 문구는 모두 여기를 거친다. 목록에 없는 코드는 기능별 기본 문구로 바꾸고 코드마다
 * 한 번 WARN을 남겨 목록에 추가할지 판단하게 한다.
 */
public final class AiErrorMessages {

    private static final Logger log = LoggerFactory.getLogger(AiErrorMessages.class);

    /** {@code INSUFFICIENT_CREDIT}: 크레딧이 모자라 작업을 시작하지 않았을 때(402 거절·비동기 작업 실패). */
    public static final String INSUFFICIENT_CREDIT = "크레딧이 부족해 작업을 시작하지 못했어요.";
    /** {@code INSUFFICIENT_CREDIT}: 진행 중이던 작업을 이어 가지 못할 때(구간 갱신·Agent 계획 수정·승인). */
    public static final String INSUFFICIENT_CREDIT_STOPPED = "크레딧이 부족해 작업을 이어 가지 못했어요. 작업 기록에서 문서 상태를 확인해 주세요.";
    /** {@code AI_USAGE_LIMIT_EXCEEDED}: 사용자별 AI 사용 한도에 걸려 비동기 작업을 시작하지 않았을 때. 요청 거절은 한도별 문구로 429를 낸다. */
    public static final String AI_USAGE_LIMIT_EXCEEDED = "AI 사용 한도에 도달해 작업을 시작하지 않았습니다. 잠시 후 또는 내일 다시 시도해 주세요.";

    private static final String QUERY_DEFAULT = "질의를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
    /** 어떤 run의 코드인지 모를 때 쓴다. 문서가 바뀌었는지 단정할 수 없어 상태를 말하지 않는다. */
    private static final String AGENT_DEFAULT = "요청을 끝까지 처리하지 못했어요. 문서 내용을 확인한 뒤 다시 시도해 주세요.";
    private static final String INGEST_DEFAULT = "위키 편입에 실패했습니다. 문서는 그대로 있으니 다시 시도해 주세요.";
    private static final String LINT_DEFAULT = "위키 정리에 실패했습니다. 잠시 후 다시 시도해 주세요.";
    private static final String CONVERT_PREFIX = "PDF 변환에 실패했습니다. ";
    private static final String CONVERT_DEFAULT = "변환 중 오류가 발생했습니다.";
    private static final String TRANSCRIPTION_DEFAULT = "녹음 파일을 글로 옮기지 못했습니다. 다시 올려 주세요.";

    /** 변환에서만 공통 문구 대신 쓰는 문구. */
    private static final Map<String, String> CONVERT_OVERRIDES = Map.of(
            "input_rejected", "암호가 걸렸거나 손상되어 변환할 수 없는 PDF입니다.");

    /** 사용자가 녹음 파일 전사를 취소했을 때 회의에 남기는 코드. AI 코드가 아니라 document가 정한다. */
    public static final String TRANSCRIPTION_CANCELLED = "transcription_cancelled";

    /** 회의 전사에서만 공통 문구 대신 쓰거나 더하는 문구. */
    private static final Map<String, String> TRANSCRIPTION_OVERRIDES = Map.of(
            "MEETING_RECORDING_TOO_LONG", "녹음이 3시간을 넘어 글로 옮길 수 없습니다. 나눠서 올려 주세요.",
            "MEETING_RECORDING_UNREADABLE", "지원하지 않거나 인식할 수 없는 녹음 파일입니다.",
            "input_rejected", "지원하지 않거나 인식할 수 없는 녹음 파일입니다.",
            "MEETING_TRANSCRIPT_EMPTY", "인식된 음성이 없습니다.",
            "MEETING_TRANSCRIPT_TOO_LONG", "대화 내용이 회의록 생성 한도(100,000자)를 넘었습니다. 녹음을 나눠 올려 주세요.",
            "deadline_exceeded", "글로 옮기는 시간이 너무 오래 걸려 중단했습니다. 녹음을 나눠 올려 주세요.",
            TRANSCRIPTION_CANCELLED, "글로 옮기기를 취소했습니다. 녹음을 다시 올려 주세요.");

    /**
     * Agent turn에서만 공통 문구 대신 쓰는 문구. 실패한 turn은 편집안을 내놓기 전에 끝나므로 문서 상태를 함께 알린다.
     * 다른 기능(질의·위키·변환·전사)에는 "문서를 바꾸지 않았어요"가 맞지 않아 공통 문구에 넣지 않는다.
     */
    private static final Map<String, String> AGENT_OVERRIDES = Map.of(
            "INSUFFICIENT_CREDIT", "크레딧이 부족해 작업을 시작하지 못했고 문서를 바꾸지 않았어요.",
            "provider_rate_limited", "AI 서비스 요청이 많거나 이용 한도에 걸려 문서를 바꾸지 않았어요. 잠시 후 다시 시도해 주세요.",
            "provider_unavailable", "AI 서비스가 응답하지 않거나 일시적인 문제가 있어 문서를 바꾸지 않았어요. 잠시 후 다시 시도해 주세요.",
            "input_rejected", "AI 서비스가 요청 내용이나 길이를 받아들이지 않아 문서를 바꾸지 않았어요. 범위를 줄이거나 요청을 고쳐 다시 시도해 주세요.",
            "deadline_exceeded", "처리 시간이 너무 오래 걸려 중단했고 문서를 바꾸지 않았어요. 범위를 줄이거나 잠시 후 다시 시도해 주세요.",
            "interrupted_attempts_exhausted", "서버 작업이 여러 번 중단되어 요청을 끝내지 못했고 문서를 바꾸지 않았어요. 잠시 후 다시 시도해 주세요.",
            "agent_turn_failed", "요청을 처리하다 예상하지 못한 문제가 생겨 문서를 바꾸지 않았어요. 잠시 후 다시 시도해 주세요.");

    private static final Map<String, String> MESSAGES = Map.ofEntries(
            // ai 공통 코드
            Map.entry("ai_service_starting", "AI 서비스를 시작하고 있어요. 준비까지 약 2~3분 걸릴 수 있습니다. 잠시 후 다시 시도해 주세요."),
            Map.entry("provider_rate_limited", "AI 서비스 요청이 많아 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
            Map.entry("provider_unavailable", "AI 서비스에 일시적인 문제가 있어 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
            Map.entry("input_rejected", "AI 서비스가 요청 내용이나 길이를 받아들이지 않아 처리하지 못했습니다. 내용을 줄이거나 고쳐서 다시 시도해 주세요."),
            Map.entry("deadline_exceeded", "처리 시간이 초과되어 중단했습니다. 내용을 줄이거나 잠시 후 다시 시도해 주세요."),
            Map.entry("cancelled", "처리가 취소되었습니다."),
            Map.entry("interrupted_attempts_exhausted", "서버 작업이 여러 번 중단되어 완료하지 못했습니다. 잠시 후 다시 시도해 주세요."),
            Map.entry("dependency_failed", "앞선 작업이 실패해 이 작업은 건너뛰었습니다. 앞선 작업의 결과를 확인한 뒤 다시 요청해 주세요."),
            // Agent: 편집안·새 문서 초안을 만들지 못한 원인
            Map.entry("markdown_edit_refused", "AI가 고친 내용 대신 처리할 수 없다는 답만 내놓아 문서를 바꾸지 않았어요. 요청을 더 구체적으로 적어 다시 시도해 주세요."),
            Map.entry("markdown_edit_evaluation_rejected", "만든 편집안이 요청에 맞는지 확인하는 검사를 통과하지 못해 문서를 바꾸지 않았어요. 바꿀 내용과 범위를 더 구체적으로 적어 다시 시도해 주세요."),
            Map.entry("markdown_edit_evaluation_invalid", "편집안을 검사한 결과를 확인하지 못해 문서를 바꾸지 않았어요. 잠시 후 다시 시도해 주세요."),
            Map.entry("markdown_edit_response_invalid", "AI 응답이 잘렸거나 형식이 맞지 않아 문서를 바꾸지 않았어요. 고칠 범위를 줄이거나 잠시 후 다시 시도해 주세요."),
            Map.entry("markdown_output_contract_failed", "만든 편집안이 문서 형식을 지키는지 확인하는 검사를 통과하지 못해 문서를 바꾸지 않았어요. 고칠 범위를 줄이거나 요청을 더 구체적으로 적어 다시 시도해 주세요."),
            Map.entry("markdown_target_crosses_structure", "선택한 범위가 표·코드 블록·수식 같은 구조의 일부에만 걸쳐 있어 문서를 바꾸지 않았어요. 구조 전체가 들어가도록 범위를 다시 선택해 주세요."),
            Map.entry("markdown_create_evaluation_rejected", "만든 초안이 Skill 지침을 지키는지 확인하는 검사를 통과하지 못해 새 문서를 만들지 않았어요. 요청을 더 구체적으로 적거나 Skill 지침을 확인한 뒤 다시 시도해 주세요."),
            Map.entry("markdown_create_evaluation_invalid", "새 문서 초안을 검사한 결과를 확인하지 못해 새 문서를 만들지 않았어요. 잠시 후 다시 시도해 주세요."),
            Map.entry("markdown_create_response_invalid", "AI 응답이 잘렸거나 형식이 맞지 않아 새 문서를 만들지 않았어요. 요청 범위를 줄이거나 잠시 후 다시 시도해 주세요."),
            Map.entry("markdown_create_output_contract_failed", "만든 초안의 제목이나 본문이 비어 있거나 형식이 맞지 않아 새 문서를 만들지 않았어요. 요청을 더 구체적으로 적어 다시 시도해 주세요."),
            // Agent: 요청·실행
            Map.entry("agent_turn_cancelled", "Agent 작업이 취소되었습니다."),
            Map.entry("agent_turn_interrupted", "서버 작업이 중단되어 요청을 끝내지 못했고 문서를 바꾸지 않았어요. 다시 시도해 주세요."),
            Map.entry("invalid_agent_turn_request", "요청 정보를 처리할 수 없어 문서를 바꾸지 않았어요. 열린 문서와 선택 범위를 확인한 뒤 다시 보내 주세요."),
            Map.entry("agent_not_configured", "지금은 AI 작업 기능을 쓸 수 없는 상태라 문서를 바꾸지 않았어요. 다시 시도해도 같으니 관리자에게 문의해 주세요."),
            Map.entry("agent_turn_route_contract_failed", "요청을 어떻게 처리할지 정하지 못해 문서를 바꾸지 않았어요. 요청을 더 구체적으로 적어 다시 시도해 주세요."),
            Map.entry("SKILL_NOT_FOUND", "선택한 Skill을 찾을 수 없어 문서를 바꾸지 않았어요. Skill을 다시 선택해 주세요."),
            Map.entry("SKILL_DISABLED", "선택한 Skill이 꺼져 있어 문서를 바꾸지 않았어요. Skill을 켜거나 다른 Skill을 선택해 주세요."),
            Map.entry("REFERENCE_DOCUMENT_TOO_LARGE", "편집할 참조 문서가 30,000자를 넘어 문서를 바꾸지 않았어요. 문서를 나누거나 더 짧은 문서로 다시 시도해 주세요."),
            Map.entry("invalid_llm_selection", "선택한 모델을 지금 쓸 수 없어 문서를 바꾸지 않았어요. 다른 모델을 선택해 다시 시도해 주세요."),
            Map.entry("agent_result_invalid_request", "AI 결과에 요청 정보가 빠져 있어 문서를 바꾸지 않았어요. 다시 시도해 주세요."),
            Map.entry("agent_result_invalid_payload", "AI 결과의 형식이 맞지 않아 문서를 바꾸지 않았어요. 다시 시도해 주세요."),
            Map.entry("agent_result_unsupported_action", "AI가 지원하지 않는 처리 결과를 내놓아 문서를 바꾸지 않았어요. 요청을 고쳐 다시 시도해 주세요."),
            Map.entry("agent_result_missing_canonical_markdown", "편집안 본문이 비어 있어 문서를 바꾸지 않았어요. 다시 시도해 주세요."),
            Map.entry("agent_result_request_mismatch", "AI 결과가 요청한 문서와 맞지 않아 문서를 바꾸지 않았어요. 문서를 다시 열고 요청해 주세요."),
            Map.entry("document_purged", "영구 삭제된 문서라 실행하지 않았습니다. 다른 문서를 골라 다시 요청해 주세요."),
            Map.entry("mutation_intent_required", "어떤 변경을 원하시는지 조금 더 구체적으로 알려 주세요."),
            Map.entry("ready_markdown_migration_unrecoverable", "이전 편집안을 복구할 수 없어 문서를 바꾸지 않았어요. 같은 요청을 다시 보내 새 편집안을 받아 주세요."),
            Map.entry("plan_out_of_scope", "선택한 폴더 밖으로 옮기거나 선택한 폴더 자체를 바꾸는 작업만 있어 정리 계획을 만들지 못했어요. 규칙을 조정해 다시 시도해 주세요."),
            // Agent: 계획 실행의 항목별 실패(문서 도구 호출 응답)
            Map.entry("tool_http_403", "이 문서를 바꿀 권한이 없어 이 작업을 실행하지 못했어요. 권한을 확인한 뒤 다시 요청해 주세요."),
            Map.entry("tool_http_404", "대상을 찾을 수 없어 이 작업을 실행하지 못했어요. 삭제되었는지 확인한 뒤 다시 요청해 주세요."),
            Map.entry("tool_http_409", "그 사이 내용이 바뀌어 이 작업을 적용하지 못했어요. 최신 내용을 확인한 뒤 다시 요청해 주세요."),
            Map.entry("tool_http_unavailable", "문서 서비스에 연결하지 못해 이 작업을 실행하지 못했어요. 작업 결과를 확인한 뒤 다시 요청해 주세요."),
            // 되돌리기
            Map.entry("rollback_failed", "변경을 되돌리지 못했어요. 작업 기록에서 문서 상태를 확인해 주세요."),
            Map.entry("rollback_child_failed", "함께 실행한 작업 중 일부를 되돌리지 못했어요. 작업 기록에서 문서 상태를 확인해 주세요."),
            Map.entry("rollback_conflict", "그 사이 내용이 바뀌어 변경을 자동으로 되돌리지 못했어요. 작업 기록에서 문서 상태를 확인해 주세요."),
            Map.entry("rollback_database_error", "저장소 문제로 변경을 되돌리지 못했어요. 작업 기록에서 문서 상태를 확인해 주세요."),
            Map.entry("rollback_retry_pending", "변경을 되돌리는 작업을 자동으로 다시 시도하고 있어요. 잠시 기다려 주세요."),
            Map.entry("rollback_document_purged", "영구 삭제된 문서의 변경은 되돌릴 수 없습니다. 나머지 변경은 되돌렸습니다."),
            // document 자체 코드
            Map.entry("wiki_index_preparation_failed", "문서는 저장했지만 위키 검색을 준비하지 못했습니다. 잠시 후 다시 편입해 주세요."),
            Map.entry("PIPELINE_UNAVAILABLE", "AI 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요."),
            Map.entry("PIPELINE_TIMEOUT", "질의 대기 시간이 지나 취소를 요청했습니다."),
            Map.entry("INSUFFICIENT_CREDIT", INSUFFICIENT_CREDIT),
            Map.entry("AI_USAGE_LIMIT_EXCEEDED", AI_USAGE_LIMIT_EXCEEDED));

    /**
     * 목록에 아직 없는 새 코드를 위한 접두사 기본 문구. 개별 항목(MESSAGES)을 먼저 보고, 없을 때만 이 목록을 위에서부터 본다.
     * 더 긴 접두사를 먼저 둔다. rollback_*에는 "code:operation_id" 꼴 값도 온다.
     */
    private static final List<Map.Entry<String, String>> PREFIX_MESSAGES = List.of(
            Map.entry("markdown_create_", "새 문서 초안을 만들지 못해 새 문서를 만들지 않았어요. 요청을 더 구체적으로 적어 다시 시도해 주세요."),
            Map.entry("markdown_", "편집안을 만들지 못해 문서를 바꾸지 않았어요. 요청을 더 구체적으로 적어 다시 시도해 주세요."),
            Map.entry("agent_result_", "AI 결과를 적용할 수 없어 문서를 바꾸지 않았어요. 다시 시도해 주세요."),
            Map.entry("tool_http_", "문서 서비스가 요청을 처리하지 못해 이 작업을 실행하지 못했어요. 작업 결과를 확인한 뒤 다시 요청해 주세요."),
            Map.entry("rollback_", "변경을 되돌리는 중 문제가 생겨 일부가 남아 있을 수 있어요. 작업 기록에서 문서 상태를 확인해 주세요."));

    /** 기능별 기본 문구가 곧 올바른 문구인 코드. 목록에 없다는 경고를 내지 않는다. */
    private static final Set<String> GENERIC_CODES =
            Set.of("internal_error", "task_failed", "agent_turn_failed");

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private AiErrorMessages() {
    }

    public static String query(String code) {
        return query(code, ResponseLanguage.KO);
    }

    public static String query(String code, ResponseLanguage language) {
        return language.isEnglish()
                ? resolve(code, AiErrorMessagesEn.QUERY_DEFAULT, Map.of(), AiErrorMessagesEn.MESSAGES, AiErrorMessagesEn.PREFIX_MESSAGES)
                : resolve(code, QUERY_DEFAULT);
    }

    public static String agent(String code) {
        return agent(code, ResponseLanguage.KO);
    }

    public static String agent(String code, ResponseLanguage language) {
        return language.isEnglish()
                ? resolve(code, AiErrorMessagesEn.AGENT_DEFAULT, AiErrorMessagesEn.AGENT_OVERRIDES, AiErrorMessagesEn.MESSAGES, AiErrorMessagesEn.PREFIX_MESSAGES)
                : resolve(code, AGENT_DEFAULT, AGENT_OVERRIDES);
    }

    public static String ingest(String code) {
        return ingest(code, ResponseLanguage.KO);
    }

    public static String ingest(String code, ResponseLanguage language) {
        return language.isEnglish()
                ? resolve(code, AiErrorMessagesEn.INGEST_DEFAULT, Map.of(), AiErrorMessagesEn.MESSAGES, AiErrorMessagesEn.PREFIX_MESSAGES)
                : resolve(code, INGEST_DEFAULT);
    }

    public static String lint(String code) {
        return lint(code, ResponseLanguage.KO);
    }

    public static String lint(String code, ResponseLanguage language) {
        return language.isEnglish()
                ? resolve(code, AiErrorMessagesEn.LINT_DEFAULT, Map.of(), AiErrorMessagesEn.MESSAGES, AiErrorMessagesEn.PREFIX_MESSAGES)
                : resolve(code, LINT_DEFAULT);
    }

    /** 변환 실패 문구. 항상 "PDF 변환에 실패했습니다."(영어는 "PDF conversion failed.")로 시작한다. */
    public static String convert(String code) {
        return convert(code, ResponseLanguage.KO);
    }

    public static String convert(String code, ResponseLanguage language) {
        return language.isEnglish()
                ? AiErrorMessagesEn.CONVERT_PREFIX + resolve(code, AiErrorMessagesEn.CONVERT_DEFAULT,
                        AiErrorMessagesEn.CONVERT_OVERRIDES, AiErrorMessagesEn.MESSAGES, AiErrorMessagesEn.PREFIX_MESSAGES)
                : CONVERT_PREFIX + resolve(code, CONVERT_DEFAULT, CONVERT_OVERRIDES);
    }

    public static String transcription(String code) {
        return transcription(code, ResponseLanguage.KO);
    }

    public static String transcription(String code, ResponseLanguage language) {
        return language.isEnglish()
                ? resolve(code, AiErrorMessagesEn.TRANSCRIPTION_DEFAULT, AiErrorMessagesEn.TRANSCRIPTION_OVERRIDES,
                        AiErrorMessagesEn.MESSAGES, AiErrorMessagesEn.PREFIX_MESSAGES)
                : resolve(code, TRANSCRIPTION_DEFAULT, TRANSCRIPTION_OVERRIDES);
    }

    /** 코드가 붙은 공통 문구({@code INSUFFICIENT_CREDIT} 등)를 언어별로 돌려준다. 목록에 없으면 null이다. */
    public static String message(String code, ResponseLanguage language) {
        return code == null ? null : (language.isEnglish() ? AiErrorMessagesEn.MESSAGES : MESSAGES).get(code);
    }

    /**
     * 저장된 실패 문구를 요청 언어로 다시 만든다. 한국어 요청이거나 코드가 없는(이전에 저장된) 행은 저장된 문구 그대로다.
     *
     * <p>저장 문구는 기능별 한국어 문구 중 하나이므로, 같은 코드로 그 문구를 만든 기능을 찾아 그 기능의 영어 문구를 쓴다.
     * 어느 기능과도 맞지 않으면(취소 안내처럼 직접 쓴 문구) 코드의 공통 영어 문구, 그것도 없으면 저장 문구다.
     */
    public static String localizeStored(String code, String storedMessage, ResponseLanguage language) {
        if (!language.isEnglish() || code == null || code.isBlank()) {
            return storedMessage;
        }
        List<java.util.function.BiFunction<String, ResponseLanguage, String>> features = List.of(
                AiErrorMessages::agent, AiErrorMessages::query, AiErrorMessages::ingest,
                AiErrorMessages::lint, AiErrorMessages::convert, AiErrorMessages::transcription);
        for (var feature : features) {
            String korean = feature.apply(code, ResponseLanguage.KO);
            // 채팅 말풍선은 varchar(255)라 잘려 저장됐을 수 있다.
            if (korean.equals(storedMessage) || storedMessage != null && !storedMessage.isEmpty()
                    && korean.length() > storedMessage.length() && korean.startsWith(storedMessage)) {
                return feature.apply(code, ResponseLanguage.EN);
            }
        }
        String common = message(code, language);
        return common != null ? common : storedMessage;
    }

    private static String resolve(String code, String fallback) {
        return resolve(code, fallback, Map.of());
    }

    private static String resolve(String code, String fallback, Map<String, String> overrides) {
        return resolve(code, fallback, overrides, MESSAGES, PREFIX_MESSAGES);
    }

    private static String resolve(String code, String fallback, Map<String, String> overrides,
                                  Map<String, String> messages, List<Map.Entry<String, String>> prefixMessages) {
        String override = code == null ? null : overrides.get(code);
        if (override != null) {
            return override;
        }
        if (code == null || code.isBlank() || GENERIC_CODES.contains(code)) {
            return fallback;
        }
        String message = messages.get(code);
        if (message != null) {
            return message;
        }
        for (var prefix : prefixMessages) {
            if (code.startsWith(prefix.getKey())) {
                return prefix.getValue();
            }
        }
        // 미등록 코드가 계속 늘어도 메모리가 자라지 않게 상한을 둔다.
        if (WARNED.size() < 200 && WARNED.add(code)) {
            log.warn("[AI 오류 코드 미등록] code={} 기본 문구로 대체한다. AiErrorMessages에 추가할지 확인한다.", code);
        }
        return fallback;
    }
}
