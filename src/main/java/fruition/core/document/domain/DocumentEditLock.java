package fruition.core.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 문서 편집 잠금(활성 편집 추적). 문서당 1행, lease 기반. */
@Entity
@Table(name = "document_edit_locks")
public class DocumentEditLock {

    @Id
    @Column(name = "document_id")
    private String documentId;

    @Column(name = "holder_user_id", nullable = false)
    private String holderUserId;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    @Column(name = "last_heartbeat_at", nullable = false)
    private Instant lastHeartbeatAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected DocumentEditLock() {}

    public String getDocumentId() { return documentId; }
    public String getHolderUserId() { return holderUserId; }
    public Instant getAcquiredAt() { return acquiredAt; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public Instant getExpiresAt() { return expiresAt; }

    public boolean isExpiredAt(Instant now) { return !expiresAt.isAfter(now); }
    public boolean isHeldBy(String userId) { return holderUserId.equals(userId); }
}
