package fruition.core.usage.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
        long amount = 500_000L; // 500 KRW in milli-KRW; existing grants keep their stored amount.
        jdbc.update("INSERT INTO beta_signup_credit_grants(slot, user_id, email_hash, amount_krw_milli) VALUES (?, ?, ?, ?)",
                slot, userId, emailHash, amount);
        if (!credits.post(userId, "grant", amount, "beta-signup:" + slot, "베타 선착순 50명 · 500원 크레딧"))
            throw new IllegalStateException("Beta credit ledger already exists without a grant record");
        return amount;
    }
}
