package fruition.core.usage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import fruition.core.authz.AccessUserClient;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 사용자 × workspace 단위 AI 사용량 정산(#59). OWNER만 실행한다.
 *
 * <ul>
 *   <li>대상: 기간 중 멤버였던 사용자 전원(탈퇴·제거 포함, access 멤버십 이력).</li>
 *   <li>단가: 실제 응답 모델({@code model}) 기준. 기간 중 단가가 바뀌면 그 시점으로 기간을 나눠
 *       구간마다 AI 사용량을 조회하고 그때 단가를 곱한다.</li>
 *   <li>입력 토큰은 캐시 읽기·생성분을 포함한 합계라(LangChain usage_metadata) 일반 입력은 그 둘을 뺀다.
 *       reasoning 토큰은 출력에 포함돼 따로 과금하지 않는다.</li>
 *   <li>단가가 없는 모델은 0원으로 두지 않고 {@code price_missing}으로 표시하고 합계에서 뺀다.
 *       사용량 미확인·미완료 호출은 건수로만 표시한다.</li>
 *   <li>마감하면 계산 결과를 저장한다. 이후 단가표가 바뀌어도 저장된 금액은 바뀌지 않는다.</li>
 * </ul>
 */
@Service
public class UsageSettlementService {

    private static final BigDecimal TOKENS_PER_PRICE_UNIT = BigDecimal.valueOf(1_000_000);
    // 사용자 수 × 단가 구간 수만큼 AI를 호출한다. 기간을 1년으로 제한해 호출 수를 묶는다.
    private static final Duration MAX_PERIOD = Duration.ofDays(366);

    private final WorkspaceAccessGuard guard;
    private final ModelUsageService usage;
    private final AccessUserClient access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public UsageSettlementService(WorkspaceAccessGuard guard, ModelUsageService usage, AccessUserClient access,
                                  JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.guard = guard;
        this.usage = usage;
        this.access = access;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
    }

    /** 저장하지 않고 계산만 한다. */
    public Settlement preview(String workspaceId, String actorId, Instant from, Instant to) {
        requireOwner(workspaceId, actorId);
        validatePeriod(from, to);
        return compute(workspaceId, from, to, null, null);
    }

    /**
     * 계산 결과를 마감 저장한다. 같은 기간을 다시 마감하면 저장된 결과를 돌려준다.
     * 이미 마감한 기간과 겹치면 같은 사용량을 두 번 청구하게 되므로 409다.
     */
    public Settlement close(String workspaceId, String actorId, Instant from, Instant to) {
        requireOwner(workspaceId, actorId);
        validatePeriod(from, to);
        // AI·access 호출은 트랜잭션 밖에서 한다.
        Settlement computed = compute(workspaceId, from, to, Instant.now(), actorId);
        return transaction.execute(tx -> {
            // 같은 workspace의 마감을 직렬화한다. 행이 아직 없어 행 잠금으로는 겹침 검사를 지킬 수 없다.
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "ai-usage-settlement:" + workspaceId);
            var same = jdbc.queryForList("SELECT result::text FROM ai_usage_settlements "
                    + "WHERE workspace_id = ? AND from_at = ? AND to_at = ?", String.class,
                    workspaceId, Timestamp.from(from), Timestamp.from(to));
            if (!same.isEmpty()) {
                return read(same.getFirst());
            }
            Boolean overlaps = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ai_usage_settlements "
                    + "WHERE workspace_id = ? AND from_at < ? AND to_at > ?)", Boolean.class,
                    workspaceId, Timestamp.from(to), Timestamp.from(from));
            if (Boolean.TRUE.equals(overlaps)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 마감한 정산 기간과 겹칩니다.");
            }
            jdbc.update("INSERT INTO ai_usage_settlements (id, workspace_id, from_at, to_at, result, closed_by, closed_at) "
                            + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?)",
                    "settlement_" + UUID.randomUUID().toString().replace("-", ""), workspaceId,
                    Timestamp.from(from), Timestamp.from(to), write(computed), actorId,
                    Timestamp.from(computed.closedAt()));
            return computed;
        });
    }

    public List<Settlement> closed(String workspaceId, String actorId) {
        requireOwner(workspaceId, actorId);
        return jdbc.queryForList("SELECT result::text FROM ai_usage_settlements WHERE workspace_id = ? "
                + "ORDER BY from_at DESC", String.class, workspaceId).stream().map(this::read).toList();
    }

    private Settlement compute(String workspaceId, Instant from, Instant to, Instant closedAt, String closedBy) {
        List<Price> prices = jdbc.query("SELECT * FROM ai_model_prices WHERE effective_from < ? ORDER BY effective_from",
                (rs, i) -> new Price(rs.getString("provider"), rs.getString("model"),
                        rs.getTimestamp("effective_from").toInstant(), rs.getBigDecimal("input_usd_per_mtok"),
                        rs.getBigDecimal("output_usd_per_mtok"), rs.getBigDecimal("cache_read_usd_per_mtok"),
                        rs.getBigDecimal("cache_write_usd_per_mtok")),
                Timestamp.from(to));
        TreeSet<Instant> cuts = new TreeSet<>(List.of(from, to));
        prices.stream().map(Price::effectiveFrom).filter(at -> at.isAfter(from)).forEach(cuts::add);
        List<Instant> bounds = new ArrayList<>(cuts);

        List<UserLine> users = new ArrayList<>();
        for (String userId : access.memberUserIds(workspaceId, from, to)) {
            Map<String, ModelAcc> models = new LinkedHashMap<>();
            for (int i = 0; i + 1 < bounds.size(); i++) {
                Instant start = bounds.get(i);
                for (JsonNode row : usage.fetch(workspaceId, userId, start, bounds.get(i + 1)).path("models")) {
                    String provider = row.path("provider").asText();
                    String model = row.path("model").asText();
                    models.computeIfAbsent(provider + "\u0000" + model, key -> new ModelAcc(provider, model))
                            .add(row, priceAt(prices, provider, model, start));
                }
            }
            if (!models.isEmpty()) {
                users.add(UserLine.of(userId, models.values().stream().map(ModelAcc::toLine).toList()));
            }
        }
        BigDecimal total = users.stream().map(UserLine::amountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Settlement(workspaceId, from, to, "USD", total,
                users.stream().anyMatch(UserLine::priceMissing), users, closedAt, closedBy);
    }

    /** 구간 시작 시점에 적용 중인 단가. 구간 안에서는 단가가 바뀌지 않도록 기간을 나눴다. */
    private static Price priceAt(List<Price> prices, String provider, String model, Instant at) {
        Price found = null;
        for (Price price : prices) {
            if (price.provider().equals(provider) && price.model().equals(model) && !price.effectiveFrom().isAfter(at)) {
                found = price;
            }
        }
        return found;
    }

    private void requireOwner(String workspaceId, String userId) {
        String role = guard.getRole(workspaceId, userId);
        if ("NONE".equals(role)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
        if (!"OWNER".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "정산은 워크스페이스 OWNER만 할 수 있습니다.");
        }
    }

    private static void validatePeriod(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "정산 시작은 종료보다 앞서야 합니다.");
        }
        if (Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "정산 기간은 366일 이하여야 합니다.");
        }
    }

    private String write(Settlement settlement) {
        try {
            return mapper.writeValueAsString(settlement);
        } catch (Exception e) {
            throw new IllegalStateException("정산 결과를 저장할 수 없습니다.", e);
        }
    }

    private Settlement read(String json) {
        try {
            return mapper.readValue(json, Settlement.class);
        } catch (Exception e) {
            throw new IllegalStateException("저장된 정산 결과를 읽을 수 없습니다.", e);
        }
    }

    private record Price(String provider, String model, Instant effectiveFrom, BigDecimal input, BigDecimal output,
                         BigDecimal cacheRead, BigDecimal cacheWrite) {
    }

    /** 모델 하나의 구간별 사용량을 합친다. 한 구간이라도 단가가 없으면 금액을 내지 않는다. */
    private static final class ModelAcc {
        private final String provider;
        private final String model;
        private long calls, unknownUsageCalls, unfinishedCalls, inputTokens, cachedInputTokens, cacheCreationTokens,
                outputTokens, reasoningTokens;
        private BigDecimal amount = BigDecimal.ZERO;
        private boolean priceMissing;

        ModelAcc(String provider, String model) {
            this.provider = provider;
            this.model = model;
        }

        void add(JsonNode row, Price price) {
            long input = row.path("known_input_tokens").asLong();
            long cached = row.path("known_cached_input_tokens").asLong();
            long creation = row.path("known_cache_creation_tokens").asLong();
            long output = row.path("known_output_tokens").asLong();
            calls += row.path("calls").asLong();
            unknownUsageCalls += row.path("unknown_usage_calls").asLong();
            unfinishedCalls += row.path("unfinished_calls").asLong();
            inputTokens += input;
            cachedInputTokens += cached;
            cacheCreationTokens += creation;
            outputTokens += output;
            reasoningTokens += row.path("known_reasoning_tokens").asLong();
            if (input == 0 && output == 0) {
                return;
            }
            if (price == null) {
                priceMissing = true;
                return;
            }
            long plainInput = Math.max(0, input - cached - creation);
            amount = amount.add(BigDecimal.valueOf(plainInput).multiply(price.input())
                    .add(BigDecimal.valueOf(cached).multiply(price.cacheRead()))
                    .add(BigDecimal.valueOf(creation).multiply(price.cacheWrite()))
                    .add(BigDecimal.valueOf(output).multiply(price.output()))
                    .divide(TOKENS_PER_PRICE_UNIT, 6, RoundingMode.HALF_UP));
        }

        ModelLine toLine() {
            return new ModelLine(provider, model, calls, unknownUsageCalls, unfinishedCalls, inputTokens,
                    cachedInputTokens, cacheCreationTokens, outputTokens, reasoningTokens,
                    priceMissing ? null : amount, priceMissing);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Settlement(String workspaceId, Instant fromAt, Instant toAt, String currency, BigDecimal totalUsd,
                             boolean priceMissing, List<UserLine> users, Instant closedAt, String closedBy) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UserLine(String userId, BigDecimal amountUsd, boolean priceMissing, List<ModelLine> models) {
        static UserLine of(String userId, List<ModelLine> models) {
            return new UserLine(userId,
                    models.stream().map(ModelLine::amountUsd).filter(java.util.Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add),
                    models.stream().anyMatch(ModelLine::priceMissing), models);
        }
    }

    /** amount_usd는 단가가 없으면 null이다. 합계에는 들어가지 않는다. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ModelLine(String provider, String model, long calls, long unknownUsageCalls, long unfinishedCalls,
                            long inputTokens, long cachedInputTokens, long cacheCreationTokens, long outputTokens,
                            long reasoningTokens, BigDecimal amountUsd, boolean priceMissing) {
    }
}
