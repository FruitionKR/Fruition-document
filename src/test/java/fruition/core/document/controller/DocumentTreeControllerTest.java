package fruition.core.document.controller;

import fruition.core.document.domain.DocumentProcessingState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.dto.DocumentListResponse;
import fruition.core.document.dto.DocumentTreeResponse;
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

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentTreeController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class DocumentTreeControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean FolderService folderService;

    @Test
    void tree_authenticatedReturnsNestedItems() throws Exception {
        when(folderService.tree(WORKSPACE_ID, USER_ID))
                .thenReturn(new DocumentTreeResponse(List.of(
                        DocumentTreeResponse.Item.folder(
                                "33333333-3333-3333-3333-333333333333",
                                "자료",
                                0,
                                2,
                                List.of(DocumentTreeResponse.Item.document(
                                        "doc_1", "메모.md", 0, 3, documentItem()))))));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/document-tree")
                        .header("Authorization",
                                "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("folder"))
                .andExpect(jsonPath("$.items[0].current_version").value(2))
                .andExpect(jsonPath("$.items[0].has_children").value(true))
                .andExpect(jsonPath("$.items[0].children[0].id").value("doc_1"))
                .andExpect(jsonPath("$.items[0].children[0].current_version").value(3))
                // 문서 메타는 목록 조회와 같은 snake_case로 나간다.
                .andExpect(jsonPath("$.items[0].children[0].document.document_role").value("EDITABLE"))
                .andExpect(jsonPath("$.items[0].children[0].document.needs_reingest").value(true))
                .andExpect(jsonPath("$.items[0].children[0].document.display_name").value("메모"))
                .andExpect(jsonPath("$.items[0].children[0].document.processing_started_at")
                        .value("2026-08-17T00:00:30Z"))
                // 폴더에는 키 자체가 없어야 한다. doesNotExist()는 "document": null도 통과시키므로
                // 키의 부재를 직접 본다.
                .andExpect(jsonPath("$.items[0]").value(not(hasKey("document"))));
    }

    /** 목록 조회가 주는 것과 같은 항목. 트리에도 그대로 실린다. */
    private static DocumentListResponse.DocumentItem documentItem() {
        return new DocumentListResponse.DocumentItem(
                "doc_1", "메모.md", "text/markdown", 12L, DocumentStatus.completed,
                "sources/documents/doc_1/original", null,
                Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-17T00:01:00Z"),
                Instant.parse("2026-08-17T00:00:30Z"),
                null, "run_1", DocumentProcessingState.completed, null,
                "pages", "page", "메모", "md", DocumentRole.EDITABLE, true, 3L, null,
                Instant.parse("2026-08-17T00:02:00Z"), true);
    }

    @Test
    void tree_unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/document-tree"))
                .andExpect(status().isUnauthorized());
    }
}
