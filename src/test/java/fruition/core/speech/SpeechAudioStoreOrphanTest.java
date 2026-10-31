package fruition.core.speech;

import fruition.core.document.service.DocumentAssetOrphanRegistry;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 행 기록이 실패한 저장의 보상 삭제까지 실패하면 객체 키를 orphan 목록에 남긴다(#43). */
class SpeechAudioStoreOrphanTest {

    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final MinioClient minio = mock(MinioClient.class);
    final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    final DocumentAssetOrphanRegistry orphans = mock(DocumentAssetOrphanRegistry.class);
    SpeechAudioStore store;
    Path pcm;

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties storage = new StorageProperties();
        storage.setBucket("bucket");
        store = new SpeechAudioStore(jdbc, minio, storage, transactionManager, orphans);
        // PUT은 성공하고 행을 쓰는 트랜잭션을 열지 못한다(DB 장애). 방금 쓴 객체는 어떤 행도 가리키지 않는다.
        when(transactionManager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("db down"));
        pcm = Files.createTempFile("speech-orphan-", ".pcm");
        Files.write(pcm, new byte[]{1, 2});
    }

    @Test
    void failedCompensatingDelete_isRecordedAsOrphanWithTheWrittenKey() throws Exception {
        doThrow(new IOException("storage down")).when(minio).removeObject(any(RemoveObjectArgs.class));

        store.save("ws_1", "user_1", "msg_1", pcm);

        ArgumentCaptor<PutObjectArgs> put = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minio).putObject(put.capture());
        ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(orphans).record(id.capture(), key.capture(), anyString());
        assertThat(key.getValue()).isEqualTo(put.getValue().object())
                .isEqualTo("assets/ws_1/speech/msg_1/" + id.getValue() + ".wav");
    }

    @Test
    void successfulCompensatingDelete_recordsNothing() throws Exception {
        store.save("ws_1", "user_1", "msg_1", pcm);

        verify(minio).removeObject(any(RemoveObjectArgs.class));
        verify(orphans, never()).record(any(), any(), any());
    }
}
