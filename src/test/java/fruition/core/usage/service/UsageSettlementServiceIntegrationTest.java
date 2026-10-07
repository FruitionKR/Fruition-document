package fruition.core.usage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.authz.AccessUserClient;
import fruition.core.authz.WorkspaceAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 단가표는 테스트 사이에 공유된다. 다른 테스트의 단가 변경 시점이 기간을 나누지 않도록 테스트마다 다른 해를 쓴다.
 * 외부 호출 의존성은 @MockBean 대신 직접 넣는다. @MockBean 조합이 새로우면 Spring 컨텍스트와 컨테이너가 하나 더 뜬다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UsageSettlementServiceIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    private final ModelUsageService usage = mock(ModelUsageService.class);
    private final AccessUserClient access = mock(AccessUserClient.class);
    private final WorkspaceAccessGuard guard = mock(WorkspaceAccessGuard.class);

    private UsageSettlementService settlements;
    private String workspace;

    @BeforeEach
    void setUp() throws Exception {
        settlements = new UsageSettlementService(guard, usage, access, jdbc, mapper, manager);
        workspace = "ws-" + UUID.randomUUID();
        when(guard.getRole(workspace, "owner")).thenReturn("OWNER");
        when(usage.fetch(any(), any(), any(), any())).thenReturn(mapper.readTree("{\"models\":[]}"));
    }

    @Test
    void splitsPeriodAtPriceChangeAndIncludesRemovedMember() throws Exception {
        Instant from = Instant.parse("2031-09-01T00:00:00Z");
        Instant change = Instant.parse("2031-09-15T00:00:00Z");
        Instant to = Instant.parse("2031-10-01T00:00:00Z");
        price("openai", "m-2031", "2031-01-01T00:00:00Z", "1", "2", "0", "0");
        price("openai", "m-2031", "2031-09-15T00:00:00Z", "2", "4", "0", "0");
        when(access.memberUserIds(workspace, from, to)).thenReturn(List.of("owner", "left"));
        when(usage.fetch(workspace, "left", from, change)).thenReturn(usageRow("openai", "m-2031", 1_000_000, 0, 0, 0, 0));
        when(usage.fetch(workspace, "left", change, to)).thenReturn(usageRow("openai", "m-2031", 1_000_000, 0, 0, 0, 0));

        var result = settlements.preview(workspace, "owner", from, to);

        // 탈퇴한 사용자도 포함되고, 사용량이 없는 사용자는 빠진다.
        assertThat(result.users()).extracting(UsageSettlementService.UserLine::userId).containsExactly("left");
        assertThat(result.totalUsd()).isEqualByComparingTo("3");
        assertThat(result.users().getFirst().models().getFirst().inputTokens()).isEqualTo(2_000_000);
    }

    @Test
    void cacheTokensUseOwnPricesAndReasoningIsNotChargedTwice() throws Exception {
        Instant from = Instant.parse("2032-01-01T00:00:00Z");
        Instant to = Instant.parse("2032-02-01T00:00:00Z");
        price("claude", "m-2032", "2032-01-01T00:00:00Z", "1", "2", "0.1", "1.25");
        when(access.memberUserIds(workspace, from, to)).thenReturn(List.of("owner"));
        // 입력 1,000 = 일반 500 + 캐시 읽기 400 + 캐시 생성 100. 출력 500에는 reasoning 300이 들어 있다.
        when(usage.fetch(workspace, "owner", from, to)).thenReturn(usageRow("claude", "m-2032", 1000, 400, 100, 500, 300));

        var line = settlements.preview(workspace, "owner", from, to).users().getFirst().models().getFirst();

        assertThat(line.amountUsd()).isEqualByComparingTo("0.001665");
        assertThat(line.reasoningTokens()).isEqualTo(300);
    }

    @Test
    void unpricedModelIsMarkedAndExcludedFromTotal() throws Exception {
        Instant from = Instant.parse("2033-01-01T00:00:00Z");
        Instant to = Instant.parse("2033-02-01T00:00:00Z");
        price("openai", "priced-2033", "2033-01-01T00:00:00Z", "1", "1", "0", "0");
        when(access.memberUserIds(workspace, from, to)).thenReturn(List.of("owner"));
        when(usage.fetch(workspace, "owner", from, to)).thenReturn(mapper.readTree("""
                {"models":[
                  {"provider":"openai","model":"priced-2033","calls":1,"known_input_tokens":1000000,"known_output_tokens":0},
                  {"provider":"openai","model":"unpriced-2033","calls":2,"unknown_usage_calls":1,"unfinished_calls":1,
                   "known_input_tokens":5000000,"known_output_tokens":0}
                ]}"""));

        var result = settlements.preview(workspace, "owner", from, to);

        assertThat(result.totalUsd()).isEqualByComparingTo("1");
        assertThat(result.priceMissing()).isTrue();
        var unpriced = result.users().getFirst().models().get(1);
        assertThat(unpriced.priceMissing()).isTrue();
        assertThat(unpriced.amountUsd()).isNull();
        assertThat(unpriced.unknownUsageCalls()).isEqualTo(1);
        assertThat(unpriced.unfinishedCalls()).isEqualTo(1);
    }

    @Test
    void closedSettlementKeepsAmountAfterPriceChangeAndRejectsOverlap() throws Exception {
        Instant from = Instant.parse("2034-01-01T00:00:00Z");
        Instant to = Instant.parse("2034-02-01T00:00:00Z");
        price("openai", "m-2034", "2034-01-01T00:00:00Z", "1", "1", "0", "0");
        when(access.memberUserIds(any(), any(), any())).thenReturn(List.of("owner"));
        when(usage.fetch(workspace, "owner", from, to)).thenReturn(usageRow("openai", "m-2034", 1_000_000, 0, 0, 0, 0));

        var closed = settlements.close(workspace, "owner", from, to);
        jdbc.update("UPDATE ai_model_prices SET input_usd_per_mtok = 9 WHERE model = 'm-2034'");

        assertThat(closed.closedAt()).isNotNull();
        assertThat(settlements.closed(workspace, "owner")).singleElement()
                .satisfies(saved -> assertThat(saved.totalUsd()).isEqualByComparingTo("1"));
        assertThat(settlements.close(workspace, "owner", from, to).totalUsd()).isEqualByComparingTo("1");
        assertThatThrownBy(() -> settlements.close(workspace, "owner",
                Instant.parse("2034-01-15T00:00:00Z"), Instant.parse("2034-02-15T00:00:00Z")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void onlyOwnerCanSettle() {
        when(guard.getRole(workspace, "member")).thenReturn("MEMBER");
        Instant from = Instant.parse("2035-01-01T00:00:00Z");

        assertThatThrownBy(() -> settlements.preview(workspace, "member", from, from.plusSeconds(60)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        assertThatThrownBy(() -> settlements.preview(workspace, "owner", from, from))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    private void price(String provider, String model, String effectiveFrom, String input, String output,
                       String cacheRead, String cacheWrite) {
        jdbc.update("INSERT INTO ai_model_prices VALUES (?, ?, ?::timestamptz, ?, ?, ?, ?)", provider, model,
                effectiveFrom, new BigDecimal(input), new BigDecimal(output), new BigDecimal(cacheRead),
                new BigDecimal(cacheWrite));
    }

    private JsonNode usageRow(String provider, String model, long input, long cached, long creation, long output,
                              long reasoning) throws Exception {
        return mapper.readTree("""
                {"models":[{"provider":"%s","model":"%s","calls":1,"known_input_tokens":%d,
                  "known_cached_input_tokens":%d,"known_cache_creation_tokens":%d,"known_output_tokens":%d,
                  "known_reasoning_tokens":%d}]}""".formatted(provider, model, input, cached, creation, output, reasoning));
    }
}
