package fruition.core.usage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.http.PipelineClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 한국수출입은행 환율 OpenAPI(AP01)의 USD 매매기준율(deal_bas_r)을 매일 {@code fx_rates}에 넣는다.
 *
 * <p>평일 11:30(KST)에 돌고, 기동할 때도 한 번 돈다. 수출입은행은 영업일 11시경 이후에 그날 값을 내고
 * 비영업일·게시 전에는 빈 배열을 돌려주므로, 오늘부터 최대 {@value #LOOKBACK_DAYS}일 전까지 거슬러 올라가
 * 값이 있는 가장 최근 날짜를 쓴다.
 *
 * <p>{@code effective_from}은 게시일이 아니라 가져온 시각(초 단위)이다. 게시일 00:00으로 두면 이미 청구된 호출에
 * 소급되어, 같은 시각 호출도 수집 시점에 따라 환율이 달라진다. 직전 환율과 같으면 넣지 않는다.
 * 여러 Pod가 동시에 돌아도 안전하도록 마지막 행 조회와 삽입을 advisory lock 트랜잭션으로 묶는다.
 * 실패하면 이전 환율을 그대로 쓰고 {@code [환율 갱신 실패]} 경고만 남긴다(알람 기준 문구).
 */
@Service
public class FxRateService {

    private static final Logger log = LoggerFactory.getLogger(FxRateService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;
    // 추석·설 연휴(주말 포함)가 길어도 직전 영업일에 닿는 범위.
    static final int LOOKBACK_DAYS = 10;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RestClient client;
    private final String endpoint;
    private final String authKey;
    private final TransactionTemplate transaction;

    public FxRateService(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper, PipelineClientFactory factory,
                         @Value("${app.fx.koreaexim.endpoint}") String endpoint,
                         @Value("${app.fx.koreaexim.auth-key:}") String authKey) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.mapper = mapper;
        // 내부 인증 헤더가 붙는 restClient()는 쓰지 않는다(외부 호출). timeout 설정만 가져온다.
        this.client = RestClient.builder().requestFactory(factory.requestFactory(10)).build();
        this.endpoint = endpoint;
        this.authKey = authKey;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        refresh();
    }

    @Scheduled(cron = "0 30 11 * * MON-FRI", zone = "Asia/Seoul")
    public void refresh() {
        if (authKey.isBlank()) {
            log.warn("[환율 갱신 실패] KOREAEXIM_AUTH_KEY가 없어 환율을 갱신하지 않는다.");
            return;
        }
        try {
            update(LocalDate.now(KST));
        } catch (RuntimeException e) {
            // 인증키가 URL 쿼리에 있어 RestClient 예외 메시지(요청 URL 포함 가능)는 남기지 않고 종류만 남긴다.
            // fetchUsd가 직접 만든 IllegalStateException 메시지(결과 코드 등)에는 URL이 없어 그대로 남긴다.
            log.warn("[환율 갱신 실패] 이전 환율을 유지한다. cause={}", e instanceof IllegalStateException
                    ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /** today부터 거슬러 올라가며 값이 있는 첫 날짜의 환율을 넣는다. */
    void update(LocalDate today) {
        for (int i = 0; i <= LOOKBACK_DAYS; i++) {
            LocalDate date = today.minusDays(i);
            BigDecimal rate = fetchUsd(date);
            if (rate == null) {
                continue;
            }
            transaction.executeWithoutResult(tx -> insertIfChanged(date, rate));
            return;
        }
        log.warn("[환율 갱신 실패] 최근 {}일 안에 게시된 USD 환율이 없다. 이전 환율을 유지한다.", LOOKBACK_DAYS);
    }

    private void insertIfChanged(LocalDate date, BigDecimal rate) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext('fx_rates'))", rs -> { });
        var latest = jdbc.queryForList("SELECT krw_per_usd FROM fx_rates ORDER BY effective_from DESC LIMIT 1", BigDecimal.class);
        if (latest.isEmpty() || rate.compareTo(latest.getFirst()) != 0) {
            // 락을 기다린 뒤의 시각이어야 최신 행이 항상 가장 늦다(now()는 트랜잭션 시작 시각이라 쓰지 않는다).
            jdbc.update("INSERT INTO fx_rates (effective_from, krw_per_usd) "
                    + "VALUES (date_trunc('second', clock_timestamp()), ?) ON CONFLICT DO NOTHING", rate);
            log.info("환율 갱신: date={} krwPerUsd={}", date, rate);
        }
    }

    /** 그 날짜의 USD 매매기준율. 게시 전·비영업일이면 null이다. */
    private BigDecimal fetchUsd(LocalDate date) {
        String body = client.get().uri(endpoint + "?authkey={key}&searchdate={date}&data=AP01", authKey,
                date.format(YYYYMMDD)).retrieve().body(String.class);
        JsonNode rows;
        try {
            rows = mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("환율 응답을 읽지 못했다", e);
        }
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            return null;
        }
        for (JsonNode row : rows) {
            int result = row.path("result").asInt(1);
            if (result != 1) {
                // 2 DATA코드 오류, 3 인증코드 오류, 4 일일제한횟수 마감.
                throw new IllegalStateException("환율 API 결과 코드 " + result);
            }
            if ("USD".equals(row.path("cur_unit").asText())) {
                BigDecimal rate = new BigDecimal(row.path("deal_bas_r").asText().replace(",", ""));
                if (rate.signum() <= 0) {
                    throw new IllegalStateException("환율이 0 이하다");
                }
                return rate;
            }
        }
        return null;
    }
}
