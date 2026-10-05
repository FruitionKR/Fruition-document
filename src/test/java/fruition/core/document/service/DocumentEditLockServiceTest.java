package fruition.core.document.service;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentEditLock;
import fruition.core.document.dto.EditLockResponse;
import fruition.core.document.exception.DocumentLockedException;
import fruition.core.document.exception.EditLockLostException;
import fruition.core.document.repository.DocumentEditLockRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.authz.AccessUserClient;
import fruition.core.authz.WorkspaceAccessGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentEditLockServiceTest {

    private static final String WS = "ws_1";
    private static final String USER = "user_1";
    private static final String DOC = "doc_1";
    private static final Instant NOW = Instant.parse("2026-08-13T04:25:00Z");

    @Mock DocumentEditLockRepository lockRepository;
    @Mock DocumentRepository documentRepository;
    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock AccessUserClient accessUserClient;

    private DocumentEditLockService service() {
        return new DocumentEditLockService(lockRepository, documentRepository,
                workspaceAccessGuard, accessUserClient, 45, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void stubOwnedEditable() {
        doNothing().when(workspaceAccessGuard).requireMember(WS, USER);
        Document doc = new Document(DOC, WS, USER, "n.md", "text/markdown", 10L,
                "sources/documents/doc_1/original", "h"); // EDITABLE, owner=USER
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(DOC, WS)).thenReturn(Optional.of(doc));
    }

    private DocumentEditLock lock(String holder, boolean expired) {
        return lock(holder, expired, NOW.plusSeconds(45));
    }

    private DocumentEditLock lock(String holder, boolean expired, Instant expiresAt) {
        DocumentEditLock l = mock(DocumentEditLock.class);
        lenient().when(l.getHolderUserId()).thenReturn(holder);
        lenient().when(l.getExpiresAt()).thenReturn(expiresAt);
        lenient().when(l.isExpiredAt(any())).thenReturn(expired);
        lenient().when(l.isHeldBy(anyString())).thenAnswer(inv -> holder.equals(inv.getArgument(0)));
        return l;
    }

    @Test
    void acquire_whenFree_returnsSelfHeldLock() {
        stubOwnedEditable();
        DocumentEditLock l = lock(USER, false);
        when(lockRepository.acquire(eq(DOC), eq(USER), any(), any())).thenReturn(1);
        when(lockRepository.findById(DOC)).thenReturn(Optional.of(l));

        EditLockResponse res = service().acquire(WS, USER, DOC);

        assertThat(res.holderUserId()).isEqualTo(USER);
    }

    @Test
    void acquire_whenHeldByOther_returnsOtherHeldLock() {
        stubOwnedEditable();
        DocumentEditLock l = lock("other", false);
        when(lockRepository.acquire(eq(DOC), eq(USER), any(), any())).thenReturn(0);
        when(lockRepository.findById(DOC)).thenReturn(Optional.of(l));

        EditLockResponse res = service().acquire(WS, USER, DOC);

        assertThat(res.holderUserId()).isEqualTo("other");
    }

    @Test
    void heartbeat_whenLost_throws409() {
        stubOwnedEditable();
        when(lockRepository.heartbeat(eq(DOC), eq(USER), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service().heartbeat(WS, USER, DOC))
                .isInstanceOf(EditLockLostException.class);
    }

    @Test
    void requireWritable_whenHeldByOther_throwsLocked() {
        DocumentEditLock l = lock("other", false);
        when(lockRepository.findById(DOC)).thenReturn(Optional.of(l));

        assertThatThrownBy(() -> service().requireWritable(DOC, USER))
                .isInstanceOf(DocumentLockedException.class);
    }

    @Test
    void requireWritable_whenFreeOrSelf_passes() {
        when(lockRepository.findById(DOC)).thenReturn(Optional.empty());
        assertThatCode(() -> service().requireWritable(DOC, USER)).doesNotThrowAnyException();

        DocumentEditLock self = lock(USER, false);
        when(lockRepository.findById(DOC)).thenReturn(Optional.of(self));
        assertThatCode(() -> service().requireWritable(DOC, USER)).doesNotThrowAnyException();
    }

    @Test
    void getStatus_whenExpired_returnsNull() {
        DocumentEditLock l = lock("other", true);
        when(lockRepository.findById(DOC)).thenReturn(Optional.of(l));
        assertThat(service().getStatus(DOC)).isNull();
    }

    @Test
    void acquire_ttlMsIsRemainingTimeByServerClock() {
        stubOwnedEditable();
        DocumentEditLock l = lock(USER, false, NOW.plusMillis(44_500));
        when(lockRepository.acquire(eq(DOC), eq(USER), any(), any())).thenReturn(1);
        when(lockRepository.findById(DOC)).thenReturn(Optional.of(l));

        EditLockResponse res = service().acquire(WS, USER, DOC);

        assertThat(res.expiresAt()).isEqualTo(NOW.plusMillis(44_500));
        assertThat(res.ttlMs()).isEqualTo(44_500L);
    }

    @Test
    void heartbeat_ttlMsIsRemainingTimeByServerClock() {
        stubOwnedEditable();
        DocumentEditLock l = lock(USER, false, NOW.plusSeconds(45));
        when(lockRepository.heartbeat(eq(DOC), eq(USER), any(), any())).thenReturn(1);
        when(lockRepository.findById(DOC)).thenReturn(Optional.of(l));

        assertThat(service().heartbeat(WS, USER, DOC).ttlMs()).isEqualTo(45_000L);
    }

    @Test
    void getStatus_ttlMsIsRemainingTimeByServerClock() {
        DocumentEditLock l = lock("other", false, NOW.plusSeconds(10));
        when(lockRepository.findById(DOC)).thenReturn(Optional.of(l));

        assertThat(service().getStatus(DOC).ttlMs()).isEqualTo(10_000L);
    }

    @Test
    void ttlMs_whenAlreadyPastExpiry_isZero() {
        stubOwnedEditable();
        DocumentEditLock l = lock("other", false, NOW.minusSeconds(1));
        when(lockRepository.acquire(eq(DOC), eq(USER), any(), any())).thenReturn(0);
        when(lockRepository.findById(DOC)).thenReturn(Optional.of(l));

        assertThat(service().acquire(WS, USER, DOC).ttlMs()).isZero();
    }
}
