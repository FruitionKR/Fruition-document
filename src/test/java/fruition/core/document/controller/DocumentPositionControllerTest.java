package fruition.core.document.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.dto.DocumentPositionRequest;
import fruition.core.document.dto.DocumentPositionResponse;
import fruition.core.document.service.DocumentPlacementService;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import fruition.core.config.SecurityConfig;
import fruition.core.CoreExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentPositionController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class DocumentPositionControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";
    private static final UUID FOLDER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean DocumentPlacementService documentPlacementService;

    @Test
    void move_authenticatedReturnsOk() throws Exception {
        when(documentPlacementService.move(eq(WORKSPACE_ID), eq(USER_ID), eq("doc_1"), eq("key-1"),
                any(DocumentPositionRequest.class)))
                .thenReturn(new DocumentPositionResponse("doc_1", FOLDER_ID, 6, 2));

        mockMvc.perform(patch("/api/workspaces/" + WORKSPACE_ID + "/documents/doc_1/position")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DocumentPositionRequest(FOLDER_ID, null, 1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("doc_1"))
                .andExpect(jsonPath("$.folder_id").value(FOLDER_ID.toString()))
                .andExpect(jsonPath("$.current_version").value(2));
    }

    @Test
    void move_documentAsParentReturns400() throws Exception {
        // folder_id에 문서 id(비 UUID)를 넣으면 역직렬화 단계에서 400이다.
        mockMvc.perform(patch("/api/workspaces/" + WORKSPACE_ID + "/documents/doc_1/position")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folder_id\":\"doc_parent\",\"base_version\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void move_missingBaseVersionReturns400() throws Exception {
        mockMvc.perform(patch("/api/workspaces/" + WORKSPACE_ID + "/documents/doc_1/position")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folder_id\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void move_unauthenticatedReturns401() throws Exception {
        mockMvc.perform(patch("/api/workspaces/" + WORKSPACE_ID + "/documents/doc_1/position")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DocumentPositionRequest(null, null, 1L))))
                .andExpect(status().isUnauthorized());
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }
}
