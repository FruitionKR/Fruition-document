package fruition.core.usage.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class BetaSignupCreditService {
    private final JdbcTemplate jdbc;
    private final CreditService credits;

    public BetaSignupCreditService(JdbcTemplate jdbc, CreditService credits) {
        this.jdbc = jdbc;
        this.credits = credits;
    }

    @Transactional
    public long grant(String userId, String emailHash, int slot) {
        if (userId == null || userId.isBlank() || emailHash == null || !emailHash.matches("[0-9a-f]{64}") || slot < 1 || slot > 50)
            throw new IllegalArgumentException("Invalid beta credit recipient");
        // 한 캠페인(50명)의 지급·중복 검사를 직렬화한다. 네트워크 호출은 이 잠금 안에서 하지 않는다.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext('beta-signup-credit'))", rs -> { });
        var existing = jdbc.queryForList("SELECT slot, user_id, email_hash, amount_krw_milli FROM beta_signup_credit_grants "
                + "WHERE slot = ? OR user_id = ? OR email_hash = ?", slot, userId, emailHash);
        if (!existing.isEmpty()) {
            var row = existing.getFirst();
            if (existing.size() != 1 || ((Number) row.get("slot")).intValue() != slot
                    || !userId.equals(row.get("user_id")) || !emailHash.equals(row.get("email_hash")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Beta credit recipient conflicts with an existing grant");
            return ((Number) row.get("amount_krw_milli")).longValue();
        }
        var rates = jdbc.queryForList("SELECT krw_per_usd FROM fx_rates WHERE effective_from <= now() "
                + "ORDER BY effective_from DESC LIMIT 1", BigDecimal.class);
        if (rates.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Beta credit exchange rate unavailable");
        // $5 상당을 최초 지급 때 원화로 고정한다. 재전송 때 환율이 달라도 같은 금액을 돌려준다.
        long amount = rates.getFirst().multiply(new BigDecimal("5000")).setScale(0, RoundingMode.HALF_UP).longValueExact();
        jdbc.update("INSERT INTO beta_signup_credit_grants(slot, user_id, email_hash, amount_krw_milli) VALUES (?, ?, ?, ?)",
                slot, userId, emailHash, amount);
        if (!credits.post(userId, "grant", amount, "beta-signup:" + slot, "베타 선착순 50명 · $5 상당 크레딧"))
            throw new IllegalStateException("Beta credit ledger already exists without a grant record");
        return amount;
    }
}
