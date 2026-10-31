package fruition.core.speech;

import fruition.core.document.service.DocumentAssetOrphanRegistry;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
import java.util.UUID;

/**
 * 끝까지 합성된 답변 음성(WAV)의 저장·조회·삭제(#113).
 *
 * <p>객체 키는 {@code assets/{workspace}/speech/{message}/{uuid}.wav}로 저장할 때마다 새로 만든다. 한 쓰기가 실패해
 * 자기 객체를 지워도 같은 메시지의 다른(성공한) 쓰기 객체를 건드리지 않는다. document 서비스 IRSA 역할이 이미
 * 읽기·쓰기·삭제할 수 있는 {@code assets/*} 아래이고, 워크스페이스 파기({@code DataPurgeService})가
 * {@code assets/{workspace}/}를 통째로 지우므로 따로 챙길 것이 없다. 행은 메시지·세션이 지워질 때 FK CASCADE로
 * 사라지므로 객체를 먼저 지운다.
 *
 * <p>저장과 세션 삭제는 메시지 행 잠금으로 줄을 세운다. 저장은 행을 쓰는 트랜잭션에서 메시지 행을 잠그고, 세션 삭제는
 * 세션·메시지 행을 잠근 뒤에 키를 읽는다. 그래서 삭제가 읽기 전에 끝난 저장은 키가 읽혀 지워지고, 삭제가 잠근 뒤의
 * 저장은 메시지가 사라져 FK로 실패해 자기 객체를 지운다. 키를 읽은 뒤 행이 생겨 객체만 남는 틈이 없다.
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
    private final TransactionTemplate ownTransaction;
    private final DocumentAssetOrphanRegistry orphans;

    public SpeechAudioStore(JdbcTemplate jdbc, MinioClient minio, StorageProperties storage,
                            PlatformTransactionManager transactionManager, DocumentAssetOrphanRegistry orphans) {
        this.jdbc = jdbc;
        this.orphans = orphans;
        this.minio = minio;
        this.bucket = storage.getBucket();
        // 저장은 호출부 트랜잭션에 섞이지 않고 스스로 커밋한다. 잠금을 짧게 쥐고, 실패 정리가 호출부 롤백과 엇갈리지 않는다.
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
     * 연결 오류(IOException)면 파일을 다시 열어 1회 재시도한다. 행 기록이 실패하면(메시지가 지워졌으면 FK로 실패한다)
     * 방금 쓴 객체를 지운다. 교체하면 이전 객체를 같은 트랜잭션 안에서 행을 바꾸기 전에 지운다. 그 삭제가 실패하면
     * 롤백돼 이전 행·객체가 그대로 남고, 커밋 뒤 삭제 실패로 행이 가리키지 않는 객체가 생기지 않는다.
     * 방금 쓴 객체를 지우는 것까지 실패하면 행 없이 남으므로 asset orphan 목록에 남겨 정리 작업이 다시 지운다.
     */
    public void save(String workspaceId, String userId, String messageId, Path pcmFile) {
        UUID objectId = UUID.randomUUID();
        String key = "assets/" + workspaceId + "/speech/" + messageId + "/" + objectId + ".wav";
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
            ownTransaction.executeWithoutResult(status -> {
                // 세션 삭제(deleteForSession)와 같은 메시지를 두고 줄을 선다. 같은 메시지의 저장끼리도 줄을 서서
                // 이전 키를 놓치지 않는다. 메시지가 지워졌으면 잠글 행이 없고 아래 INSERT가 FK로 실패한다.
                jdbc.queryForList("SELECT id FROM chat_messages WHERE id = ? FOR NO KEY UPDATE", String.class, messageId);
                removeAll(jdbc.queryForList(
                        "SELECT object_key FROM chat_message_speech WHERE message_id = ?", String.class, messageId));
                jdbc.update("""
                        INSERT INTO chat_message_speech (message_id, workspace_id, user_id, object_key, byte_size)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (message_id) DO UPDATE SET object_key = EXCLUDED.object_key,
                            byte_size = EXCLUDED.byte_size, created_at = now()
                        """, messageId, workspaceId, userId, key, wavBytes);
            });
        } catch (Exception e) {
            log.warn("[답변 음성 저장 실패] messageId={} error={}", messageId, e.toString());
            removeOrTrack(objectId, key);
        }
    }

    private void put(String key, Path pcmFile, long pcmBytes, long wavBytes) throws Exception {
        try (InputStream pcm = Files.newInputStream(pcmFile);
             InputStream wav = new SequenceInputStream(new ByteArrayInputStream(wavHeader(pcmBytes)), pcm)) {
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(wav, wavBytes, -1)
                    .contentType("audio/wav").build());
        }
    }

    /**
     * 세션의 음성 객체를 지운다. 행은 세션 삭제 때 CASCADE로 사라진다. 객체 삭제가 실패하면 예외를 던져 세션을 남긴다.
     *
     * <p>세션 삭제 트랜잭션 안에서만 부른다. 세션 행(새 메시지를 막는다)과 메시지 행(새 음성 행을 막는다)을 잠근 뒤에 키를
     * 읽어, 잠그는 동안 커밋된 저장까지 읽힌다. 잠금은 세션 행이 지워지고 커밋될 때까지 유지된다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteForSession(String sessionId) {
        jdbc.queryForList("SELECT id FROM chat_sessions WHERE id = ? FOR UPDATE", String.class, sessionId);
        jdbc.queryForList("SELECT id FROM chat_messages WHERE session_id = ? FOR UPDATE", String.class, sessionId);
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

    /**
     * 추적에서 빠진 객체를 지운다. 실패하면 이미지 asset과 같은 orphan 목록(document_asset_orphans)에 남겨
     * DocumentAssetCleanupWorker가 다시 지운다. asset_id 자리에는 객체 키의 UUID를 쓴다(조회에 쓰지 않는 기록용 값).
     */
    private void removeOrTrack(UUID objectId, String key) {
        try {
            removeAll(List.of(key));
        } catch (RuntimeException e) {
            log.warn("[답변 음성 객체 정리 실패, orphan 기록] key={}", key, e);
            try {
                orphans.record(objectId, key, e.getMessage());
            } catch (RuntimeException recordFailure) {
                log.error("[답변 음성 orphan 기록 실패] key={}", key, recordFailure);
            }
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
