package fruition.core.wiki.service;

import fruition.TestcontainersConfiguration;
import fruition.core.aihistory.service.WikiObjectReader;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.service.MarkdownDiffService;
import fruition.core.wiki.repository.PipelineWikiPageRequester;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import fruition.shared.util.MinioConfig;
import fruition.shared.util.StorageProperties;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 버전 이력이 없는 페이지는 {@code markdown_uri} 객체에서 본문을 채운다. */
class WikiServiceMarkdownObjectIntegrationTest {

    @Test
    void emptyMarkdownWithoutVersionIsReadFromObject() throws Exception {
        try (var container = new MinIOContainer(DockerImageName.parse(TestcontainersConfiguration.MINIO_IMAGE)
                .asCompatibleSubstituteFor("minio/minio"))) {
            container.start();
            var props = new StorageProperties();
            props.setEndpoint(container.getS3URL()); props.setBucket("wiki-markdown-test");
            props.setAccessKey(container.getUserName()); props.setSecretKey(container.getPassword());
            props.setRegion("us-east-1");
            MinioClient storage = new MinioConfig().minioClient(props);
            storage.makeBucket(MakeBucketArgs.builder().bucket(props.getBucket()).build());
            byte[] body = "# 객체 본문".getBytes(StandardCharsets.UTF_8);
            storage.putObject(PutObjectArgs.builder().bucket(props.getBucket())
                    .object("wiki/ws_1/pages/wp_1/ops/op_0.md")
                    .stream(new ByteArrayInputStream(body), body.length, -1).build());

            var stateRequester = mock(PipelineWikiStateRequester.class);
            var versionRepository = mock(WikiPageVersionRepository.class);
            var service = new WikiService(mock(DocumentRepository.class), mock(WorkspaceAccessGuard.class),
                    mock(PipelineWikiPageRequester.class), stateRequester, versionRepository,
                    mock(MarkdownDiffService.class), new WikiObjectReader(storage, props));
            when(stateRequester.page("ws_1", "wp_1")).thenReturn(Optional.of(WikiServiceTest.page(
                    "", "s3://wiki-markdown-test/wiki/ws_1/pages/wp_1/ops/op_0.md")));
            when(versionRepository.findTopByIdPageIdOrderByIdRevisionDesc("wp_1")).thenReturn(Optional.empty());

            assertThat(service.findById("ws_1", "user_1", "wp_1").markdown()).isEqualTo("# 객체 본문");
        }
    }
}
