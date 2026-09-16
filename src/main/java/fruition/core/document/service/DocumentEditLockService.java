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
    private final long ttlSeconds;

    public DocumentEditLockService(DocumentEditLockRepository lockRepository,
                                   DocumentRepository documentRepository,
                                   WorkspaceAccessGuard workspaceAccessGuard,
                                   AccessUserClient accessUserClient,
                                   @Value("${app.document.edit-lock.ttl-seconds:45}") long ttlSeconds) {
        this.lockRepository = lockRepository;
        this.documentRepository = documentRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.accessUserClient = accessUserClient;
        this.ttlSeconds = ttlSeconds;
    }

    /** 잠금 획득/갱신. 성립하면 본인 보유 잠금을, 다른 사용자가 보유 중이면 그 사용자 잠금을 그대로 반환한다. */
    @Transactional
    public EditLockResponse acquire(String workspaceId, String userId, String documentId) {
        requireEditableOwned(workspaceId, userId, documentId);
        Instant now = Instant.now();
        lockRepository.acquire(documentId, userId, now, now.plus(Duration.ofSeconds(ttlSeconds)));
        return toResponse(currentLock(documentId));
    }

    /** heartbeat 갱신. 보유자 본인의 유효한 잠금만 연장하며, 상실 시 409. */
    @Transactional
    public EditLockResponse heartbeat(String workspaceId, String userId, String documentId) {
        requireEditableOwned(workspaceId, userId, documentId);
        Instant now = Instant.now();
        int renewed = lockRepository.heartbeat(documentId, userId, now, now.plus(Duration.ofSeconds(ttlSeconds)));
        if (renewed == 0) {
            throw new EditLockLostException("편집 잠금이 만료되었거나 다른 사용자에게 넘어갔습니다. 다시 획득해 주세요.");
        }
        return toResponse(currentLock(documentId));
    }

    /** 보유자 본인의 잠금 해제(멱등). */
    @Transactional
    public void release(String workspaceId, String userId, String documentId) {
        requireEditableOwned(workspaceId, userId, documentId);
        lockRepository.release(documentId, userId);
    }

    /** 현재 잠금 상태. 비어 있거나 만료됐으면 null. GET /documents/{id}의 edit_lock 노출용. */
    @Transactional(readOnly = true)
    public EditLockResponse getStatus(String documentId) {
        DocumentEditLock lock = lockRepository.findById(documentId).orElse(null);
        if (lock == null || lock.isExpiredAt(Instant.now())) {
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
        if (lock == null || lock.isExpiredAt(Instant.now()) || lock.isHeldBy(userId)) {
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
        return new EditLockResponse(lock.getHolderUserId(), holderDisplayName(lock.getHolderUserId()), lock.getExpiresAt());
    }

    private String holderDisplayName(String userId) {
        // 표시명은 best-effort: 내부 API 조회에 실패하면 null이 유지된다.
        return accessUserClient.getDisplayName(userId);
    }

    private void requireEditableOwned(String workspaceId, String userId, String documentId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!document.getUserId().equals(userId)) {
            throw new DocumentWriteForbiddenException("문서 소유자만 편집할 수 있습니다.");
        }
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new InvalidMarkdownContentException("편집 가능한 Markdown 문서만 편집 잠금을 사용할 수 있습니다.");
        }
        // 채팅 Wiki page화 문서는 확인 전용이라 편집기에 들어갈 일이 없다.
        if ("chat_export".equals(document.getOrigin())) {
            throw new InvalidMarkdownContentException("채팅 Wiki page화 문서는 편집할 수 없습니다.");
        }
    }
}
