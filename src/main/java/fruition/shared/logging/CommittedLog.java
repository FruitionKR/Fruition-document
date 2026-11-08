package fruition.shared.logging;

import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Map;
import java.util.regex.Pattern;

/** Terminal state logs follow the database commit, including the originating request context. */
public final class CommittedLog {
    private static final Pattern CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private CommittedLog() {}
    /** Never put arbitrary provider messages or model output in a code field. */
    public static String code(String value) {
        return value != null && CODE.matcher(value).matches() ? value : "unknown";
    }
    /** Operator-facing cause text; identifiers/codes remain separate for filtering. */
    public static String reason(String value) {
        return REASONS.getOrDefault(code(value), UNCLASSIFIED_REASON);
    }
    /** Upstream strings only become stable codes when listed here; keep the raw code in upstreamCode. */
    public static String stableCode(String value) {
        return REASONS.containsKey(code(value)) ? code(value) : "UNCLASSIFIED";
    }
    private static final String UNCLASSIFIED_REASON = "실패 원인이 세부 항목으로 분류되지 않았습니다. 오류 코드와 최초 예외 로그를 확인해야 합니다.";
    private static final String GENERIC_FAILURE = "작업 처리에 실패했습니다. 오류 코드와 최초 예외 로그로 세부 원인을 확인해야 합니다.";
    private static final String TIMED_OUT = "정해진 시간 안에 작업 결과를 받지 못했습니다.";
    private static final String AI_UNAVAILABLE = "AI 서비스에 연결하거나 정상 응답을 받지 못했습니다.";
    private static final String NO_CREDIT = "사용 가능한 크레딧이 부족합니다.";
    private static final String OVER_AI_LIMIT = "설정된 AI 사용 한도를 넘었습니다.";
    private static final Map<String, String> REASONS = Map.ofEntries(
            Map.entry("agent_result_invalid_request", "AI 결과에 요청 정보가 없거나 요청 정보의 형식이 올바르지 않습니다."),
            Map.entry("agent_result_invalid_payload", "AI 결과의 본문이 없거나 결과 형식이 올바르지 않습니다."),
            Map.entry("agent_result_unsupported_action", "AI가 서버에서 지원하지 않는 작업 종류를 반환했습니다."),
            Map.entry("agent_result_missing_canonical_markdown", "AI 결과에 적용할 문서 본문이 없습니다."),
            Map.entry("agent_result_request_mismatch", "AI 결과의 대상 정보가 원래 작업 요청과 일치하지 않습니다."),
            Map.entry("MEETING_NOTES_INVALID", "회의록 초안에 필수 요약 목록이 없거나 형식이 올바르지 않습니다."),
            Map.entry("MEETING_NOTES_TIMEOUT", TIMED_OUT),
            Map.entry("deadline_exceeded", TIMED_OUT),
            Map.entry("PIPELINE_TIMEOUT", TIMED_OUT),
            Map.entry("MEETING_NOTES_UNAVAILABLE", AI_UNAVAILABLE),
            Map.entry("provider_unavailable", AI_UNAVAILABLE),
            Map.entry("PIPELINE_UNAVAILABLE", AI_UNAVAILABLE),
            Map.entry("provider_rate_limited", "AI 서비스의 요청량 제한에 걸렸습니다."),
            Map.entry("input_rejected", "AI 서비스가 입력 내용이나 길이를 받아들이지 않았습니다."),
            Map.entry("interrupted_attempts_exhausted", "서버 작업이 반복해서 중단돼 재시도를 모두 사용했습니다."),
            Map.entry("dependency_failed", "앞선 작업이 실패해 이 작업을 건너뛰었습니다."),
            Map.entry("INSUFFICIENT_CREDIT", NO_CREDIT),
            Map.entry("insufficient_credit", NO_CREDIT),
            Map.entry("AI_USAGE_LIMIT_EXCEEDED", OVER_AI_LIMIT),
            Map.entry("ai_usage_limit_exceeded", OVER_AI_LIMIT),
            Map.entry("rollback_conflict", "현재 작업 상태와 취소 복구 요청이 충돌했습니다."),
            Map.entry("restore_conflict", "되돌리기 접수 뒤 위키가 바뀌어 되돌리지 못했습니다."),
            Map.entry("rollback_failed", "AI 서비스가 변경 복구 실패를 알렸습니다."),
            Map.entry("rollback_child_failed", "함께 실행한 작업 중 일부를 복구하지 못했습니다."),
            Map.entry("rollback_database_error", "저장소 문제로 변경을 복구하지 못했습니다."),
            Map.entry("rollback_document_purged", "영구 삭제된 문서가 있어 일부 변경을 복구하지 못했습니다."),
            Map.entry("backpressure", "음성 전송이 밀려 실시간 처리를 이어 가지 못했습니다."),
            Map.entry("invalid_audio", "음성 전송 형식이나 전송 순서가 올바르지 않습니다."),
            Map.entry("not_member", "회의에 접근할 수 있는 멤버 권한을 확인하지 못했습니다."),
            Map.entry("meeting_not_open", "실시간 녹음을 시작할 수 있는 회의 상태가 아닙니다."),
            Map.entry("transcript_limit", "전사할 수 있는 대화 길이 한도에 도달했습니다."),
            Map.entry("transcript_save_failed", "받아쓴 대화 내용을 저장하지 못했습니다."),
            Map.entry("transcript_incomplete", "마지막 문장까지 저장됐는지 확인하지 못했습니다."),
            Map.entry("segment_conflict", "같은 구간에 다른 확정 문장이 도착했습니다."),
            Map.entry("transcription_failed", "음성 전사 서비스가 실패를 알리거나 연결이 중단됐습니다."),
            Map.entry("MEETING_TRANSCRIPT_EMPTY", "녹음에서 인식된 음성이 없습니다."),
            Map.entry("MEETING_TRANSCRIPT_TOO_LONG", "전사 내용이 회의록 생성 한도를 넘었습니다."),
            Map.entry("MEETING_RECORDING_TOO_LONG", "녹음 길이가 전사 한도를 넘었습니다."),
            Map.entry("MEETING_RECORDING_UNREADABLE", "지원하지 않거나 인식할 수 없는 녹음 파일입니다."),
            Map.entry("MEETING_NOTES_FAILED", GENERIC_FAILURE),
            Map.entry("internal_error", GENERIC_FAILURE),
            Map.entry("agent_turn_failed", GENERIC_FAILURE),
            Map.entry("task_failed", GENERIC_FAILURE),
            Map.entry("INVALID_PDF", "파일 내용이 PDF 형식이 아닙니다."),
            Map.entry("UNSUPPORTED_DOCUMENT_FILE", "지원하지 않는 파일 형식입니다."),
            Map.entry("INVALID_DOCUMENT_FILENAME", "파일 이름이 올바르지 않습니다."),
            Map.entry("INVALID_DOCUMENT_ORIGIN", "문서 출처 값이 올바르지 않습니다."),
            Map.entry("DOCUMENT_WRITE_FORBIDDEN", "이 위치에 문서를 저장할 권한이 없습니다."),
            Map.entry("FILE_TOO_LARGE", "파일이 업로드 크기 한도를 넘었습니다."),
            Map.entry("TOO_MANY_FILES", "한 번에 올릴 수 있는 파일 수를 넘었습니다."),
            Map.entry("STORAGE_QUOTA_EXCEEDED", "저장 용량 한도를 넘었습니다."),
            Map.entry("UPLOAD_PARTS_INCOMPLETE", "아직 전송되지 않은 업로드 조각이 있습니다."),
            Map.entry("UPLOAD_PARTS_INVALID", "업로드 조각의 크기나 순서가 맞지 않습니다."),
            Map.entry("INVALID_IDEMPOTENCY_KEY", "멱등 키가 없거나 형식이 올바르지 않습니다."),
            Map.entry("IDEMPOTENCY_KEY_CONFLICT", "같은 멱등 키로 다른 내용의 요청을 보냈습니다."),
            Map.entry("IDEMPOTENCY_IN_PROGRESS", "같은 멱등 키의 요청이 아직 처리 중입니다."),
            Map.entry("INVALID_MARKDOWN_CONTENT", "Markdown 내용이 올바르지 않습니다."),
            Map.entry("MARKDOWN_CONTENT_TOO_LARGE", "Markdown 본문이 크기 한도를 넘었습니다."),
            Map.entry("IMPORT_INVALID", "유효한 가져오기 세션이 아닙니다."),
            Map.entry("UPLOAD_METADATA_MISMATCH", "조립한 파일의 크기나 형식이 처음 알린 정보와 다릅니다."),
            Map.entry("SKILL_INTENT_AMBIGUOUS", "스킬이 반복할 작업을 특정할 수 없습니다."),
            Map.entry("SKILL_INTENT_UNSUPPORTED", "지원하지 않는 스킬 작업입니다."),
            Map.entry("SKILL_INSTRUCTION_INVALID", "스킬 지침의 길이·이름·참조 문서가 올바르지 않습니다."),
            Map.entry("SKILL_RESULT_MODE_INVALID", "스킬 결과 방식이 기능 구성과 맞지 않습니다."),
            Map.entry("SKILL_TARGETS_INVALID", "스킬 적용 대상이 올바르지 않습니다."),
            Map.entry("SKILL_CAPABILITIES_INVALID", "스킬 기능 구성이 올바르지 않습니다."),
            Map.entry("SKILL_TEST_RUN_UNSUPPORTED", "이 종류의 스킬은 테스트 실행을 지원하지 않습니다."),
            Map.entry("SKILL_TEST_RUN_UNAVAILABLE", "AI 서비스가 스킬 테스트 실행을 아직 제공하지 않습니다."),
            Map.entry("SKILL_REQUEST_REJECTED", "AI 서비스가 스킬 요청을 거절했습니다."),
            Map.entry("SKILL_AI_UNAVAILABLE", AI_UNAVAILABLE));
    /** 사용자 입력·한도·충돌처럼 예상 가능한 최종 실패. 누군가 조치해야 하는 장애가 아니므로 WARN이다. */
    private static final java.util.Set<String> EXPECTED_FAILURES = java.util.Set.of(
            "input_rejected", "provider_rate_limited", "INSUFFICIENT_CREDIT", "insufficient_credit",
            "AI_USAGE_LIMIT_EXCEEDED", "ai_usage_limit_exceeded", "MEETING_TRANSCRIPT_EMPTY", "MEETING_TRANSCRIPT_TOO_LONG",
            "MEETING_RECORDING_TOO_LONG", "MEETING_RECORDING_UNREADABLE", "rollback_conflict", "restore_conflict",
            "rollback_document_purged", "cancelled", "agent_turn_cancelled");
    /** 최종 실패를 커밋 뒤에 남긴다. 예상 가능한 실패는 WARN, 그 밖은 조치가 필요한 ERROR다. */
    public static void failure(Logger logger, String code, String template, Object... arguments) {
        if (EXPECTED_FAILURES.contains(code(code))) warn(logger, template, arguments);
        else error(logger, template, arguments);
    }
    public static void warn(Logger logger, String template, Object... arguments) {
        afterCommit(() -> logger.warn(template, arguments));
    }
    public static void debug(Logger logger, String template, Object... arguments) {
        afterCommit(() -> logger.debug(template, arguments));
    }
    public static void info(Logger logger, String template, Object... arguments) {
        afterCommit(() -> logger.info(template, arguments));
    }
    public static void error(Logger logger, String template, Object... arguments) {
        afterCommit(() -> logger.error(template, arguments));
    }
    static void afterCommit(Runnable record) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        Runnable contextual = () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (context == null) MDC.clear(); else MDC.setContextMap(context);
                record.run();
            } finally {
                if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { contextual.run(); }
            });
        } else contextual.run();
    }
}
