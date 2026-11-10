package fruition.core.usage.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.usage.service.BetaSignupCreditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
public class InternalBetaCreditController {
    private final BetaSignupCreditService credits;
    private final String token;

    public InternalBetaCreditController(BetaSignupCreditService credits,
                                       @Value("${app.internal.callback-token}") String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Internal token is required");
        this.credits = credits;
        this.token = token;
    }

    @PostMapping("/internal/credits/beta-signup")
    public GrantResponse grant(@RequestHeader(value = "X-Internal-Token", required = false) String provided,
                               @Valid @RequestBody GrantRequest request) {
        if (provided == null || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return new GrantResponse(credits.grant(request.userId(), request.emailHash(), request.slot()));
    }

    public record GrantRequest(@NotBlank @JsonProperty("user_id") String userId,
                               @NotBlank @Pattern(regexp = "[0-9a-f]{64}") @JsonProperty("email_hash") String emailHash,
                               @Min(1) @Max(50) int slot) {}
    public record GrantResponse(@JsonProperty("amount_krw_milli") long amountKrwMilli) {}
}
