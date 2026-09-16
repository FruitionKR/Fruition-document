package fruition.core.document.controller;

import fruition.core.document.dto.BreadcrumbResponse;
import fruition.core.document.dto.FolderChildrenResponse;
import fruition.core.document.dto.HierarchySearchResponse;
import fruition.core.document.service.FolderService;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import fruition.core.config.SecurityConfig;
import fruition.core.CoreExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NavigationController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class NavigationControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean FolderService folderService;

    @Test
    void root_authenticatedReturnsTopLevelItems() throws Exception {
        when(folderService.children(WORKSPACE_ID, USER_ID, null))
                .thenReturn(new FolderChildrenResponse(List.of(
                        FolderChildrenResponse.Item.folder(
                                "33333333-3333-3333-3333-333333333333", "자료", 0, 2, true),
                        FolderChildrenResponse.Item.document("doc_1", "메모", 1, 3))));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/navigation")
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("folder"))
                .andExpect(jsonPath("$.items[0].current_version").value(2))
                .andExpect(jsonPath("$.items[0].has_children").value(true))
                .andExpect(jsonPath("$.items[1].id").value("doc_1"))
                .andExpect(jsonPath("$.items[1].current_version").value(3));
    }

    @Test
    void root_unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/navigation"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void breadcrumb_authenticatedReturnsPath() throws Exception {
        when(folderService.breadcrumb(WORKSPACE_ID, USER_ID, null, "doc_1"))
                .thenReturn(new BreadcrumbResponse(List.of(
                        BreadcrumbResponse.Node.folder("33333333-3333-3333-3333-333333333333", "자료"),
                        BreadcrumbResponse.Node.document("doc_1", "메모"))));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/navigation/breadcrumb")
                        .param("document_id", "doc_1")
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path[0].type").value("folder"))
                .andExpect(jsonPath("$.path[1].id").value("doc_1"));
    }

    @Test
    void search_authenticatedReturnsMatches() throws Exception {
        when(folderService.search(WORKSPACE_ID, USER_ID, "보고서"))
                .thenReturn(new HierarchySearchResponse(List.of(
                        new HierarchySearchResponse.Match("folder", "33333333-3333-3333-3333-333333333333",
                                "보고서 폴더", List.of()))));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/navigation/search")
                        .param("query", "보고서")
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].type").value("folder"))
                .andExpect(jsonPath("$.results[0].name").value("보고서 폴더"));
    }
}
