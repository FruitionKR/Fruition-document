package fruition.core.agent.controller;

import fruition.core.CoreExceptionHandler;
import fruition.core.agent.dto.AgentAutoApprovalSettings;
import fruition.core.agent.service.AgentAutoApprovalService;
import fruition.core.config.SecurityConfig;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentAutoApprovalController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class AgentAutoApprovalControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String PATH = "/api/workspaces/ws_aaa11111/agent/auto-approval-settings";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean AgentAutoApprovalService autoApprovalService;

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }

    @Test
    void getReturnsDefaultOffSettings() throws Exception {
        when(autoApprovalService.get("ws_aaa11111", USER_ID)).thenReturn(AgentAutoApprovalSettings.DISABLED);

        mockMvc.perform(get(PATH).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content_edit").value(false))
                .andExpect(jsonPath("$.organize").value(false));
    }

    @Test
    void putSavesBothCategories() throws Exception {
        AgentAutoApprovalSettings settings = new AgentAutoApprovalSettings(true, false);
        when(autoApprovalService.update("ws_aaa11111", USER_ID, settings)).thenReturn(settings);

        mockMvc.perform(put(PATH).header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content_edit\":true,\"organize\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content_edit").value(true));
    }

    @Test
    void putRejectsMissingOrUnknownFields() throws Exception {
        for (String body : new String[]{"{\"content_edit\":true}",
                "{\"content_edit\":true,\"organize\":true,\"delete\":true}"}) {
            mockMvc.perform(put(PATH).header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(autoApprovalService, never()).update(any(), any(), any());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }
}
