package fruition.core.wiki.controller;

import fruition.core.CoreExceptionHandler;
import fruition.core.config.SecurityConfig;
import fruition.core.wiki.dto.WikiGraphNode;
import fruition.core.wiki.dto.WikiGraphResponse;
import fruition.core.wiki.service.WikiService;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WikiController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class WikiControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";
    private static final String GRAPH = "/api/workspaces/" + WORKSPACE_ID + "/wiki/graph";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean WikiService wikiService;

    @Test
    void graph_returns304WhenGraphIsUnchanged() throws Exception {
        when(wikiService.findGraph(WORKSPACE_ID, USER_ID)).thenReturn(graph("제목"));
        String etag = mockMvc.perform(get(GRAPH).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[0].title").value("제목"))
                .andReturn().getResponse().getHeader("ETag");

        mockMvc.perform(get(GRAPH).header("Authorization", bearer()).header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""));
    }

    @Test
    void graph_returnsBodyWithNewEtagWhenGraphChanged() throws Exception {
        when(wikiService.findGraph(WORKSPACE_ID, USER_ID)).thenReturn(graph("제목"));
        String etag = mockMvc.perform(get(GRAPH).header("Authorization", bearer()))
                .andReturn().getResponse().getHeader("ETag");
        when(wikiService.findGraph(WORKSPACE_ID, USER_ID)).thenReturn(graph("바뀐 제목"));

        mockMvc.perform(get(GRAPH).header("Authorization", bearer()).header("If-None-Match", etag))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.nodes[0].title").value("바뀐 제목"));
    }

    private static WikiGraphResponse graph(String title) {
        return new WikiGraphResponse(List.of(
                new WikiGraphNode("wp_1", "concept", title, "title", "요약", "active", null)), List.of());
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }
}
