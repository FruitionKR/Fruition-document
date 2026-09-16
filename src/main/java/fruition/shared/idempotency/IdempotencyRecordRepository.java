package fruition.shared.idempotency;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByUserIdAndEndpointScopeAndIdempotencyKey(
            String userId,
            String endpointScope,
            String idempotencyKey
    );

    @Modifying
    @Query(value = """
            INSERT INTO idempotency_records (
                id, user_id, endpoint_scope, idempotency_key, request_hash, status, claim_token,
                response_status, resource_id, response_body, created_at, expires_at
            ) VALUES (
                :id, :userId, :endpointScope, :idempotencyKey, :requestHash, 'IN_PROGRESS', :claimToken,
                NULL, NULL, NULL, :now, :expiresAt
            ) ON CONFLICT (user_id, endpoint_scope, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int claim(@Param("id") UUID id,
              @Param("claimToken") UUID claimToken,
              @Param("userId") String userId,
              @Param("endpointScope") String endpointScope,
              @Param("idempotencyKey") String idempotencyKey,
              @Param("requestHash") String requestHash,
              @Param("now") Instant now,
              @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Query(value = """
            UPDATE idempotency_records
            SET request_hash = :requestHash, claim_token = :claimToken,
                response_status = NULL, resource_id = NULL, response_body = NULL,
                created_at = :now, expires_at = :expiresAt
            WHERE user_id = :userId
              AND endpoint_scope = :endpointScope
              AND idempotency_key = :idempotencyKey
              AND status = 'IN_PROGRESS'
              AND request_hash = :requestHash
              AND expires_at <= :now
            """, nativeQuery = true)
    int reclaimExpiredInProgress(@Param("userId") String userId,
                                 @Param("endpointScope") String endpointScope,
                                 @Param("idempotencyKey") String idempotencyKey,
                                 @Param("requestHash") String requestHash,
                                 @Param("claimToken") UUID claimToken,
                                 @Param("now") Instant now,
                                 @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Query("""
            DELETE FROM IdempotencyRecord record
            WHERE record.userId = :userId
              AND record.endpointScope = :endpointScope
              AND record.idempotencyKey = :idempotencyKey
              AND record.status = 'COMPLETED'
              AND record.expiresAt <= :now
            """)
    int deleteExpiredCompleted(@Param("userId") String userId,
                               @Param("endpointScope") String endpointScope,
                               @Param("idempotencyKey") String idempotencyKey,
                               @Param("now") Instant now);

    @Modifying
    @Query(value = """
            UPDATE idempotency_records
            SET status = 'COMPLETED', response_status = :responseStatus,
                resource_id = :resourceId, response_body = CAST(:responseBody AS jsonb),
                claim_token = NULL, expires_at = :expiresAt
            WHERE id = :id AND status = 'IN_PROGRESS' AND claim_token = :claimToken
            """, nativeQuery = true)
    int complete(@Param("id") UUID id,
                 @Param("claimToken") UUID claimToken,
                 @Param("responseStatus") int responseStatus,
                 @Param("resourceId") String resourceId,
                 @Param("responseBody") String responseBody,
                 @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Query(value = """
            DELETE FROM idempotency_records
            WHERE id = :id AND status = 'IN_PROGRESS' AND claim_token = :claimToken
            """, nativeQuery = true)
    int release(@Param("id") UUID id, @Param("claimToken") UUID claimToken);
}
