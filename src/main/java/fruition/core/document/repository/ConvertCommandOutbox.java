package fruition.core.document.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * PDF 변환 batch command를 현재 DB 트랜잭션의 outbox에 저장한다(ai ADR 0034).
 * 변환 run({@code convert:<placeholderId>})이 이미 크레딧을 예약했으므로 batch마다 run 등록·예약을 만들지 않는다.
 */
@Component
public class ConvertCommandOutbox {

    private final AiCommandOutboxWriter writer;
    private final ObjectMapper objectMapper;
    private final String commandTopic;

    public ConvertCommandOutbox(AiCommandOutboxWriter writer, ObjectMapper objectMapper,
                                @Value("${app.convert.command-topic}") String commandTopic) {
        this.writer = writer;
        this.objectMapper = objectMapper;
        this.commandTopic = commandTopic;
    }

    public static String batchRunId(String placeholderId, int startPage) {
        return "convert:" + placeholderId + ":" + startPage;
    }

    /** @return 발행한 command JSON. 취소 요청에 같은 내용을 실어야 해서 호출자가 저장한다. */
    public String enqueue(String placeholderId, String workspaceId, String userId, String sourceKey,
                          int startPage, int pageCount, String provider, String model) {
        String runId = batchRunId(placeholderId, startPage);
        try {
            String payload = objectMapper.writeValueAsString(new ConvertCommand(
                    runId, "convert:" + placeholderId, "convert_batch", workspaceId, userId, placeholderId,
                    sourceKey, startPage, pageCount, provider, model));
            // Kafka key는 batch run_id다. document_id를 쓰면 한 문서의 batch가 한 파티션에 몰려 병렬 처리가 막힌다.
            writer.enqueueJoined(runId, commandTopic, runId, payload);
            return payload;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("PDF 변환 command 직렬화 실패: runId=" + runId, e);
        }
    }

    record ConvertCommand(
            @JsonProperty("run_id") String runId,
            @JsonProperty("parent_run_id") String parentRunId,
            String kind,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("source_key") String sourceKey,
            @JsonProperty("start_page") int startPage,
            @JsonProperty("page_count") int pageCount,
            String provider,
            String model
    ) {}
}
