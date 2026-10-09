package fruition.core.aihistory.service;

import fruition.core.aihistory.exception.InvalidCallbackPayloadException;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void rejectsPageObjectOutsideThatPage() {
        for (String key : List.of(
                "wiki/ws_other/pages/C1/ops/op_1.md",
                "wiki/ws_1/pages/C2/ops/op_1.md",
                "wiki/ws_1/pages/C1/../C2/ops/op_1.md",
                "s3://other-bucket/wiki/ws_1/pages/C1/ops/op_1.md")) {
            assertThatThrownBy(() -> reader.readPageObject(key, "ws_1", "C1"))
                    .as(key)
                    .isInstanceOf(InvalidCallbackPayloadException.class);
        }
    }
}
