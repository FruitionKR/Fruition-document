package fruition.core.wikischema.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import fruition.core.config.SecurityConfig;
import fruition.core.CoreExceptionHandler;
import fruition.core.wikischema.dto.WikiSchemaDraftRequest;
import fruition.core.wikischema.dto.WikiSchemaPreviewRequest;
import fruition.core.wikischema.service.WikiSchemaService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(WikiSchemaController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class WikiSchemaControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean WikiSchemaService wikiSchemaService;

    @Test
    void preview_authenticatedReturnsPipelineJson() throws Exception {
        when(wikiSchemaService.preview(eq(WORKSPACE_ID), eq(USER_ID), any(WikiSchemaPreviewRequest.class)))
                .thenReturn(objectMapper.readTree("{\"preview_markdown\":\"# 미리보기\",\"has_blocked_issues\":false}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki-schema/preview")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WikiSchemaPreviewRequest("# 원문"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview_markdown").value("# 미리보기"))
                .andExpect(jsonPath("$.has_blocked_issues").value(false));
    }

    @Test
    void createDraft_authenticatedReturnsPipelineJson() throws Exception {
        when(wikiSchemaService.createDraft(eq(WORKSPACE_ID), eq(USER_ID), any(WikiSchemaDraftRequest.class)))
                .thenReturn(objectMapper.readTree("{\"wiki_schema\":{\"id\":\"sch_1\"}}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki-schema/drafts")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WikiSchemaDraftRequest("# 원문", "기본"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wiki_schema.id").value("sch_1"));
    }

    @Test
    void activate_authenticatedReturnsPipelineJson() throws Exception {
        when(wikiSchemaService.activate(WORKSPACE_ID, USER_ID, "sch_1"))
                .thenReturn(objectMapper.readTree("{\"id\":\"sch_1\",\"status\":\"active\"}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki-schema/sch_1/activate")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void getActive_authenticatedReturnsPipelineJson() throws Exception {
        when(wikiSchemaService.getActive(WORKSPACE_ID, USER_ID))
                .thenReturn(objectMapper.readTree("{\"id\":\"sch_1\",\"status\":\"active\"}"));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/wiki-schema/active")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sch_1"));
    }

    @Test
    void preview_blankRawMarkdownReturns400() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki-schema/preview")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WikiSchemaPreviewRequest(" "))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void preview_unauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki-schema/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WikiSchemaPreviewRequest("# 원문"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void swaggerSchemas_exposeWikiSchemaProperties() throws Exception {
        var preview = ModelConverters.getInstance().read(schemaClass("WikiSchemaPreviewResponseSchema"))
                .get("WikiSchemaPreviewResponse");
        var response = ModelConverters.getInstance().read(schemaClass("WikiSchemaResponseSchema"))
                .get("WikiSchemaResponse");
        var fragments = ModelConverters.getInstance().read(schemaClass("WikiSchemaFragmentsSchema"))
                .get("WikiSchemaFragmentsResponse");
        var issue = ModelConverters.getInstance().read(schemaClass("WikiSchemaIssueSchema"))
                .get("WikiSchemaIssueResponse");

        assertThat(preview.getProperties()).containsKeys("fragments", "issues", "preview_markdown", "has_blocked_issues");
        assertThat(response.getProperties()).containsKeys("id", "fragments", "issues", "created_at", "activated_at");
        assertThat(fragments.getProperties()).containsKeys("global_markdown", "query_markdown", "ingest_markdown", "edit_markdown", "concept_markdown", "template_markdown");
        assertThat(issue.getProperties()).containsKeys("severity", "category", "text", "reason", "section");
        assertThat(response.getRequired()).contains("id", "fragments", "issues");
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) preview.getProperties().get("fragments")).get$ref())
                .isEqualTo("#/components/schemas/WikiSchemaFragmentsResponse");
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) preview.getProperties().get("issues")).getItems().get$ref())
                .isEqualTo("#/components/schemas/WikiSchemaIssueResponse");
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) response.getProperties().get("fragments")).get$ref())
                .isEqualTo("#/components/schemas/WikiSchemaFragmentsResponse");
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) response.getProperties().get("issues")).getItems().get$ref())
                .isEqualTo("#/components/schemas/WikiSchemaIssueResponse");
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) response.getProperties().get("created_at")).getNullable()).isTrue();
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) issue.getProperties().get("section")).getNullable()).isTrue();
    }

    private Class<?> schemaClass(String name) throws ClassNotFoundException {
        return Class.forName(WikiSchemaController.class.getName() + "$" + name);
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }
}
