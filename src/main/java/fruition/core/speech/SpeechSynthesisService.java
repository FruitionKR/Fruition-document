package fruition.core.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.exception.ChatSessionNotFoundException;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.service.ChatSessionService;
import fruition.core.usage.service.UsageChargeService;
import io.minio.GetObjectResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 채팅 답변 음성 듣기(#113). 응답은 줄 단위 JSON 이벤트(NDJSON)다.
 *
 * <pre>
 * {"type":"audio","delta":"&lt;base64 PCM16 LE mono 24kHz&gt;"} ... 그리고 정확히 하나의
 * {"type":"done"} 또는 {"type":"error","code":"..."}
 * </pre>
 *
 * <p>저장된 음성이 있으면 ai를 부르지 않고 같은 형식으로 다시 들려준다(과금 없음). 없으면 ai 스트림을 한 줄씩 중계하며
 * 오디오를 모으고, {@code done}을 받았을 때만 WAV로 저장한다. 클라이언트가 끊으면(쓰기 실패) ai 연결을 즉시 닫는다.
 * 모든 사전 검증과 ai 연결은 {@link #prepare}에서 응답을 시작하기 전에 끝내 HTTP 상태로 오류를 돌려준다.
 */
@Service
public class SpeechSynthesisService {
    private static final Logger log = LoggerFactory.getLogger(SpeechSynthesisService.class);
    static final String KIND = "speech_synthesis";
    private static final int REPLAY_CHUNK_BYTES = 32 * 1024;
    // 합성 PCM은 임시 파일에 모은다(메모리에 올리지 않는다). 디스크 보호를 위해 256 MiB(약 1시간 30분 분량)를 넘으면
    // 저장만 포기하고 재생은 계속한다.
    static final long MAX_STORE_BYTES = 256L * 1024 * 1024;

    private final ChatMessageRepository messages;
    private final ChatSessionService sessions;
    private final UsageChargeService usageCharges;
    private final SpeechSynthesisClient client;
    private final SpeechAudioStore store;
    private final ObjectMapper mapper;

    public SpeechSynthesisService(ChatMessageRepository messages, ChatSessionService sessions,
                                  UsageChargeService usageCharges, SpeechSynthesisClient client,
                                  SpeechAudioStore store, ObjectMapper mapper) {
        this.messages = messages;
        this.sessions = sessions;
        this.usageCharges = usageCharges;
        this.client = client;
        this.store = store;
        this.mapper = mapper;
    }

    public StreamingResponseBody prepare(String workspaceId, String userId, String messageId) {
        // 멤버·세션 소유 검증. 남의 메시지와 없는 메시지는 같은 404다.
        String sessionId = messages.findSessionIdById(messageId).orElseThrow(() -> new ChatSessionNotFoundException(messageId));
        sessions.verifyOwnedSession(workspaceId, userId, sessionId);
        ChatMessage message = messages.findById(messageId).orElseThrow(() -> new ChatSessionNotFoundException(messageId));
        if (!"assistant".equals(message.getRole())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "음성으로 들을 수 있는 답변이 아닙니다.");
        }
        if (!"completed".equals(message.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "완료된 답변만 음성으로 들을 수 있습니다.");
        }
        var stored = store.openPcm(messageId);
        if (stored.isPresent()) return replay(stored.get());

        String text = SpeechTextCleaner.clean(message.getContent());
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "읽을 수 있는 글이 없는 답변입니다.");
        }
        String runId = usageCharges.startRun(KIND, workspaceId, userId);  // 잔액 부족이면 여기서 402
        SpeechSynthesisClient.Stream upstream;
        try {
            upstream = client.open(runId, workspaceId, userId, text);
        } catch (RuntimeException e) {
            // 실패해도 공급사 호출이 일어났을 수 있어 사용량 수집은 건다.
            usageCharges.failRun(runId, UsageChargeService.failureCode(e));
            usageCharges.enqueue(runId);
            throw e;
        }
        return out -> relay(upstream, out, runId, workspaceId, userId, messageId);
    }

    private StreamingResponseBody replay(GetObjectResponse object) {
        return out -> {
            try (object) {
                byte[] buffer = new byte[REPLAY_CHUNK_BYTES];
                int read;
                while ((read = object.readNBytes(buffer, 0, buffer.length)) > 0) {
                    emit(out, audioLine(buffer, read));
                }
                emit(out, "{\"type\":\"done\"}");
            } catch (ClientGone e) {
                // 듣다가 멈춘 것이다.
            } catch (IOException e) {
                tryEmit(out, errorLine("internal_error"));  // 저장본을 읽다 실패했다. 공급사 문제가 아니다.
            }
        };
    }

    private void relay(SpeechSynthesisClient.Stream upstream, OutputStream out, String runId, String workspaceId,
                       String userId, String messageId) {
        Path pcmFile = null;
        OutputStream pcm = null;
        long pcmBytes = 0;
        boolean overflow = false;
        boolean completed = false;
        String failure = "provider_unavailable";  // AI error 이벤트나 종결 이벤트 없는 끊김
        try (upstream) {
            pcmFile = Files.createTempFile("speech-", ".pcm");
            pcm = Files.newOutputStream(pcmFile);
            String line;
            while ((line = upstream.lines().readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode event = mapper.readTree(line);
                switch (event.path("type").asText()) {
                    case "audio" -> {
                        if (!overflow) {
                            byte[] chunk = Base64.getDecoder().decode(event.path("delta").asText());
                            if (pcmBytes + chunk.length > MAX_STORE_BYTES) overflow = true;
                            else {
                                pcm.write(chunk);
                                pcmBytes += chunk.length;
                            }
                        }
                        emit(out, line);
                    }
                    case "done" -> {
                        // 저장을 먼저 끝내고 done을 보내, 클라이언트가 done을 본 뒤 바로 다시 요청해도 저장본이 있다.
                        if (!overflow && pcmBytes > 0) {
                            pcm.close();
                            store.save(workspaceId, userId, messageId, pcmFile);
                        }
                        emit(out, line);
                        completed = true;
                        return;
                    }
                    case "error" -> {
                        emit(out, line);
                        return;
                    }
                    default -> {
                        // 모르는 이벤트 종류는 무시하고 이어 읽는다(ai가 이벤트를 추가해도 중계가 깨지지 않는다).
                    }
                }
            }
            emit(out, errorLine("provider_unavailable"));  // 종결 이벤트 없이 끊겼다.
        } catch (ClientGone e) {
            // 클라이언트가 멈췄다. try-with-resources가 ai 연결을 닫아 ai가 공급사 응답을 취소한다.
            failure = null;
        } catch (SocketTimeoutException e) {
            failure = "deadline_exceeded";
            tryEmit(out, errorLine("deadline_exceeded"));
        } catch (IOException | RuntimeException e) {
            log.warn("[답변 음성 중계 실패] runId={} error={}", runId, e.toString());
            failure = "internal_error";
            tryEmit(out, errorLine("internal_error"));
        } finally {
            closeQuietly(pcm);
            deleteQuietly(pcmFile);
            if (completed) {
                usageCharges.endRun(runId);
            } else {
                usageCharges.failRun(runId, failure);
                usageCharges.enqueue(runId);  // 취소·실패한 합성도 생성된 만큼 과금된다.
            }
        }
    }

    private static void closeQuietly(OutputStream stream) {
        try {
            if (stream != null) stream.close();
        } catch (IOException ignored) {
            // 임시 파일이다.
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            if (file != null) Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("[답변 음성 임시 파일 삭제 실패] file={}", file);
        }
    }

    private static String audioLine(byte[] buffer, int length) {
        return "{\"type\":\"audio\",\"delta\":\"" + Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(buffer, length))
                + "\"}";
    }

    private static String errorLine(String code) {
        return "{\"type\":\"error\",\"code\":\"" + code + "\"}";
    }

    /** 한 줄을 쓰고 바로 내보낸다. 쓰기가 실패하면 클라이언트가 끊은 것이다. */
    private static void emit(OutputStream out, String line) {
        try {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new ClientGone(e);
        }
    }

    private static void tryEmit(OutputStream out, String line) {
        try {
            emit(out, line);
        } catch (ClientGone ignored) {
            // 이미 끊겼다.
        }
    }

    private static final class ClientGone extends RuntimeException {
        ClientGone(IOException cause) {
            super(cause);
        }
    }
}
