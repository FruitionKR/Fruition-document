package fruition.shared.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** HTTP Idempotency-Key 요청을 실행 전에 선점하고 완료 응답을 저장·재생한다. */
@Service
public class IdempotencyService {

    static final long EXECUTION_LEASE_SECONDS = 15L * 60;
    static final long RESPONSE_TTL_SECONDS = 24L * 60 * 60;

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;
    /** 선점·해제 전용. 호출자 트랜잭션이 롤백돼도 살아남아야 하므로 항상 별도 트랜잭션이다. */
    private final TransactionTemplate transaction;
    /** 실행 전용. 호출자 트랜잭션에 합류해 비즈니스 변경과 완료 기록을 한 트랜잭션에 둔다. */
    private final TransactionTemplate actionTransaction;
    private final Clock clock;
    private final ThreadLocal<ActiveClaim> currentClaim = new ThreadLocal<>();

    @Autowired
    public IdempotencyService(IdempotencyRecordRepository repository,
                              ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager) {
        this(repository, objectMapper, transactionManager, Clock.systemUTC());
    }

    IdempotencyService(IdempotencyRecordRepository repository,
                       ObjectMapper objectMapper,
                       PlatformTransactionManager transactionManager,
                       Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.actionTransaction = new TransactionTemplate(transactionManager);
        this.actionTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    public void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key는 1자 이상 255자 이하여야 합니다.");
        }
    }

    public String requestHash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("\0", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    public <T> T execute(String userId,
                         String endpointScope,
                         String idempotencyKey,
                         String requestHash,
                         Class<T> responseType,
                         int responseStatus,
                         Function<T, String> resourceId,
                         Supplier<T> action) {
        validateKey(idempotencyKey);
        Claim claim = transaction.execute(status -> claim(
                userId, endpointScope, idempotencyKey, requestHash));
        if (claim == null) {
            throw new IllegalStateException("멱등성 선점 결과를 확인할 수 없습니다.");
        }
        if (!claim.acquired()) {
            return deserializeCompleted(claim.replay(), responseType);
        }

        ActiveClaim previousClaim = currentClaim.get();
        currentClaim.set(new ActiveClaim(claim, previousClaim));
        try {
            T response = actionTransaction.execute(status -> {
                registerReleaseOnRollback(claim);
                T executed = action.get();
                String responseBody = serialize(executed);
                if (repository.complete(
                        claim.id(), claim.token(), responseStatus, resourceId.apply(executed), responseBody,
                        clock.instant().plusSeconds(RESPONSE_TTL_SECONDS)) != 1) {
                    throw new IllegalStateException("멱등성 완료 상태를 저장할 수 없습니다.");
                }
                return executed;
            });
            if (response == null) {
                throw new IllegalStateException("멱등성 요청 결과를 확인할 수 없습니다.");
            }
            return response;
        } catch (RuntimeException | Error exception) {
            transaction.executeWithoutResult(status -> repository.release(claim.id(), claim.token()));
            throw exception;
        } finally {
            restoreCurrentClaim(previousClaim);
        }
    }

    /** 기존 트랜잭션형 서비스에서 실행 전 선점 또는 완료 응답 재생을 수행한다. */
    public <T> Optional<T> replay(String userId,
                                  String endpointScope,
                                  String idempotencyKey,
                                  String requestHash,
                                  Class<T> responseType) {
        validateKey(idempotencyKey);
        Claim claim = transaction.execute(status -> claim(
                userId, endpointScope, idempotencyKey, requestHash));
        if (claim == null) {
            throw new IllegalStateException("멱등성 선점 결과를 확인할 수 없습니다.");
        }
        if (!claim.acquired()) {
            return Optional.of(deserializeCompleted(claim.replay(), responseType));
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            transaction.executeWithoutResult(status -> repository.release(claim.id(), claim.token()));
            throw new IllegalStateException("멱등성 요청은 활성 트랜잭션에서 실행해야 합니다.");
        }
        ActiveClaim previousClaim = currentClaim.get();
        ActiveClaim activeClaim = new ActiveClaim(claim, previousClaim);
        currentClaim.set(activeClaim);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (currentClaim.get() == activeClaim) {
                    restoreCurrentClaim(previousClaim);
                }
                if (status != STATUS_COMMITTED) {
                    transaction.executeWithoutResult(
                            ignored -> repository.release(claim.id(), claim.token()));
                }
            }
        });
        return Optional.empty();
    }

    /** 선점한 요청의 응답을 비즈니스 변경과 같은 트랜잭션에서 완료 상태로 전환한다. */
    public void save(String userId,
                     String endpointScope,
                     String idempotencyKey,
                     String requestHash,
                     int responseStatus,
                     String resourceId,
                     Object response) {
        ActiveClaim activeClaim = currentClaim.get();
        if (activeClaim == null) {
            throw new IllegalStateException("멱등성 선점 없이 응답을 저장할 수 없습니다.");
        }
        Claim claim = activeClaim.claim();
        if (repository.complete(
                claim.id(), claim.token(), responseStatus, resourceId, serialize(response),
                clock.instant().plusSeconds(RESPONSE_TTL_SECONDS)) != 1) {
            throw new IllegalStateException("멱등성 완료 상태를 저장할 수 없습니다.");
        }
        restoreCurrentClaim(activeClaim.previous());
    }

    /** 현재 실행에만 속하는 resource ID seed. lease 재선점 시 새 값으로 fencing된다. */
    public Optional<UUID> currentExecutionId() {
        ActiveClaim activeClaim = currentClaim.get();
        return activeClaim == null ? Optional.empty() : Optional.of(activeClaim.claim().token());
    }

    private void restoreCurrentClaim(ActiveClaim previousClaim) {
        if (previousClaim == null) {
            currentClaim.remove();
        } else {
            currentClaim.set(previousClaim);
        }
    }

    /**
     * 실행 트랜잭션이 커밋되지 않으면 선점을 해제한다.
     * 호출자 트랜잭션에 합류한 경우 완료 기록도 함께 롤백되므로, 그 시점에 선점을 반드시 풀어야 한다.
     */
    private void registerReleaseOnRollback(Claim claim) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    transaction.executeWithoutResult(
                            ignored -> repository.release(claim.id(), claim.token()));
                }
            }
        });
    }

    private Claim claim(String userId, String endpointScope, String idempotencyKey, String requestHash) {
        Instant now = clock.instant();
        Instant leaseExpiresAt = now.plusSeconds(EXECUTION_LEASE_SECONDS);
        UUID claimToken = UUID.randomUUID();
        if (repository.reclaimExpiredInProgress(
                userId, endpointScope, idempotencyKey, requestHash,
                claimToken, now, leaseExpiresAt) == 1) {
            IdempotencyRecord reclaimed = repository.findByUserIdAndEndpointScopeAndIdempotencyKey(
                            userId, endpointScope, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("멱등성 재선점 상태를 확인할 수 없습니다."));
            return new Claim(reclaimed.getId(), claimToken, null, true);
        }
        repository.deleteExpiredCompleted(userId, endpointScope, idempotencyKey, now);
        UUID claimId = UUID.randomUUID();
        if (repository.claim(
                claimId, claimToken, userId, endpointScope, idempotencyKey, requestHash,
                now, leaseExpiresAt) == 1) {
            return new Claim(claimId, claimToken, null, true);
        }

        IdempotencyRecord existing = repository.findByUserIdAndEndpointScopeAndIdempotencyKey(
                        userId, endpointScope, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("멱등성 선점 상태를 확인할 수 없습니다."));
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException("같은 Idempotency-Key를 다른 요청에 사용할 수 없습니다.");
        }
        if ("IN_PROGRESS".equals(existing.getStatus())) {
            throw new IdempotencyInProgressException("같은 Idempotency-Key 요청이 처리 중입니다.");
        }
        return new Claim(existing.getId(), null, existing.getResponseBody(), false);
    }

    private String serialize(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("멱등성 응답을 저장할 수 없습니다.", e);
        }
    }

    private <T> T deserialize(String responseBody, Class<T> responseType) {
        try {
            return objectMapper.readValue(responseBody, responseType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("멱등성 응답을 복원할 수 없습니다.", e);
        }
    }

    private <T> T deserializeCompleted(String responseBody, Class<T> responseType) {
        if (responseBody == null) {
            throw new IllegalStateException("완료된 멱등성 기록에 응답이 없습니다.");
        }
        return deserialize(responseBody, responseType);
    }

    private record Claim(UUID id, UUID token, String replay, boolean acquired) {}
    private record ActiveClaim(Claim claim, ActiveClaim previous) {}
}
