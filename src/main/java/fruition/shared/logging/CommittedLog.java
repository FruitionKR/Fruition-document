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
        return switch (code(value)) {
            case "agent_result_invalid_request" -> "AI 결과에 요청 정보가 없거나 요청 정보의 형식이 올바르지 않습니다.";
            case "agent_result_invalid_payload" -> "AI 결과의 본문이 없거나 결과 형식이 올바르지 않습니다.";
            case "agent_result_unsupported_action" -> "AI가 서버에서 지원하지 않는 작업 종류를 반환했습니다.";
            case "agent_result_missing_canonical_markdown" -> "AI 결과에 적용할 문서 본문이 없습니다.";
            case "agent_result_request_mismatch" -> "AI 결과의 대상 정보가 원래 작업 요청과 일치하지 않습니다.";
            case "MEETING_NOTES_INVALID" -> "회의록 초안에 필수 요약 목록이 없거나 형식이 올바르지 않습니다.";
            case "MEETING_NOTES_TIMEOUT", "deadline_exceeded" -> "정해진 시간 안에 작업 결과를 받지 못했습니다.";
            case "MEETING_NOTES_UNAVAILABLE", "provider_unavailable", "PIPELINE_UNAVAILABLE" -> "AI 서비스에 연결하거나 정상 응답을 받지 못했습니다.";
            case "provider_rate_limited" -> "AI 서비스의 요청량 제한에 걸렸습니다.";
            case "INSUFFICIENT_CREDIT", "insufficient_credit" -> "사용 가능한 크레딧이 부족합니다.";
            case "AI_USAGE_LIMIT_EXCEEDED", "ai_usage_limit_exceeded" -> "설정된 AI 사용 한도를 넘었습니다.";
            case "rollback_conflict" -> "현재 작업 상태와 취소 복구 요청이 충돌했습니다.";
            case "backpressure" -> "음성 전송이 밀려 실시간 처리를 이어 가지 못했습니다.";
            case "invalid_audio" -> "음성 전송 형식이나 전송 순서가 올바르지 않습니다.";
            case "not_member" -> "회의에 접근할 수 있는 멤버 권한을 확인하지 못했습니다.";
            case "meeting_not_open" -> "실시간 녹음을 시작할 수 있는 회의 상태가 아닙니다.";
            case "transcript_limit" -> "전사할 수 있는 대화 길이 한도에 도달했습니다.";
            case "transcript_save_failed" -> "받아쓴 대화 내용을 저장하지 못했습니다.";
            case "transcription_failed" -> "음성 전사 서비스가 실패를 알리거나 연결이 중단됐습니다.";
            case "MEETING_NOTES_FAILED", "internal_error", "agent_turn_failed", "task_failed" -> "작업 처리에 실패했습니다. 오류 코드와 최초 예외 로그로 세부 원인을 확인해야 합니다.";
            default -> "실패 원인이 세부 항목으로 분류되지 않았습니다. 오류 코드와 최초 예외 로그를 확인해야 합니다.";
        };
    }
    public static void warn(Logger logger, String template, Object... arguments) {
        afterCommit(() -> logger.warn(template, arguments));
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
