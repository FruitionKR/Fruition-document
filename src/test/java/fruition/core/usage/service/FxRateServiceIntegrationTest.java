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
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 수출입은행 API는 가짜 서버로 대신한다. fx_rates는 테스트 사이에 공유되므로 행 수는 상대 증가량으로 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FxRateServiceIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper mapper;
    @Autowired PipelineClientFactory factory;

    private HttpServer server;
    private FxRateService fx;
    private final Map<String, String> bodyBySearchDate = new ConcurrentHashMap<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        Thread.sleep(1100); // effective_from이 초 단위라 앞 테스트 행과 같은 초가 되지 않게 한다.
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
        // 다른 테스트가 넣은 미래 행이 '최신 행'을 가리지 않게 치운다(각 테스트가 자기 행을 넣는다).
        jdbc.update("DELETE FROM fx_rates WHERE effective_from > now()");
        fx = new FxRateService(jdbc, manager, mapper, factory, "http://127.0.0.1:" + server.getAddress().getPort() + "/fx", "test-key");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String usd(String rate) {
        return "[{\"result\":1,\"cur_unit\":\"JPY(100)\",\"deal_bas_r\":\"900.1\"},"
                + "{\"result\":1,\"cur_unit\":\"USD\",\"deal_bas_r\":\"" + rate + "\"}]";
    }

    private List<BigDecimal> rates() {
        return jdbc.queryForList("SELECT krw_per_usd FROM fx_rates ORDER BY effective_from", BigDecimal.class);
    }

    @Test
    void insertsFetchTimeRateWithoutBackdatingAndSkipsUnchangedOrEmptyDays() throws Exception {
        // 2061-03-05(금) 게시, 2061-03-06(토)·07(일)은 빈 배열. 일요일에 돌려도 금요일 값을 쓴다.
        bodyBySearchDate.put("20610305", usd("1,401.50"));
        var before = Instant.now().minusSeconds(1);
        fx.update(LocalDate.of(2061, 3, 7));

        // effective_from은 게시일이 아니라 가져온 시각이다(소급하지 않는다).
        var latest = jdbc.queryForMap("SELECT effective_from, krw_per_usd FROM fx_rates ORDER BY effective_from DESC LIMIT 1");
        assertThat(latest.get("krw_per_usd")).isEqualTo(new BigDecimal("1401.5000"));
        assertThat(((Timestamp) latest.get("effective_from")).toInstant()).isBetween(before, Instant.now().plusSeconds(1));

        // 같은 값을 다시 받으면 행을 늘리지 않고, 비영업일 응답(빈 배열)은 그대로 둔다.
        int size = rates().size();
        bodyBySearchDate.put("20610308", usd("1401.50"));
        fx.update(LocalDate.of(2061, 3, 8));
        fx.update(LocalDate.of(2061, 3, 9));
        assertThat(rates()).hasSize(size);

        // 값이 바뀌면 새 행이 붙는다.
        Thread.sleep(1100);
        bodyBySearchDate.put("20610310", usd("1410.00"));
        fx.update(LocalDate.of(2061, 3, 10));
        assertThat(rates()).hasSize(size + 1);
        assertThat(rates().getLast()).isEqualByComparingTo("1410");
    }

    @Test
    void concurrentReplicasInsertOnce() throws Exception {
        bodyBySearchDate.put("20630305", usd("1555.55"));
        int size = rates().size();
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(i -> pool.submit(() -> fx.update(LocalDate.of(2063, 3, 5)))).toList();
            for (var task : tasks) task.get();
        } finally {
            pool.shutdown();
        }
        assertThat(rates()).hasSize(size + 1);
    }

    @Test
    void keepsPreviousRateWhenApiRejectsOrNothingPublished() {
        bodyBySearchDate.put("20620305", "[{\"result\":3,\"cur_unit\":null,\"deal_bas_r\":null}]");
        int size = rates().size();
        // 거절은 예외로 올라가고(refresh가 경고만 남기고 삼킨다) 행은 늘지 않는다. 메시지에 결과 코드가 있다.
        assertThatThrownBy(() -> fx.update(LocalDate.of(2062, 3, 5)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("3");
        fx.update(LocalDate.of(2062, 3, 20)); // 10일 안에 값이 없다.
        assertThat(rates()).hasSize(size);
    }

    @Test
    void blankAuthKeyDoesNothing() {
        var blank = new FxRateService(jdbc, manager, mapper, factory, "http://127.0.0.1:" + server.getAddress().getPort() + "/fx", "");
        blank.refresh();
        assertThat(queries).isEmpty();
    }
}
