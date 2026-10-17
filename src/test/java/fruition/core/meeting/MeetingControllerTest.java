package fruition.core.meeting;

import fruition.core.CoreExceptionHandler;
import fruition.core.config.SecurityConfig;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MeetingController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class MeetingControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean MeetingService meetingService;
    @MockBean MeetingRecordingService meetingRecordingService;
    @MockBean MeetingTranscriptionService meetingTranscriptionService;

    @Test
    void list_nonNumericLimitReturns400InsteadOf500() throws Exception {
        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/meetings")
                        .param("limit", "abc")
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("요청 형식이 올바르지 않습니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("limit"))
                .andExpect(jsonPath("$.error.details[0].reason").value("값의 형식이 올바르지 않습니다."));

        verify(meetingService, never()).list(any(), any(), any(), any(), any());
    }
}
