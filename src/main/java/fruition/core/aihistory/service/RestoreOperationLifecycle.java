package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.repository.OperationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 복구 작업의 시작과 종료를 호출자가 소유한 트랜잭션에서 기록한다.
 *
 * <p>{@link RestoreExecuteService}와 분리한 이유는 자기 호출로는 {@code @Transactional}이
 * 걸리지 않기 때문이다.
 */
@Component
public class RestoreOperationLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RestoreOperationLifecycle.class);

    /** 이미 끝난 복구. 판단 기준은 {@link OperationStatus#isTerminal()} 하나로 둔다. */
    private static final Set<OperationStatus> TERMINAL_STATUSES =
            Arrays.stream(OperationStatus.values())
                    .filter(OperationStatus::isTerminal)
                    .collect(Collectors.toUnmodifiableSet());

    private final OperationLogRepository operationLogRepository;

    public RestoreOperationLifecycle(OperationLogRepository operationLogRepository) {
        this.operationLogRepository = operationLogRepository;
    }

    /** 인증된 복구 대상에 같은 토큰 지문이 이미 선점됐는지 확인한다. */
    @Transactional(readOnly = true)
    public boolean isClaimed(String restoredFrom, String restoreTokenHash) {
        return operationLogRepository.existsByOperationTypeAndRestoredFromAndRestoreTokenHash(
                OperationType.restore, restoredFrom, restoreTokenHash);
    }

    /**
     * 복구 작업을 {@code applying}으로 기록한다. 문서 복구에서는 본문 저장과 같은 트랜잭션에
     * 포함되어 반영 중 실패하면 claim도 함께 롤백된다.
     */
    @Transactional
    public Optional<OperationLog> start(OperationLog target, String manifestJson,
                                        String restoreTokenHash, Instant now) {
        String operationId = "op_" + randomSuffix();
        if (operationLogRepository.insertRestoreIfAbsent(
                operationId, target.getWorkspaceId(), target.getUserId(),
                target.getTargetDocumentId(), target.getOperationId(), manifestJson,
                restoreTokenHash, now) == 0) {
            return Optional.empty();
        }
        return operationLogRepository.findById(operationId);
    }

    /** Kafka command outbox와 같은 바깥 트랜잭션에 참여해 queued 복구를 등록한다. */
    @Transactional
    public Optional<OperationLog> startQueued(OperationLog target, String manifestJson,
                                               String restoreTokenHash, Instant now) {
        String operationId = "op_" + randomSuffix();
        if (operationLogRepository.insertRestoreIfAbsent(
                operationId, target.getWorkspaceId(), target.getUserId(),
                target.getTargetDocumentId(), target.getOperationId(), manifestJson,
                restoreTokenHash, now) == 0) {
            return Optional.empty();
        }
        return operationLogRepository.findById(operationId);
    }

    /**
     * Kafka 결과 receipt와 같은 트랜잭션에서 실패 상태를 확정한다.
     *
     * <p>여기까지 온 복구는 Wiki를 하나도 바꾸지 못했다. 반영은 워커가 succeeded를 보고했을 때만
     * 일어나기 때문이다. 그래서 선점을 풀어 같은 미리보기로 다시 시도할 수 있게 한다.
     * 풀지 않으면 미리보기 토큰이 계획의 결정적 해시라 그 복구를 영영 다시 시도할 수 없다.
     *
     * <p>이미 끝난 복구는 건드리지 않는다. 늦게 도착한 실패 신호에 선점까지 풀면 반영이 끝난
     * 복구를 같은 미리보기로 한 번 더 실행할 수 있게 된다. 확인과 갱신은 한 UPDATE로 묶어
     * 성공 확정과 겹쳐도 그 사이를 비집고 들어갈 수 없게 한다.
     */
    @Transactional
    public void fail(String restoreOperationId, String reason, Instant now) {
        int failed = operationLogRepository.failRestoreIfNotTerminal(
                restoreOperationId, OperationStatus.failed,
                OperationFailureSummary.of(OperationType.restore, OperationStatus.failed),
                now, TERMINAL_STATUSES);
        if (failed == 0) {
            log.warn("[복구 실패 신호 무시] 이미 끝난 복구다. operationId={} reason={}",
                    restoreOperationId, reason);
            return;
        }
        log.warn("[복구 실패 확정] operationId={} reason={}", restoreOperationId, reason);
    }

    /** 문서 되돌리기는 재작성이 없어 본문 저장과 같은 트랜잭션에서 확정한다. */
    @Transactional
    public void finishDocument(String restoreOperationId, long toVersion, long newVersion, Instant now) {
        operationLogRepository.findById(restoreOperationId).ifPresent(restore ->
                restore.complete(OperationStatus.succeeded,
                        "버전 " + toVersion + " 내용으로 되돌렸습니다. 새 버전 " + newVersion,
                        1, null, now));
    }

    private String randomSuffix() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
