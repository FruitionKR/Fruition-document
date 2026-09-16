package fruition.core.aihistory.service;

import fruition.core.aihistory.exception.InvalidCallbackPayloadException;
import fruition.core.aihistory.exception.WikiObjectReadException;
import fruition.shared.util.StorageProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 콜백이 알려준 key로 Wiki 본문을 읽는다.
 *
 * <p>bucket은 <b>환경 설정으로 고정</b>하고 콜백에서 받지 않는다. 콜백이 준 경로를 검증 없이 열면
 * 임의 객체를 읽게 된다. prefix가 그 워크스페이스·페이지·작업의 것인지 정확히 대조한 뒤에만 읽는다.
 */
@Component
public class WikiObjectReader {

    private static final Logger log = LoggerFactory.getLogger(WikiObjectReader.class);

    private final MinioClient minioClient;
    private final StorageProperties storageProperties;

    public WikiObjectReader(MinioClient minioClient, StorageProperties storageProperties) {
        this.minioClient = minioClient;
        this.storageProperties = storageProperties;
    }

    /**
     * 기대하는 경로인지 확인하고 본문을 읽는다.
     *
     * @param markdownKey 콜백이 준 object key. {@code wiki/{ws}/pages/{page}/ops/{op}.md}여야 한다
     */
    public String read(String markdownKey, String workspaceId, String pageId, String operationId) {
        String expected = markdownKey(workspaceId, pageId, operationId);
        String actual = normalize(markdownKey);
        if (!expected.equals(actual)) {
            throw new InvalidCallbackPayloadException(
                    "허용되지 않은 본문 경로입니다: pageId=" + pageId);
        }
        try (var stream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(storageProperties.getBucket())
                .object(actual)
                .build())) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 경로 검증은 통과했다. 여기서 실패했으면 저장소 쪽 문제이므로 재전송해도 소용없다.
            log.error("[Wiki 본문 읽기 실패] pageId={} key={} operationId={}",
                    pageId, actual, operationId, e);
            throw new WikiObjectReadException(
                    "본문 객체를 읽지 못했습니다: pageId=" + pageId, e);
        }
    }

    /** 작업별 불변 본문 key. llmPipeline과 Backend가 같은 규칙으로 만든다. */
    public String markdownKey(String workspaceId, String pageId, String operationId) {
        return objectPrefix(workspaceId, pageId, operationId) + ".md";
    }

    /** 재조립에 쓰는 기여 조각 key. */
    public String contributionKey(String workspaceId, String pageId, String operationId) {
        return objectPrefix(workspaceId, pageId, operationId) + ".json";
    }

    /** 콜백이 보낸 기여 key가 등록된 workspace·page·operation의 것인지 확인한다. */
    public String validateContributionKey(
            String key, String workspaceId, String pageId, String operationId) {
        String expected = contributionKey(workspaceId, pageId, operationId);
        String actual = normalize(key);
        if (!expected.equals(actual)) {
            throw new InvalidCallbackPayloadException(
                    "허용되지 않은 기여 경로입니다: pageId=" + pageId);
        }
        return actual;
    }

    private String objectPrefix(String workspaceId, String pageId, String operationId) {
        return "wiki/" + workspaceId + "/pages/" + pageId + "/ops/" + operationId;
    }

    /** 전송 무결성 확인용. 저장 중 잘렸는지 본다. */
    public String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of()
                    .formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("content_hash를 계산하지 못했습니다.", e);
        }
    }

    /** {@code s3://bucket/...}이나 bucket prefix가 붙어 와도 object key만 남긴다. */
    private String normalize(String key) {
        if (key == null) {
            return "";
        }
        String bucketPrefix = "s3://" + storageProperties.getBucket() + "/";
        if (key.startsWith(bucketPrefix)) {
            return key.substring(bucketPrefix.length());
        }
        if (key.startsWith("s3://")) {
            int objectStart = key.indexOf('/', "s3://".length());
            return objectStart >= 0 ? key.substring(objectStart + 1) : key;
        }
        return key;
    }
}
