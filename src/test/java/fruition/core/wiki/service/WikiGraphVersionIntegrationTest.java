package fruition.core.wiki.service;

import fruition.TestcontainersConfiguration;
import fruition.core.wiki.dto.WikiGraphResponse;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiWorkspaceVersionRepository;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** wiki/graph가 워크스페이스 위키 버전으로 ai 호출 전에 304를 판단한다(#68). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WikiGraphVersionIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired WikiWorkspaceVersionRepository wikiVersions;
    @MockBean PipelineWikiStateRequester wikiStateRequester;

    private String userId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_" + suffix;
        workspaceId = "ws_" + suffix;
        redisTemplate.opsForValue().set("authz:role:v2:" + workspaceId + ":" + userId,
                "{\"role\":\"MEMBER\",\"permissions\":[]}");
        when(wikiStateRequester.graph(workspaceId)).thenReturn(new WikiGraphResponse(List.of(), List.of()));
    }

    @Test
    void unchangedVersionReturns304WithoutCallingAi() throws Exception {
        String etag = graph(null).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"wiki-0\""))
                .andReturn().getResponse().getHeader("ETag");

        graph(etag).andExpect(status().isNotModified());
        verify(wikiStateRequester, times(1)).graph(workspaceId);

        wikiVersions.bump(workspaceId);
        graph(etag).andExpect(status().isOk()).andExpect(header().string("ETag", "\"wiki-1\""));
        verify(wikiStateRequester, times(2)).graph(workspaceId);
    }

    @Test
    void nonMemberGets404EvenWithMatchingEtag() throws Exception {
        redisTemplate.opsForValue().set("authz:role:v2:" + workspaceId + ":" + userId,
                "{\"role\":\"NONE\",\"permissions\":[]}");

        graph("\"wiki-0\"").andExpect(status().isNotFound());
        verify(wikiStateRequester, never()).graph(workspaceId);
    }

    private org.springframework.test.web.servlet.ResultActions graph(String ifNoneMatch) throws Exception {
        var request = get("/api/workspaces/" + workspaceId + "/wiki/graph")
                .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com"));
        if (ifNoneMatch != null) request.header("If-None-Match", ifNoneMatch);
        return mockMvc.perform(request);
    }
}
