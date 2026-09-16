package fruition.core.document.controller;

import fruition.core.CoreExceptionHandler;
import fruition.core.config.SecurityConfig;
import fruition.core.document.service.DocumentService;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * access(인증 서비스)가 호출하는 내부 초기 노트 API.
 * 사용자 JWT가 아니라 X-Internal-Token으로 지키며, 토큰을 통과하기 전에는 서비스에 닿지 않아야 한다.
 */
@WebMvcTest(InternalDocumentController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class InternalDocumentControllerTest {

    private static final String URL = "/internal/workspaces/ws_aaa11111/initial-note";
    private static final String BODY = "{\"user_id\":\"user_1f9a74af\"}";

    @Autowired MockMvc mockMvc;
    @MockBean DocumentService documentService;

    @Value("${app.internal.callback-token}")
    String internalToken;

    @Test
    @DisplayName("토큰이 없으면 401이고 서비스에 닿지 않는다")
    void createInitialNote_withoutToken_rejects() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_INTERNAL_TOKEN"));

        verify(documentService, never()).createInitialNote(any(), any());
    }

    @Test
    @DisplayName("토큰이 틀리면 401이다")
    void createInitialNote_wrongToken_rejects() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Internal-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verify(documentService, never()).createInitialNote(any(), any());
    }

    @Test
    @DisplayName("올바른 토큰이면 초기 노트를 생성하고 204를 돌려준다")
    void createInitialNote_validToken_createsNote() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNoContent());

        verify(documentService).createInitialNote("ws_aaa11111", "user_1f9a74af");
    }
}
