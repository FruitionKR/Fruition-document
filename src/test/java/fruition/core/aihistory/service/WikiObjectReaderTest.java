package fruition.core.aihistory.service;

import fruition.core.aihistory.exception.InvalidCallbackPayloadException;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class WikiObjectReaderTest {

    private WikiObjectReader reader;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.setBucket("fruition-storage");
        reader = new WikiObjectReader(mock(MinioClient.class), properties);
    }

    @Test
    void acceptsRegisteredContributionKey() {
        String key = reader.validateContributionKey(
                "s3://fruition-storage/wiki/ws_1/pages/C1/ops/op_1.json",
                "ws_1", "C1", "op_1");

        assertThat(key).isEqualTo("wiki/ws_1/pages/C1/ops/op_1.json");
    }

    @Test
    void rejectsForeignContributionKey() {
        assertThatThrownBy(() -> reader.validateContributionKey(
                "wiki/ws_1/pages/C1/ops/op_other.json",
                "ws_1", "C1", "op_1"))
                .isInstanceOf(InvalidCallbackPayloadException.class);
    }
}
