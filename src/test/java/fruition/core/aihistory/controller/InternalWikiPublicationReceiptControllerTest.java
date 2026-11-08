package fruition.core.aihistory.controller;

import fruition.core.CoreExceptionHandler;
import fruition.core.aihistory.service.WikiPublicationReceiptService;
import fruition.core.config.SecurityConfig;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalWikiPublicationReceiptController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class InternalWikiPublicationReceiptControllerTest {

    private static final String URL = "/internal/wiki/publications/receipt";
    private static final String BODY = """
            {"workspace_id":"ws-1","user_id":"user-1","publication_id":"wiki:op-1",
             "operation_id":"op-1","event_id":"publication:wiki:op-1"}
            """;

    @Autowired MockMvc mockMvc;
    @MockBean WikiPublicationReceiptService receipts;
    @Value("${app.internal.callback-token}") String internalToken;

    @Test
    void tokenIsRequiredBeforeReceiptLookup() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).header("X-Internal-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());

        verify(receipts, never()).find(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void tokenCheckPrecedesRequestShapeValidation() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verify(receipts, never()).find(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void absentAndMismatchedIdentityAreUniformlyNotFound() throws Exception {
        when(receipts.find("ws-1", "user-1", "wiki:op-1", "op-1", "publication:wiki:op-1"))
                .thenReturn(Optional.empty());

        mockMvc.perform(post(URL).header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PUBLICATION_RECEIPT_NOT_FOUND"));
    }

    @Test
    void returnsOnlyBodyFreeCommittedReceiptFields() throws Exception {
        when(receipts.find("ws-1", "user-1", "wiki:op-1", "op-1", "publication:wiki:op-1"))
                .thenReturn(Optional.of(new WikiPublicationReceiptService.PublicationReceipt(
                        "wiki:op-1", "op-1", "publication:wiki:op-1", "ws-1", "user-1",
                        "LINT", "PUBLISHED", "sha256:" + "a".repeat(64),
                        Instant.parse("2026-11-08T00:00:00Z"),
                        "ACCEPTED", null)));

        mockMvc.perform(post(URL).header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event_id").value("publication:wiki:op-1"))
                .andExpect(jsonPath("$.disposition").value("ACCEPTED"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }
}
