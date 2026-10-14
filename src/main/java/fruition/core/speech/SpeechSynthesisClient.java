package fruition.core.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.usage.service.UsageChargeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * ai-svc 음성 합성(`POST /speech/synthesis`) 스트림 호출. 응답은 줄 단위 JSON 이벤트(NDJSON)다.
 *
 * <p>읽기 제한 시간은 전체가 아니라 청크 사이 간격이다. {@link HttpURLConnection}의 read timeout이 소켓 읽기마다
 * 적용되므로 오래 걸리는 합성도 청크가 계속 오는 동안은 끊기지 않는다. 호출자가 {@link Stream#close()}하면 연결을 닫아
 * ai가 끊김을 감지하고 공급사 응답을 취소한다.
 */
@Component
public class SpeechSynthesisClient {
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    private final ObjectMapper mapper;
    private final String endpoint;
    private final String internalToken;
    private final int readTimeoutMillis;

    public SpeechSynthesisClient(ObjectMapper mapper,
                                 @Value("${app.speech.synthesis-endpoint}") String endpoint,
                                 @Value("${app.internal.callback-token}") String internalToken,
                                 @Value("${app.speech.synthesis-read-timeout-seconds:30}") int readTimeoutSeconds) {
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.internalToken = internalToken;
        this.readTimeoutMillis = readTimeoutSeconds * 1000;
    }

    /** 열린 응답. 줄 읽기는 {@link #lines}, 닫으면 연결을 끊는다. */
    public record Stream(HttpURLConnection connection, BufferedReader lines) implements AutoCloseable {
        @Override
        public void close() {
            connection.disconnect();
        }
    }

    /**
     * 요청을 보내고 응답 헤더까지 받는다. 상태 오류는 스트림이 시작되기 전이라 HTTP 상태로 돌려준다.
     * ai의 입력 오류(400)는 그대로, 그 밖의 실패는 원문을 노출하지 않고 503으로 바꾼다(ai는 스트림 시작 전에 502를 내지 않는다).
     */
    public Stream open(String runId, String workspaceId, String userId, String answer) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/x-ndjson");
            connection.setRequestProperty("X-Internal-Token", internalToken);
            connection.setRequestProperty(UsageChargeService.RUN_ID_HEADER, runId);
            byte[] body = mapper.writeValueAsBytes(Map.of("workspace_id", workspaceId, "user_id", userId,
                    "action", "chat_answer", "answer", answer));
            connection.setFixedLengthStreamingMode(body.length);
            connection.getOutputStream().write(body);
            int status = connection.getResponseCode();
            if (status != 200) {
                connection.disconnect();
                throw translate(status);
            }
            return new Stream(connection,
                    new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            if (connection != null) connection.disconnect();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "음성 합성 서비스에 연결하지 못했습니다.", e);
        }
    }

    private static ResponseStatusException translate(int status) {
        return switch (status) {
            case 400 -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "답변이 너무 길어 음성으로 만들 수 없습니다.");
            case 403 -> new ResponseStatusException(HttpStatus.FORBIDDEN, "워크스페이스 접근 권한이 없습니다.");
            default -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "음성 합성 서비스를 사용할 수 없습니다.");
        };
    }
}
