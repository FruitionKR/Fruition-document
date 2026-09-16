package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.repository.OperationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/** lint 실행 전에 작업 로그를 등록하고 호출 실패를 확정한다. */
@Component
public class LintOperationStarter {

    private static final Logger log = LoggerFactory.getLogger(LintOperationStarter.class);

    private final OperationLogRepository operationLogRepository;

    public LintOperationStarter(OperationLogRepository operationLogRepository) {
        this.operationLogRepository = operationLogRepository;
    }

    @Transactional
    public String start(String workspaceId, String userId) {
        String operationId = "op_" + randomSuffix();
        operationLogRepository.save(OperationLog.processing(
                operationId, workspaceId, userId, OperationType.lint, null,
                "위키 다듬기", Instant.now()));
        return operationId;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String operationId, String reason) {
        operationLogRepository.findById(operationId).ifPresent(operation -> {
            // 오류 원문은 운영자용이라 로그로만 남기고, 저장하는 요약은 사용자용 문구로 쓴다.
            log.warn("[lint 실패 확정] operationId={} reason={}", operationId, reason);
            operation.complete(OperationStatus.failed,
                    OperationFailureSummary.of(OperationType.lint, OperationStatus.failed),
                    0, null, Instant.now());
        });
    }

    private String randomSuffix() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
