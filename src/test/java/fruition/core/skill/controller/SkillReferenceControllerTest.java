package fruition.core.skill.controller;

import fruition.core.CoreExceptionHandler;
import fruition.core.config.SecurityConfig;
import fruition.core.skill.service.SkillReferenceService;
import fruition.core.skill.dto.SkillReferenceReadResponse;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;

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
        when(referenceService.read("ws_1", "user_1", "doc_1", null))
                .thenReturn(new SkillReferenceReadResponse("EDITABLE", "# 현재 본문", null, null, "주간 회의록"));

        mockMvc.perform(post(URL)
                        .header("X-Agent-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document_role").value("EDITABLE"))
                .andExpect(jsonPath("$.markdown").value("# 현재 본문"));
    }

    @Test
    void read_returnsEditRevisionAndCanEditForEditableDocument() throws Exception {
        when(referenceService.read("ws_1", "user_1", "doc_1", null))
                .thenReturn(new SkillReferenceReadResponse("EDITABLE", "# 현재 본문", null, null, "주간 회의록", 7L, false));

        mockMvc.perform(post(URL)
                        .header("X-Agent-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edit_revision").value(7))
                .andExpect(jsonPath("$.can_edit").value(false));
    }

    @Test
    void read_omitsEditFieldsForOriginalDocument() throws Exception {
        when(referenceService.read("ws_1", "user_1", "doc_1", null))
                .thenReturn(new SkillReferenceReadResponse("ORIGINAL", null, null, null, "원본.pdf"));

        mockMvc.perform(post(URL)
                        .header("X-Agent-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edit_revision").doesNotExist())
                .andExpect(jsonPath("$.can_edit").doesNotExist());
    }

    @Test
    void read_withoutMaxCharsOmitsTruncationFields() throws Exception {
        when(referenceService.read("ws_1", "user_1", "doc_1", null))
                .thenReturn(new SkillReferenceReadResponse("EDITABLE", "# 현재 본문", null, null, "주간 회의록"));

        mockMvc.perform(post(URL)
                        .header("X-Agent-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").doesNotExist())
                .andExpect(jsonPath("$.total_chars").doesNotExist());
    }

    @Test
    void read_passesMaxCharsAndReturnsTruncationFields() throws Exception {
        when(referenceService.read("ws_1", "user_1", "doc_1", 5))
                .thenReturn(new SkillReferenceReadResponse("EDITABLE", "# 현재 ", true, 7, "주간 회의록"));

        mockMvc.perform(post(URL)
                        .header("X-Agent-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withMaxChars("5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markdown").value("# 현재 "))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.total_chars").value(7))
                .andExpect(jsonPath("$.display_name").value("주간 회의록"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "\"abc\"", "3000000000"})
    void read_rejectsMaxCharsThatIsNotPositiveInteger(String value) throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Agent-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withMaxChars(value)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(referenceService);
    }

    @Test
    void read_withMaxCharsStillRequiresToken() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withMaxChars("5")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(referenceService);
    }

    private static String withMaxChars(String value) {
        return BODY.substring(0, BODY.length() - 1) + ",\"max_chars\":" + value + "}";
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

    // 디코딩 전 URI를 문자열 비교하면 /references/%72ead가 토큰 없이 200을 받았다(#8).
    @ParameterizedTest
    @ValueSource(strings = {
            "/internal/agent/skill-authoring/references/%72ead",
            "/internal/agent/skill-authoring/references/%72%65%61%64",
            "/internal/agent/skill%2Dauthoring/references/read",
            "/%69nternal/agent/skill-authoring/references/read"})
    void read_rejectsPercentEncodedPathWithoutToken(String path) throws Exception {
        mockMvc.perform(post(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(referenceService);
    }

    /** 대조군: 인코딩한 경로도 같은 핸들러로 가므로 위 401은 필터가 막은 결과다. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/internal/agent/skill-authoring/references/%72ead",
            "/internal/agent/skill%2Dauthoring/references/read"})
    void read_returnsMarkdownForPercentEncodedPathWithToken(String path) throws Exception {
        when(referenceService.read("ws_1", "user_1", "doc_1", null))
                .thenReturn(new SkillReferenceReadResponse("EDITABLE", "# 현재 본문", null, null, "주간 회의록"));

        mockMvc.perform(post(URI.create(path))
                        .header("X-Agent-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markdown").value("# 현재 본문"));
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
