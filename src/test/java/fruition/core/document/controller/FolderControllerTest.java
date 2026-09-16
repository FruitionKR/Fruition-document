package fruition.core.document.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.dto.DocumentLifecycleRequest;
import fruition.core.document.dto.FolderChildrenResponse;
import fruition.core.document.dto.FolderCreateRequest;
import fruition.core.document.dto.FolderLifecycleResponse;
import fruition.core.document.dto.FolderRenameRequest;
import fruition.core.document.dto.FolderResponse;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FolderController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class FolderControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";
    private static final UUID FOLDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean FolderService folderService;

    @Test
    void create_authenticatedReturnsCreated() throws Exception {
        when(folderService.create(eq(WORKSPACE_ID), eq(USER_ID), eq("key-1"), any(FolderCreateRequest.class)))
                .thenReturn(new FolderResponse(FOLDER_ID, null, "자료", 4, 1, Instant.now(), Instant.now()));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/folders")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FolderCreateRequest("자료", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(FOLDER_ID.toString()))
                .andExpect(jsonPath("$.name").value("자료"))
                .andExpect(jsonPath("$.sort_order").value(4))
                .andExpect(jsonPath("$.current_version").value(1));
    }

    @Test
    void create_blankNameReturns400() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/folders")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FolderCreateRequest(" ", null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_unauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/folders")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FolderCreateRequest("자료", null))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rename_authenticatedReturnsOk() throws Exception {
        when(folderService.rename(eq(WORKSPACE_ID), eq(USER_ID), eq(FOLDER_ID), eq("key-2"),
                any(FolderRenameRequest.class)))
                .thenReturn(new FolderResponse(FOLDER_ID, null, "새이름", 4, 2, Instant.now(), Instant.now()));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/workspaces/" + WORKSPACE_ID + "/folders/" + FOLDER_ID)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FolderRenameRequest("새이름", 1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새이름"))
                .andExpect(jsonPath("$.current_version").value(2));
    }

    @Test
    void children_authenticatedReturnsMixedItems() throws Exception {
        when(folderService.children(WORKSPACE_ID, USER_ID, FOLDER_ID))
                .thenReturn(new FolderChildrenResponse(List.of(
                        FolderChildrenResponse.Item.folder(
                                "33333333-3333-3333-3333-333333333333", "하위", 0, 2, false),
                        FolderChildrenResponse.Item.document("doc_1", "메모", 1, 3))));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/folders/" + FOLDER_ID + "/children")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("folder"))
                .andExpect(jsonPath("$.items[1].type").value("document"))
                .andExpect(jsonPath("$.items[1].id").value("doc_1"))
                .andExpect(jsonPath("$.items[0].current_version").value(2))
                .andExpect(jsonPath("$.items[1].current_version").value(3));
    }

    @Test
    void delete_authenticatedReturnsOk() throws Exception {
        when(folderService.delete(eq(WORKSPACE_ID), eq(USER_ID), eq(FOLDER_ID), eq("dk"), eq(3L)))
                .thenReturn(new FolderLifecycleResponse(FOLDER_ID, 4, true, Instant.now(), UUID.randomUUID()));

        mockMvc.perform(delete("/api/workspaces/" + WORKSPACE_ID + "/folders/" + FOLDER_ID)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "dk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DocumentLifecycleRequest(3L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.current_version").value(4));
    }

    @Test
    void restore_authenticatedReturnsOk() throws Exception {
        when(folderService.restore(eq(WORKSPACE_ID), eq(USER_ID), eq(FOLDER_ID), eq("rk"), eq(4L)))
                .thenReturn(new FolderLifecycleResponse(FOLDER_ID, 5, false, null, null));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/folders/" + FOLDER_ID + "/restore")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DocumentLifecycleRequest(4L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.current_version").value(5));
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }
}
