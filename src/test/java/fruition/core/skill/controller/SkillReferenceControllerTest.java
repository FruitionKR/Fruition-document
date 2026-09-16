package fruition.core.skill.controller;

import fruition.core.CoreExceptionHandler;
import fruition.core.config.SecurityConfig;
import fruition.core.skill.service.SkillReferenceService;
import fruition.core.skill.dto.SkillReferenceReadResponse;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SkillReferenceController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        SkillReferenceTokenFilter.class})
@TestPropertySource(properties = "app.skill.agent-token=test-agent-token")
class SkillReferenceControllerTest {

    private static final String URL = "/internal/agent/skill-authoring/references/read";
    private static final String BODY = "{\"workspace_id\":\"ws_1\",\"user_id\":\"user_1\",\"document_id\":\"doc_1\"}";

    @Autowired MockMvc mockMvc;
    @MockBean SkillReferenceService referenceService;

    @Test
    void read_returnsCurrentMarkdownForServiceToken() throws Exception {
        when(referenceService.read("ws_1", "user_1", "doc_1"))
                .thenReturn(new SkillReferenceReadResponse("EDITABLE", "# 현재 본문"));

        mockMvc.perform(post(URL)
                        .header("X-Agent-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document_role").value("EDITABLE"))
                .andExpect(jsonPath("$.markdown").value("# 현재 본문"));
    }

    @Test
    void read_rejectsTokenBeforeServiceAccess() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Agent-Service-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(referenceService);
    }

    @Test
    void read_rejectsInvalidTokenBeforeInvalidBody() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Agent-Service-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CALLBACK_TOKEN"));

        verifyNoInteractions(referenceService);
    }
}
