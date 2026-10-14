package fruition.core.speech;

import fruition.shared.util.StorageProperties;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 끝까지 합성된 답변 음성(WAV)의 저장·조회·삭제(#113).
 *
 * <p>객체 키는 {@code assets/{workspace}/speech/{message}.wav}다. document 서비스 IRSA 역할이 이미 읽기·쓰기·삭제할 수
 * 있는 {@code assets/*} 아래이고, 워크스페이스 파기({@code DataPurgeService})가 {@code assets/{workspace}/}를 통째로
 * 지우므로 따로 챙길 것이 없다. 행은 메시지·세션이 지워질 때 FK CASCADE로 사라지므로 객체를 먼저 지운다.
 */
@Component
public class SpeechAudioStore {
    private static final Logger log = LoggerFactory.getLogger(SpeechAudioStore.class);
    static final int SAMPLE_RATE = 24_000;
    static final int WAV_HEADER_BYTES = 44;
    static final int RETENTION_DAYS = 30;
    private static final int CLEANUP_BATCH = 100;

    private final JdbcTemplate jdbc;
    private final MinioClient minio;
    private final String bucket;

    public SpeechAudioStore(JdbcTemplate jdbc, MinioClient minio, StorageProperties storage) {
        this.jdbc = jdbc;
        this.minio = minio;
        this.bucket = storage.getBucket();
    }

    /** 저장된 음성을 열고 WAV 헤더를 건너뛴다. 읽는 쪽은 PCM16 LE 모노 24kHz 바이트만 받는다. 없으면 비어 있다. */
    public Optional<GetObjectResponse> openPcm(String messageId) {
        List<String> keys = jdbc.queryForList("SELECT object_key FROM chat_message_speech WHERE message_id = ?",
                String.class, messageId);
        if (keys.isEmpty()) return Optional.empty();
        try {
            GetObjectResponse object = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(keys.getFirst()).build());
            object.readNBytes(WAV_HEADER_BYTES);
            return Optional.of(object);
        } catch (Exception e) {
            // 객체가 사라졌거나 저장소 오류. 캐시 미스로 보고 다시 합성한다(저장 때 덮어쓴다).
            log.warn("[답변 음성 읽기 실패] messageId={} error={}", messageId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 임시 파일의 PCM에 WAV 헤더를 붙여 스트리밍으로 저장하고 행을 남긴다(이미 있으면 교체). 메모리에 올리지 않는다.
     * 연결 오류(IOException)면 파일을 다시 열어 1회 재시도한다. 행 기록이 실패하면 방금 쓴 객체를 지운다.
     */
    public void save(String workspaceId, String userId, String messageId, Path pcmFile) {
        String key = "assets/" + workspaceId + "/speech/" + messageId + ".wav";
        try {
            long pcmBytes = Files.size(pcmFile);
            long wavBytes = WAV_HEADER_BYTES + pcmBytes;
            try {
                put(key, pcmFile, pcmBytes, wavBytes);
            } catch (InterruptedIOException interrupted) {
                throw interrupted;
            } catch (IOException first) {
                log.warn("[답변 음성 PUT 연결 오류, 1회 재시도] key={} cause={}", key, first.toString());
                put(key, pcmFile, pcmBytes, wavBytes);
            }
            jdbc.update("""
                    INSERT INTO chat_message_speech (message_id, workspace_id, user_id, object_key, byte_size)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (message_id) DO UPDATE SET object_key = EXCLUDED.object_key,
                        byte_size = EXCLUDED.byte_size, created_at = now()
                    """, messageId, workspaceId, userId, key, wavBytes);
        } catch (Exception e) {
            log.warn("[답변 음성 저장 실패] messageId={} error={}", messageId, e.toString());
            removeQuietly(key);
        }
    }

    private void put(String key, Path pcmFile, long pcmBytes, long wavBytes) throws Exception {
        try (InputStream pcm = Files.newInputStream(pcmFile);
             InputStream wav = new SequenceInputStream(new ByteArrayInputStream(wavHeader(pcmBytes)), pcm)) {
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(wav, wavBytes, -1)
                    .contentType("audio/wav").build());
        }
    }

    /** 세션의 음성 객체를 지운다. 행은 세션 삭제 때 CASCADE로 사라진다. 객체 삭제가 실패하면 예외를 던져 세션을 남긴다. */
    public void deleteForSession(String sessionId) {
        removeAll(jdbc.queryForList("SELECT s.object_key FROM chat_message_speech s "
                + "JOIN chat_messages m ON m.id = s.message_id WHERE m.session_id = ?", String.class, sessionId));
    }

    /**
     * 30일이 지난 음성을 지운다. 객체를 먼저 지우고 행을 지워, 실패하면 다음 실행이 다시 시도한다. 한 건이 실패해도
     * 나머지는 계속 지운다(message_id 순으로 훑어 실패한 행이 같은 실행에서 반복되지 않는다).
     */
    @Scheduled(initialDelay = 120_000, fixedDelayString = "${app.speech.cleanup-interval-ms:3600000}")
    public void cleanupExpired() {
        try {
            String after = "";
            List<String> ids;
            do {
                ids = jdbc.queryForList("SELECT message_id FROM chat_message_speech WHERE message_id > ? "
                        + "AND created_at < now() - make_interval(days => ?) ORDER BY message_id LIMIT ?",
                        String.class, after, RETENTION_DAYS, CLEANUP_BATCH);
                for (String id : ids) {
                    try {
                        removeAll(jdbc.queryForList("SELECT object_key FROM chat_message_speech "
                                + "WHERE message_id = ? AND created_at < now() - make_interval(days => ?)",
                                String.class, id, RETENTION_DAYS));
                        jdbc.update("DELETE FROM chat_message_speech WHERE message_id = ? "
                                + "AND created_at < now() - make_interval(days => ?)", id, RETENTION_DAYS);
                    } catch (RuntimeException e) {
                        log.warn("[답변 음성 만료 정리 실패] messageId={} error={}", id, e.toString());
                    }
                    after = id;
                }
            } while (ids.size() == CLEANUP_BATCH);
        } catch (RuntimeException e) {
            log.warn("[답변 음성 만료 정리 실패] error={}", e.toString());
        }
    }

    private void removeAll(List<String> keys) {
        for (String key : keys) {
            try {
                minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            } catch (Exception e) {
                throw new IllegalStateException("답변 음성 객체 삭제 실패: " + key, e);
            }
        }
    }

    private void removeQuietly(String key) {
        try {
            removeAll(List.of(key));
        } catch (RuntimeException e) {
            log.warn("[답변 음성 객체 정리 실패] key={}", key, e);
        }
    }

    /** PCM16 LE 모노 24kHz용 44바이트 WAV 헤더. */
    static byte[] wavHeader(long pcmBytes) {
        ByteBuffer header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()).putInt((int) (36 + pcmBytes)).put("WAVE".getBytes())
                .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt((int) pcmBytes);
        return header.array();
    }
}
