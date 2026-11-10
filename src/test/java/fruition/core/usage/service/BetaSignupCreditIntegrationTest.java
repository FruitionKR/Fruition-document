package fruition.core.usage.service;

import fruition.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BetaSignupCreditIntegrationTest {
    @Autowired BetaSignupCreditService service;
    @Autowired CreditService credits;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    static final String HASH = "a".repeat(64);
    @BeforeEach void reset() {
        jdbc.update("DELETE FROM beta_signup_credit_grants");
        jdbc.update("DELETE FROM credit_entries WHERE idempotency_key LIKE 'beta-signup:%'");
        jdbc.update("DELETE FROM credit_accounts WHERE user_id LIKE 'beta-test-%'");
    }
    @Test void concurrentRetriesGrantOnceAndKeepOriginalAmount() throws Exception {
        try (var pool = Executors.newFixedThreadPool(6)) {
            var tasks = IntStream.range(0, 12).<Callable<Long>>mapToObj(i -> () -> service.grant("beta-test-one", HASH, 1)).toList();
            var results = pool.invokeAll(tasks);
            long amount = results.getFirst().get();
            assertThat(amount).isPositive();
            for (var result : results) assertThat(result.get()).isEqualTo(amount);
            assertThat(credits.credits("beta-test-one").balanceKrwMilli()).isEqualTo(amount);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries WHERE idempotency_key='beta-signup:1'", Integer.class)).isEqualTo(1);
            jdbc.update("INSERT INTO fx_rates(effective_from, krw_per_usd) VALUES (now(), 9999)");
            try { assertThat(service.grant("beta-test-one", HASH, 1)).isEqualTo(amount); }
            finally { jdbc.update("DELETE FROM fx_rates WHERE krw_per_usd=9999"); }
        }
    }
    @Test void fiveDollarsUsesMilliKrwAndLedgerFailureRollsBackGrant() {
        var rateTime = jdbc.queryForObject("INSERT INTO fx_rates(effective_from, krw_per_usd) VALUES (now(), 1400) RETURNING effective_from", java.sql.Timestamp.class);
        try {
            assertThat(service.grant("beta-test-one", HASH, 1)).isEqualTo(7_000_000L);
            credits.post("beta-test-other", "grant", 100, "beta-signup:2", "conflicting test ledger");
            assertThatThrownBy(() -> service.grant("beta-test-other", "b".repeat(64), 2)).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM beta_signup_credit_grants WHERE slot=2", Integer.class)).isZero();
            assertThat(credits.credits("beta-test-other").balanceKrwMilli()).isEqualTo(100);
        } finally { jdbc.update("DELETE FROM fx_rates WHERE effective_from=?", rateTime); }
    }

    @Test void slotEmailAndUserCannotBeReused() {
        service.grant("beta-test-one", HASH, 1);
        assertThatThrownBy(() -> service.grant("beta-test-other", "b".repeat(64), 1)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> service.grant("beta-test-other", HASH, 2)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> service.grant("beta-test-one", "b".repeat(64), 2)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> service.grant("beta-test-other", HASH, 51)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void endpointRejectsUnauthenticatedAndInvalidGrants() throws Exception {
        String body = "{\"user_id\":\"beta-test-api\",\"email_hash\":\"" + HASH + "\",\"slot\":1}";
        mvc.perform(post("/internal/credits/beta-signup").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/credits/beta-signup").header("X-Internal-Token", "wrong").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/credits/beta-signup").header("X-Internal-Token", "callback-token-INSECURE-NOT-FOR-PRODUCTION").contentType(MediaType.APPLICATION_JSON).content(body.replace("\"slot\":1", "\"slot\":51"))).andExpect(status().isBadRequest());
    }
}
