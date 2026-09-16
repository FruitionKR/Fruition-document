package fruition.core.document.controller;

import fruition.core.document.dto.EditLockResponse;
import fruition.core.document.service.DocumentEditLockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentEditLockControllerTest {

    private static final String WS = "ws_1";
    private static final String USER = "user_1";
    private static final String DOC = "doc_1";

    @Mock DocumentEditLockService editLockService;
    @InjectMocks DocumentEditLockController controller;

    @Test
    void acquire_whenHeldBySelf_returns200() {
        when(editLockService.acquire(WS, USER, DOC))
                .thenReturn(new EditLockResponse(USER, "나", Instant.now().plusSeconds(45)));

        ResponseEntity<EditLockResponse> res = controller.acquire(WS, USER, DOC);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().holderUserId()).isEqualTo(USER);
    }

    @Test
    void acquire_whenHeldByOther_returns423() {
        when(editLockService.acquire(WS, USER, DOC))
                .thenReturn(new EditLockResponse("other", "다른사람", Instant.now().plusSeconds(45)));

        ResponseEntity<EditLockResponse> res = controller.acquire(WS, USER, DOC);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(res.getBody().holderUserId()).isEqualTo("other");
    }
}
