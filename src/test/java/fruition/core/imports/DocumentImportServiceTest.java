package fruition.core.imports;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.service.DocumentService;
import fruition.core.imports.DocumentImportDtos.CreateRequest;
import fruition.core.upload.UploadLimitService;
import fruition.core.usage.service.CreditService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DocumentImportServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UploadLimitService limits = mock(UploadLimitService.class);
    private final DocumentImportService service = new DocumentImportService(jdbc, mock(WorkspaceAccessGuard.class),
            limits, mock(DocumentService.class), mock(CreditService.class));

    @Test
    void create_rejectsBeforeTouchingQuotaOrDatabase() {
        assertRejected(() -> service.create("ws", "u", new CreateRequest("confluence", 1, 0L)),
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertRejected(() -> service.create("ws", "u", new CreateRequest("notion", null, 0L)),
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertRejected(() -> service.create("ws", "u", new CreateRequest("notion", 1, -1L)),
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertRejected(() -> service.create("ws", "u", null), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        verifyNoInteractions(limits, jdbc);
    }

    @Test
    void lookup_withMalformedImportId_isNotFound() {
        assertRejected(() -> service.get("ws", "u", "nope"), HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND");
        verifyNoInteractions(jdbc);
    }

    @Test
    void sessionStore_withMalformedHeader_isImportInvalid() {
        ImportSessionStore store = new ImportSessionStore(jdbc);
        assertRejected(() -> store.requireOpen("ws", "u", "not-a-uuid"), HttpStatus.BAD_REQUEST, "IMPORT_INVALID");
        verifyNoInteractions(jdbc);
    }

    private static void assertRejected(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DocumentImportException.class, e -> {
            org.assertj.core.api.Assertions.assertThat(e.getStatus()).isEqualTo(status);
            org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo(code);
        });
    }
}
