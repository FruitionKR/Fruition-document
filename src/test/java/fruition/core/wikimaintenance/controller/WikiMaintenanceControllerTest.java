package fruition.core.wikimaintenance.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import fruition.core.config.SecurityConfig;
import fruition.core.CoreExceptionHandler;
import fruition.core.wikimaintenance.dto.WikiLintRequest;
import fruition.core.wikimaintenance.service.WikiMaintenanceService;
import io.swagger.v3.core.converter.ModelConverters;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(WikiMaintenanceController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class WikiMaintenanceControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean WikiMaintenanceService wikiMaintenanceService;

    @Test
    void lint_authenticatedReturnsPipelineJson() throws Exception {
        when(wikiMaintenanceService.lint(eq(WORKSPACE_ID), eq(USER_ID), any()))
                .thenReturn(objectMapper.readTree("{\"run_id\":\"run_1\",\"status\":\"queued\"}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki/maintenance/lint")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WikiLintRequest(false, true))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued"));
    }

    @Test
    void lint_authenticatedWithoutBodyReturnsPipelineJson() throws Exception {
        when(wikiMaintenanceService.lint(eq(WORKSPACE_ID), eq(USER_ID), any()))
                .thenReturn(objectMapper.readTree("{\"run_id\":\"run_1\",\"status\":\"queued\"}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki/maintenance/lint")
                        .header("Authorization", bearer()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued"));
    }

    @Test
    void lint_strictBooleanValuesBindOnlyJsonBooleansAndNull() throws Exception {
        when(wikiMaintenanceService.lint(eq(WORKSPACE_ID), eq(USER_ID), any()))
                .thenReturn(objectMapper.readTree("{\"run_id\":\"run_1\",\"status\":\"queued\"}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki/maintenance/lint")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materialize_promotions\":true,\"dry_run\":false}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki/maintenance/lint")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materialize_promotions\":null,\"dry_run\":null}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki/maintenance/lint")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materialize_promotions\":true}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki/maintenance/lint")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dry_run\":false}"))
                .andExpect(status().isAccepted());

        verify(wikiMaintenanceService).lint(eq(WORKSPACE_ID), eq(USER_ID), eq(new WikiLintRequest(true, false)));
        verify(wikiMaintenanceService).lint(eq(WORKSPACE_ID), eq(USER_ID), eq(new WikiLintRequest(null, null)));
        verify(wikiMaintenanceService).lint(eq(WORKSPACE_ID), eq(USER_ID), eq(new WikiLintRequest(true, null)));
        verify(wikiMaintenanceService).lint(eq(WORKSPACE_ID), eq(USER_ID), eq(new WikiLintRequest(null, false)));
    }

    @Test
    void lint_invalidBooleanScalarsReturn400WithoutInvokingService() throws Exception {
        for (String field : new String[]{"materialize_promotions", "dry_run"}) {
            for (String value : new String[]{"0", "1", "1.5", "\"true\"", "\"invalid\"", "[]", "{}"}) {
                mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki/maintenance/lint")
                                .header("Authorization", bearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"" + field + "\":" + value + "}"))
                        .andExpect(status().isBadRequest());
            }
        }

        verifyNoInteractions(wikiMaintenanceService);
    }

    @Test
    void lint_unauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki/maintenance/lint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WikiLintRequest(false, true))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void swaggerSchema_exposesWikiLintProperties() throws Exception {
        var schema = ModelConverters.getInstance()
                .read(Class.forName(WikiMaintenanceController.class.getName() + "$WikiLintResponseSchema"))
                .get("WikiLintResponse");

        assertThat(schema.getProperties()).containsKeys("run_id", "operation_id", "status");
        assertThat(schema.getRequired()).contains("run_id", "operation_id", "status");
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) schema.getProperties().get("operation_id")).getNullable()).isTrue();
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }
}
