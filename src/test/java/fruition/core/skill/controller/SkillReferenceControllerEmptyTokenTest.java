package fruition.core.skill.controller;

import fruition.core.CoreExceptionHandler;
import fruition.core.config.SecurityConfig;
import fruition.core.skill.service.SkillReferenceService;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SkillReferenceController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        SkillReferenceTokenFilter.class})
@TestPropertySource(properties = "app.skill.agent-token=")
class SkillReferenceControllerEmptyTokenTest {

    private static final String URL = "/internal/agent/skill-authoring/references/read";
    private static final String BODY = "{\"workspace_id\":\"ws_1\",\"user_id\":\"user_1\",\"document_id\":\"doc_1\"}";

    @Autowired MockMvc mockMvc;
    @MockBean SkillReferenceService referenceService;

    @Test
    void read_rejectsEmptyTokensBeforeServiceAccess() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Agent-Service-Token", "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CALLBACK_TOKEN"));

        verifyNoInteractions(referenceService);
    }
}
