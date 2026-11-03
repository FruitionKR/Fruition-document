package fruition.core.document.service;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentEditLock;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.dto.EditLockResponse;
import fruition.core.document.exception.DocumentLockedException;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import fruition.core.document.exception.EditLockLostException;
import fruition.core.document.exception.InvalidMarkdownContentException;
import fruition.core.document.repository.DocumentEditLockRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.authz.AccessUserClient;
import fruition.core.authz.WorkspaceAccessGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 문서 편집 잠금(활성 편집 추적) 서비스. lease(TTL + heartbeat) 기반으로 편집 중인 문서를 표시하고,
 * 다른 사용자가 편집 중이면 쓰기를 차단한다. heartbeat가 끊기면 잠금은 자동 만료된다.
 */
@Service
public class DocumentEditLockService {

    private final DocumentEditLockRepository lockRepository;
    private final DocumentRepository documentRepository;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final AccessUserClient accessUserClient;
    private final DocumentAccessPolicy documentAccessPolicy;
    private final long ttlSeconds;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public DocumentEditLockService(DocumentEditLockRepository lockRepository,
                                   DocumentRepository documentRepository,
                                   WorkspaceAccessGuard workspaceAccessGuard,
                                   AccessUserClient accessUserClient,
                                   DocumentAccessPolicy documentAccessPolicy,
                                   @Value("${app.document.edit-lock.ttl-seconds:45}") long ttlSeconds,
                                   Clock clock,
                                   TransactionTemplate transactionTemplate) {
        this.lockRepository = lockRepository;
        this.documentRepository = documentRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.accessUserClient = accessUserClient;
        this.documentAccessPolicy = documentAccessPolicy;
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 잠금 획득/갱신. 성립하면 본인 보유 잠금을, 다른 사용자가 보유 중이면 그 사용자 잠금을 그대로 반환한다.
     * 잠금 처리는 짧은 트랜잭션으로 끝내고 보유자 표시 이름은 트랜잭션 밖에서 조회한다.
     * DB 연결을 쥔 채 Access를 기다리면 Access 지연이 곧 연결 풀 고갈이 된다.
     */
    public EditLockResponse acquire(String workspaceId, String userId, String documentId) {
        DocumentEditLock lock = transactionTemplate.execute(status -> {
            requireEditable(workspaceId, userId, documentId);
            Instant now = Instant.now(clock);
            lockRepository.acquire(documentId, userId, now, now.plus(Duration.ofSeconds(ttlSeconds)));
            return currentLock(documentId);
        });
        return toResponse(lock);
    }

    /**
     * heartbeat 갱신. 보유자 본인의 유효한 잠금만 연장하며, 상실 시 409.
     * 보유자는 항상 요청자 본인이라 표시 이름은 조회하지 않는다({@code holder_display_name}=null).
     * 수 초마다 오는 요청이 DB 연결을 쥔 채 Access를 부르면 Access 지연이 곧 연결 풀 고갈이 된다.
     */
    @Transactional
    public EditLockResponse heartbeat(String workspaceId, String userId, String documentId) {
        requireEditable(workspaceId, userId, documentId);
        Instant now = Instant.now(clock);
        int renewed = lockRepository.heartbeat(documentId, userId, now, now.plus(Duration.ofSeconds(ttlSeconds)));
        if (renewed == 0) {
            throw new EditLockLostException("편집 잠금이 만료되었거나 다른 사용자에게 넘어갔습니다. 다시 획득해 주세요.");
        }
        return toResponse(currentLock(documentId), null);
    }

    /** 보유자 본인의 잠금 해제(멱등). */
    @Transactional
    public void release(String workspaceId, String userId, String documentId) {
        requireEditable(workspaceId, userId, documentId);
        lockRepository.release(documentId, userId);
    }

    /**
     * 현재 잠금 상태. 비어 있거나 만료됐으면 null. GET /documents/{id}의 edit_lock 노출용.
     * 트랜잭션을 걸지 않아 조회 연결은 findById 직후 반납되고, 표시 이름 조회는 연결 없이 한다.
     */
    public EditLockResponse getStatus(String documentId) {
        DocumentEditLock lock = lockRepository.findById(documentId).orElse(null);
        if (lock == null || lock.isExpiredAt(Instant.now(clock))) {
            return null;
        }
        return toResponse(lock);
    }

    /**
     * 쓰기 가능 여부 검증(enforcement). 다른 사용자가 유효한 잠금을 보유 중이면 423으로 차단한다.
     * 잠금이 없거나 만료됐거나 본인 보유면 통과한다. 저장·AI 편집·복원·재ingest 앞단에서 호출한다.
     */
    @Transactional(readOnly = true)
    public void requireWritable(String documentId, String userId) {
        DocumentEditLock lock = lockRepository.findById(documentId).orElse(null);
        if (lock == null || lock.isExpiredAt(Instant.now(clock)) || lock.isHeldBy(userId)) {
            return;
        }
        String holder = holderDisplayName(lock.getHolderUserId());
        String who = holder != null ? holder + "님이" : "다른 사용자가";
        throw new DocumentLockedException(who + " 편집 중입니다. 편집이 끝난 뒤 다시 시도해 주세요.");
    }

    private DocumentEditLock currentLock(String documentId) {
        return lockRepository.findById(documentId)
                .orElseThrow(() -> new EditLockLostException("편집 잠금 상태를 확인할 수 없습니다."));
    }

    private EditLockResponse toResponse(DocumentEditLock lock) {
        return toResponse(lock, holderDisplayName(lock.getHolderUserId()));
    }

    private EditLockResponse toResponse(DocumentEditLock lock, String holderDisplayName) {
        // 클라이언트 시계와 무관하게 쓸 수 있도록 서버 시계 기준 남은 시간을 함께 내려준다.
        long ttlMs = Math.max(0L, Duration.between(Instant.now(clock), lock.getExpiresAt()).toMillis());
        return new EditLockResponse(lock.getHolderUserId(), holderDisplayName, lock.getExpiresAt(), ttlMs);
    }

    private String holderDisplayName(String userId) {
        // 표시명은 best-effort: 내부 API 조회에 실패하면 null이 유지된다.
        return accessUserClient.getDisplayName(userId);
    }

    /** 사용자가 편집 권한을 가진 Markdown 문서인지 확인한다. 회의록 저장 대상 검증도 같은 규칙을 쓴다. */
    public void requireEditable(String workspaceId, String userId, String documentId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        documentAccessPolicy.requireEdit(document, userId);
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new InvalidMarkdownContentException("편집 가능한 Markdown 문서만 편집 잠금을 사용할 수 있습니다.");
        }
        // 채팅 Wiki page화 문서는 확인 전용이라 편집기에 들어갈 일이 없다.
        if ("chat_export".equals(document.getOrigin())) {
            throw new InvalidMarkdownContentException("채팅 Wiki page화 문서는 편집할 수 없습니다.");
        }
    }
}
