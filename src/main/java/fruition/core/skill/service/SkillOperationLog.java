package fruition.core.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.skill.exception.PipelineSkillException;
import fruition.shared.logging.CommittedLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/** 스킬 작업의 요청·완료·거부·실패를 같은 형식으로 남긴다. 지시문·입력·결과 원문은 남기지 않는다. */
final class SkillOperationLog {
    private static final Logger log = LoggerFactory.getLogger(SkillOperationLog.class);

    private SkillOperationLog() {}

    static <T> T track(String operation, String title, String skillId, String runId, String workspaceId, String userId, Supplier<T> call) {
        long startedAt = System.nanoTime();
        log.debug("[{} 요청] operation={} stage=requested skillId={} runId={} workspaceId={} userId={}", title, operation, skillId, runId, workspaceId, userId);
        try {
            T result = call.get();
            String resultId = skillId != null ? skillId : resultSkillId(result);
            String status = result instanceof JsonNode node ? node.path("status").asText(null) : null;
            String statusField = status == null ? "" : " runStatus=" + CommittedLog.code(status);
            log.info("[{} 완료] operation={} stage=completed skillId={} runId={} workspaceId={} userId={}{} elapsedMs={} 설명=AI 서비스가 처리 결과를 돌려줬습니다. 문서를 바꾸지는 않았습니다.",
                    title, operation, resultId, runId, workspaceId, userId, statusField, elapsedMs(startedAt));
            return result;
        } catch (PipelineSkillException e) {
            String code = e.getCode() != null ? e.getCode() : e.getHttpStatus() < 500 ? "SKILL_REQUEST_REJECTED" : "SKILL_AI_UNAVAILABLE";
            String template = "[{} {}] operation={} stage=pipeline errorCode={} retryable={} skillId={} runId={} workspaceId={} userId={} status={} elapsedMs={} 원인={}";
            Object[] fields = {title, e.getHttpStatus() < 500 ? "거부" : "실패", operation, code, e.getHttpStatus() >= 500, skillId, runId, workspaceId, userId,
                    e.getHttpStatus(), elapsedMs(startedAt), CommittedLog.reason(code)};
            if (e.getHttpStatus() < 500) log.warn(template, fields); else log.error(template, fields);
            throw e;
        } catch (RuntimeException e) {
            log.warn("[{} 중단] operation={} stage=request errorCode=UNCLASSIFIED skillId={} runId={} workspaceId={} userId={} errorType={} elapsedMs={} 원인=권한·입력 거부 또는 처리 오류로 끝나지 못했습니다. 같은 requestId의 공통 예외 로그로 구분합니다.",
                    title, operation, skillId, runId, workspaceId, userId, e.getClass().getSimpleName(), elapsedMs(startedAt));
            throw e;
        }
    }

    private static String resultSkillId(Object result) {
        if (!(result instanceof JsonNode node)) return null;
        return node.hasNonNull("skill_id") ? node.path("skill_id").asText() : node.path("id").asText(null);
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
