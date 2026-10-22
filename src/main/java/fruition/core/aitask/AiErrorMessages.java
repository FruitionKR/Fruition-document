package fruition.core.aitask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 실패 코드를 사용자에게 보일 한국어 문구로 바꾸는 유일한 목록이다.
 *
 * <p>ai는 실패 원인을 코드로만 보내고 예외 원문은 자기 로그에만 남긴다. 화면·SSE·저장 컬럼에
 * 나가는 문구는 모두 여기를 거친다. 목록에 없는 코드는 기능별 기본 문구로 바꾸고 코드마다
 * 한 번 WARN을 남겨 목록에 추가할지 판단하게 한다.
 */
public final class AiErrorMessages {

    private static final Logger log = LoggerFactory.getLogger(AiErrorMessages.class);

    /** {@code INSUFFICIENT_CREDIT}: 크레딧이 모자라 작업을 시작하지 않았을 때(402 거절·비동기 작업 실패). */
    public static final String INSUFFICIENT_CREDIT = "크레딧이 부족해 작업을 시작하지 않았습니다. 크레딧을 충전한 뒤 다시 시도해 주세요.";
    /** {@code INSUFFICIENT_CREDIT}: 진행 중이던 작업을 이어 가지 못할 때(구간 갱신·Agent 계획 수정·승인). */
    public static final String INSUFFICIENT_CREDIT_STOPPED = "크레딧이 부족해 중단했습니다. 크레딧을 충전한 뒤 다시 시도해 주세요.";

    private static final String QUERY_DEFAULT = "질의 처리 중 오류가 발생했습니다.";
    private static final String AGENT_DEFAULT = "Agent 처리 중 오류가 발생했습니다.";
    private static final String INGEST_DEFAULT = "위키 편입에 실패했습니다.";
    private static final String LINT_DEFAULT = "위키 정리에 실패했습니다.";
    private static final String CONVERT_PREFIX = "PDF 변환에 실패했습니다. ";
    private static final String CONVERT_DEFAULT = "변환 중 오류가 발생했습니다.";
    private static final String TRANSCRIPTION_DEFAULT = "녹음 파일을 글로 옮기지 못했습니다. 다시 올려 주세요.";

    /** 변환에서만 공통 문구 대신 쓰는 문구. */
    private static final Map<String, String> CONVERT_OVERRIDES = Map.of(
            "input_rejected", "암호가 걸렸거나 손상되어 변환할 수 없는 PDF입니다.");

    /** 회의 전사에서만 공통 문구 대신 쓰거나 더하는 문구. */
    private static final Map<String, String> TRANSCRIPTION_OVERRIDES = Map.of(
            "MEETING_RECORDING_TOO_LONG", "녹음이 3시간을 넘어 글로 옮길 수 없습니다. 나눠서 올려 주세요.",
            "MEETING_RECORDING_UNREADABLE", "지원하지 않거나 인식할 수 없는 녹음 파일입니다.",
            "input_rejected", "지원하지 않거나 인식할 수 없는 녹음 파일입니다.",
            "MEETING_TRANSCRIPT_EMPTY", "인식된 음성이 없습니다.",
            "MEETING_TRANSCRIPT_TOO_LONG", "대화 내용이 회의록 생성 한도(100,000자)를 넘었습니다. 녹음을 나눠 올려 주세요.",
            "deadline_exceeded", "글로 옮기는 시간이 너무 오래 걸려 중단했습니다. 녹음을 나눠 올려 주세요.");

