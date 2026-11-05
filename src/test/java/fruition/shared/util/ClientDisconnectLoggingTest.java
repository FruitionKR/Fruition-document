package fruition.shared.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fruition.core.CoreExceptionHandler;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 클라이언트가 먼저 끊은 요청이 500 오류 로그로 남지 않는지 실제 Tomcat으로 확인한다.
 * 비슷한 메시지의 서버 쪽 입출력 오류는 그대로 500이어야 한다.
 */
@SpringBootTest(classes = ClientDisconnectLoggingTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientDisconnectLoggingTest {

    private static final String DISCONNECTED = "[클라이언트 연결 끊김]";

    @LocalServerPort int port;

    private Logger logger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(BaseExceptionHandler.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
        appender.stop();
    }

    @Test
    void sseSubscriberDisconnectIsLoggedAsDebugNotAsServerError() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write("GET /sse HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertThat(socket.getInputStream().read()).as("이벤트를 받기 시작한 뒤 끊는다").isNotNegative();
            // 정상 종료 대신 RST로 끊어 서버의 다음 쓰기가 바로 실패하게 한다.
            socket.setSoLinger(true, 0);
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(messages()).anyMatch(message -> message.contains(DISCONNECTED)));
        assertThat(appender.list).allMatch(event -> event.getLevel() == Level.DEBUG);
        assertThat(messages()).noneMatch(message -> message.contains("INTERNAL_ERROR"));
    }

    @Test
    void clientAbortOnPlainRequestIsLoggedAsDebug() throws Exception {
        get("/client-abort");

        assertThat(messages()).singleElement().asString().contains(DISCONNECTED);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
    }

    @Test
    void serverSideIoErrorWithSameMessageStaysInternalError() throws Exception {
        HttpResponse<String> response = get("/server-io-error");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("INTERNAL_ERROR");
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("INTERNAL_ERROR");
        });
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private java.util.List<String> messages() {
        return java.util.List.copyOf(appender.list).stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class})
    @Import({CoreExceptionHandler.class, TestController.class})
    static class TestApp {}

    @RestController
    static class TestController {

        /** QueryEventBroker처럼 쓰기 실패를 잡아 completeWithError로 넘긴다. */
        @GetMapping("/sse")
        SseEmitter sse() {
            SseEmitter emitter = new SseEmitter(0L);
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        emitter.send(SseEmitter.event().comment("ping"));
                        Thread.sleep(20);
                    }
                } catch (IOException | IllegalStateException e) {
                    emitter.completeWithError(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            return emitter;
        }

        @GetMapping("/client-abort")
        String clientAbort() throws IOException {
            throw new ClientAbortException("java.io.IOException: Broken pipe");
        }

        @GetMapping("/server-io-error")
        String serverIoError() throws IOException {
            throw new IOException("Broken pipe");
        }
    }
}
