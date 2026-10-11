package fruition.core.usage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fruition.TestcontainersConfiguration;
import fruition.shared.http.PipelineClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 수출입은행 API는 가짜 서버로 대신한다. fx_rates는 테스트 사이에 공유되므로 먼 미래 날짜(2060년대)만 쓴다.
 * 이미 더 늦은 행이 있으면 갱신하지 않는 규칙 때문에, 각 테스트는 자기 날짜보다 이른 행만 있는 날짜를 고른다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FxRateServiceIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PipelineClientFactory factory;

    private HttpServer server;
    private FxRateService fx;
    private final Map<String, String> bodyBySearchDate = new ConcurrentHashMap<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fx", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            queries.add(query);
            String date = query.replaceAll(".*searchdate=(\\d+).*", "$1");
            byte[] body = bodyBySearchDate.getOrDefault(date, "[]").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        fx = new FxRateService(jdbc, mapper, factory, "http://127.0.0.1:" + server.getAddress().getPort() + "/fx", "test-key");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String usd(String rate) {
        return "[{\"result\":1,\"cur_unit\":\"JPY(100)\",\"deal_bas_r\":\"900.1\"},"
                + "{\"result\":1,\"cur_unit\":\"USD\",\"deal_bas_r\":\"" + rate + "\"}]";
    }

    private List<BigDecimal> rates(String fromInclusive) {
        return jdbc.queryForList("SELECT krw_per_usd FROM fx_rates WHERE effective_from >= ?::timestamptz "
                + "ORDER BY effective_from", BigDecimal.class, fromInclusive);
    }

    @Test
    void insertsLatestBusinessDayRateWithCommasAndSkipsUnchangedOrEmptyDays() {
        // 2061-03-05(금) 게시, 2061-03-06(토)·07(일)은 빈 배열. 일요일에 돌려도 금요일 값을 쓴다.
        bodyBySearchDate.put("20610305", usd("1,401.50"));
        fx.update(LocalDate.of(2061, 3, 7));

        assertThat(jdbc.queryForObject("SELECT krw_per_usd FROM fx_rates WHERE effective_from = "
                + "'2061-03-04T15:00:00Z'::timestamptz", BigDecimal.class)).isEqualByComparingTo("1401.5");

        // 같은 값을 다음 영업일에 다시 받으면 행을 늘리지 않고, 비영업일 응답(빈 배열)은 그대로 둔다.
        bodyBySearchDate.put("20610308", usd("1401.50"));
        fx.update(LocalDate.of(2061, 3, 8));
        fx.update(LocalDate.of(2061, 3, 9));
        assertThat(rates("2061-03-01T00:00:00Z")).hasSize(1);

        // 값이 바뀌면 새 행이 붙는다. 같은 날 다시 돌려도(다른 Pod) 중복되지 않고 더 부르지도 않는다.
        bodyBySearchDate.put("20610310", usd("1410.00"));
        fx.update(LocalDate.of(2061, 3, 10));
        queries.clear();
        fx.update(LocalDate.of(2061, 3, 10));
        assertThat(rates("2061-03-01T00:00:00Z")).hasSize(2);
        assertThat(queries).isEmpty();
    }

    @Test
    void keepsPreviousRateWhenApiRejectsOrNothingPublished() {
        bodyBySearchDate.put("20620305", "[{\"result\":3,\"cur_unit\":null,\"deal_bas_r\":null}]");
        // 거절은 예외로 올라가고(refresh가 경고만 남기고 삼킨다) 행은 늘지 않는다.
        assertThatThrownBy(() -> fx.update(LocalDate.of(2062, 3, 5))).isInstanceOf(IllegalStateException.class);
        fx.update(LocalDate.of(2062, 3, 20)); // 10일 안에 값이 없다.
        assertThat(rates("2062-01-01T00:00:00Z")).isEmpty();
    }

    @Test
    void blankAuthKeyDoesNothing() {
        var blank = new FxRateService(jdbc, mapper, factory, "http://127.0.0.1:" + server.getAddress().getPort() + "/fx", "");
        blank.refresh();
        assertThat(queries).isEmpty();
    }
}