    private static final Map<String, String> MESSAGES = Map.ofEntries(
            // ai 공통 코드
            Map.entry("provider_rate_limited", "AI 서비스 요청이 많아 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
            Map.entry("provider_unavailable", "AI 서비스에 일시적인 문제가 있어 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
            Map.entry("input_rejected", "요청 내용을 처리할 수 없습니다. 입력을 확인해 주세요."),
            Map.entry("deadline_exceeded", "처리 시간이 초과되어 중단했습니다. 다시 시도해 주세요."),
            Map.entry("cancelled", "처리가 취소되었습니다."),
            Map.entry("interrupted_attempts_exhausted", "처리가 여러 번 중단되어 완료하지 못했습니다. 다시 시도해 주세요."),
            Map.entry("dependency_failed", "앞선 작업이 실패해 이 작업은 건너뛰었습니다."),
            Map.entry("rollback_child_failed", "변경을 되돌리지 못했습니다. 작업 기록을 확인해 주세요."),
            // Agent
            Map.entry("agent_turn_cancelled", "Agent 작업이 취소되었습니다."),
            Map.entry("agent_turn_interrupted", "Agent 작업이 중단되었습니다. 다시 시도해 주세요."),
            Map.entry("invalid_agent_turn_request", "Agent 요청이 올바르지 않습니다."),
            Map.entry("agent_not_configured", "Agent를 사용할 수 없는 상태입니다. 관리자에게 문의해 주세요."),
            Map.entry("agent_turn_route_contract_failed", "Agent 처리 결과를 확정하지 못했습니다. 다시 시도해 주세요."),
            Map.entry("SKILL_NOT_FOUND", "선택한 Skill을 찾을 수 없습니다."),
            Map.entry("SKILL_DISABLED", "선택한 Skill이 비활성화되어 있습니다."),
            Map.entry("REFERENCE_DOCUMENT_TOO_LARGE", "편집 대상 참조 문서가 30,000자를 넘어 처리할 수 없습니다."),
            Map.entry("invalid_llm_selection", "선택한 모델을 사용할 수 없습니다. 모델을 다시 선택해 주세요."),
            Map.entry("agent_result_invalid_request", "Agent 결과의 요청 정보가 올바르지 않습니다."),
            Map.entry("agent_result_invalid_payload", "Agent 결과 형식이 올바르지 않습니다."),
            Map.entry("agent_result_unsupported_action", "지원하지 않는 Agent 처리 결과입니다."),
            Map.entry("agent_result_missing_canonical_markdown", "편집안 본문이 비어 있어 적용할 수 없습니다."),
            Map.entry("agent_result_request_mismatch", "요청과 결과가 달라 적용할 수 없습니다."),
            Map.entry("rollback_failed", "변경을 되돌리지 못했습니다. 작업 기록을 확인해 주세요."),
            Map.entry("mutation_intent_required", "어떤 변경을 원하시는지 조금 더 구체적으로 알려 주세요."),
            Map.entry("ready_markdown_migration_unrecoverable", "이전 편집안을 복구할 수 없어 적용하지 못했습니다. 다시 요청해 주세요."),
            // document 자체 코드
            Map.entry("PIPELINE_UNAVAILABLE", "AI 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요."),
            Map.entry("PIPELINE_TIMEOUT", "질의 대기 시간이 지나 취소를 요청했습니다."),
            Map.entry("INSUFFICIENT_CREDIT", INSUFFICIENT_CREDIT));

    /**
     * 접두사로 묶는 코드. 개별 항목(MESSAGES)을 먼저 보고, 없을 때만 이 목록을 위에서부터 본다.
     * markdown_* 는 편집안 계약 위반 세부 사유라 한 문구로 보인다.
     */
    private static final List<Map.Entry<String, String>> PREFIX_MESSAGES = List.of(
            Map.entry("markdown_", "편집안을 만들지 못했습니다. 다시 시도해 주세요."),
            Map.entry("rollback_", "변경을 되돌리지 못했습니다. 작업 기록을 확인해 주세요."));

    /** 기능별 기본 문구가 곧 올바른 문구인 코드. 목록에 없다는 경고를 내지 않는다. */
    private static final Set<String> GENERIC_CODES =
            Set.of("internal_error", "task_failed", "agent_turn_failed");

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private AiErrorMessages() {
    }

    public static String query(String code) {
        return resolve(code, QUERY_DEFAULT);
    }

    public static String agent(String code) {
        return resolve(code, AGENT_DEFAULT);
    }

    public static String ingest(String code) {
        return resolve(code, INGEST_DEFAULT);
    }

    public static String lint(String code) {
        return resolve(code, LINT_DEFAULT);
    }

    /** 변환 실패 문구. 항상 "PDF 변환에 실패했습니다."로 시작한다. */
    public static String convert(String code) {
        return CONVERT_PREFIX + resolve(code, CONVERT_DEFAULT, CONVERT_OVERRIDES);
    }

    public static String transcription(String code) {
        return resolve(code, TRANSCRIPTION_DEFAULT, TRANSCRIPTION_OVERRIDES);
    }

    private static String resolve(String code, String fallback) {
        return resolve(code, fallback, Map.of());
    }

    private static String resolve(String code, String fallback, Map<String, String> overrides) {
        String override = code == null ? null : overrides.get(code);
        if (override != null) {
            return override;
        }
        if (code == null || code.isBlank() || GENERIC_CODES.contains(code)) {
            return fallback;
        }
        String message = MESSAGES.get(code);
        if (message != null) {
            return message;
        }
        for (var prefix : PREFIX_MESSAGES) {
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
