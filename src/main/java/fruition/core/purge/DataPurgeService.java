package fruition.core.purge;

import fruition.shared.util.StorageProperties;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 탈퇴 사용자와 영구 삭제된 워크스페이스의 core_db 행과 object storage 객체를 지운다.
 *
 * <p>객체를 먼저 지우고 행을 나중에 지운다. 객체 키는 행에서 읽으므로, 객체 삭제가 실패하면 행을 그대로 두고
 * 실패를 돌려준다. access가 다시 호출하면 같은 키를 다시 읽어 이어서 지운다. 반대 순서면 행이 사라진 뒤
 * 남은 객체를 찾을 방법이 없다. 모든 삭제가 조건부 DELETE라 같은 요청을 여러 번 보내도 결과가 같다.
 *
 * <p>AI 사용 정산(ai_usage_settlements, ai_model_prices)은 대금 결제 기록 보관 대상이라 지우지 않는다.
 */
@Service
public class DataPurgeService {

    private static final Logger log = LoggerFactory.getLogger(DataPurgeService.class);

    /** 워크스페이스 운영 로그 id. 위키 기여·버전 행에는 workspace_id가 없어 이 로그를 거쳐 찾는다. */
    private static final String WORKSPACE_OPERATIONS =
            "SELECT operation_id FROM ai_operation_logs WHERE workspace_id = ?";
    private static final String WORKSPACE_RUNS = "SELECT id FROM ai_task_runs WHERE workspace_id = ?";
    private static final String USER_RUNS = "SELECT id FROM ai_task_runs WHERE user_id = ?";

    /**
     * FK 순서대로 나열한다. 위키 기여·버전과 AI 작업 변경분은 CASCADE 없이 로그·실행을 참조하므로 먼저 지운다.
     * 문서는 폴더보다 먼저 지운다. 폴더를 먼저 지우면 문서가 루트로 올라오며 이름 고유 제약에 걸릴 수 있다.
     * 문서에 딸린 본문·버전·잠금·asset 참조, 채팅 메시지, 회의 전사는 CASCADE로 함께 지워진다.
     */
    private static final List<Step> WORKSPACE_STEPS = List.of(
            new Step("wiki_page_versions", "DELETE FROM wiki_page_versions WHERE operation_id IN (" + WORKSPACE_OPERATIONS
                    + ") OR page_id IN (SELECT c.page_id FROM wiki_page_contributions c"
                    + " JOIN ai_operation_logs l ON l.operation_id = c.ingest_operation_id WHERE l.workspace_id = ?)", 2),
            new Step("wiki_page_contributions", "DELETE FROM wiki_page_contributions WHERE ingest_operation_id IN ("
                    + WORKSPACE_OPERATIONS + ") OR deactivated_by IN (" + WORKSPACE_OPERATIONS + ")", 2),
            new Step("ai_operation_logs", "DELETE FROM ai_operation_logs WHERE workspace_id = ?", 1),
            new Step("ai_task_result_receipts", "DELETE FROM ai_task_result_receipts WHERE run_id IN (" + WORKSPACE_RUNS + ")", 1),
            new Step("ai_task_changes", "DELETE FROM ai_task_changes WHERE run_id IN (" + WORKSPACE_RUNS + ")", 1),
            new Step("ai_task_runs", "DELETE FROM ai_task_runs WHERE workspace_id = ?", 1),
            new Step("agent_apply_projections", "DELETE FROM agent_apply_projections WHERE workspace_id = ?", 1),
            new Step("chat_sessions", "DELETE FROM chat_sessions WHERE workspace_id = ?", 1),
            new Step("chat_partial_wiki", "DELETE FROM chat_partial_wiki WHERE workspace_id = ?", 1),
            new Step("meetings", "DELETE FROM meetings WHERE workspace_id = ?", 1),
            new Step("document_edit_outbox", "DELETE FROM document_edit_outbox WHERE workspace_id = ?", 1),
            new Step("documents", "DELETE FROM documents WHERE workspace_id = ?", 1),
            new Step("document_assets", "DELETE FROM document_assets WHERE workspace_id = ?", 1),
            new Step("folders", "DELETE FROM folders WHERE workspace_id = ?", 1),
            new Step("wiki_lint_state", "DELETE FROM wiki_lint_state WHERE workspace_id = ?", 1));

    /**
     * 공유 워크스페이스에 남는 사용자 개인 데이터. 멤버가 함께 보는 문서와 AI 작업 로그는 OWNER가 관리하므로 남긴다.
     * AI 실행 기록은 질문 원문과 변경 전후 값을 담고 있어 지운다.
     */
    private static final List<Step> USER_STEPS = List.of(
            new Step("ai_task_result_receipts", "DELETE FROM ai_task_result_receipts WHERE run_id IN (" + USER_RUNS + ")", 1),
            new Step("ai_task_changes", "DELETE FROM ai_task_changes WHERE run_id IN (" + USER_RUNS + ")", 1),
            new Step("ai_task_runs", "DELETE FROM ai_task_runs WHERE user_id = ?", 1),
            new Step("agent_apply_projections", "DELETE FROM agent_apply_projections WHERE user_id = ?", 1),
            new Step("chat_sessions", "DELETE FROM chat_sessions WHERE user_id = ?", 1),
            new Step("meetings", "DELETE FROM meetings WHERE created_by = ?", 1),
            new Step("idempotency_records", "DELETE FROM idempotency_records WHERE user_id = ?", 1));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final MinioClient minio;
    private final String bucket;

    public DataPurgeService(JdbcTemplate jdbc, TransactionTemplate transactionTemplate,
                            MinioClient minio, StorageProperties storage) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.minio = minio;
        this.bucket = storage.getBucket();
    }

    public PurgeResult purgeWorkspace(String workspaceId) {
        List<String> keys = new ArrayList<>();
        keys.addAll(jdbc.queryForList("SELECT source_uri FROM documents WHERE workspace_id = ? AND source_uri IS NOT NULL"
                + " UNION ALL SELECT extracted_text_uri FROM documents WHERE workspace_id = ? AND extracted_text_uri IS NOT NULL",
                String.class, workspaceId, workspaceId));
        keys.addAll(recordingKeys("workspace_id", workspaceId));
        // ponytail: 목록을 읽은 뒤 행을 지우기 전에 새로 올라온 asset 객체는 남는다. 휴지통·탈퇴로 쓰지 않는 워크스페이스라 창이 작다.
        keys.addAll(listKeys("assets/" + workspaceId + "/"));
        return purge("workspace", workspaceId, keys, WORKSPACE_STEPS);
    }

    public PurgeResult purgeUser(String userId) {
        return purge("user", userId, recordingKeys("created_by", userId), USER_STEPS);
    }

    private PurgeResult purge(String scope, String id, List<String> keys, List<Step> steps) {
        removeObjects(keys.stream().map(DataPurgeService::objectKey).distinct().toList());
        Map<String, Integer> rows = transactionTemplate.execute(status -> {
            Map<String, Integer> deleted = new LinkedHashMap<>();
            for (Step step : steps) {
                deleted.put(step.table(), jdbc.update(step.sql(), Collections.nCopies(step.parameters(), id).toArray()));
            }
            return deleted;
        });
        log.info("[데이터 파기] scope={} id={} objects={} rows={}", scope, id, keys.size(), rows);
        return new PurgeResult(rows, keys.size());
    }

    private List<String> recordingKeys(String column, String value) {
        return jdbc.queryForList("SELECT recording_key FROM meetings WHERE " + column + " = ? AND recording_key IS NOT NULL",
                String.class, value);
    }

    private List<String> listKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        try {
            for (Result<Item> result : minio.listObjects(
                    ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build())) {
                keys.add(result.get().objectName());
            }
        } catch (Exception e) {
            throw new PurgeStorageException(e);
        }
        return keys;
    }

    /** removeObjects는 결과를 끝까지 읽어야 요청이 나간다. 없는 키는 오류 없이 지나간다. */
    private void removeObjects(List<String> keys) {
        if (keys.isEmpty()) return;
        try {
            for (Result<DeleteError> result : minio.removeObjects(RemoveObjectsArgs.builder().bucket(bucket)
                    .objects(keys.stream().map(DeleteObject::new).toList()).build())) {
                DeleteError error = result.get();
                throw new PurgeStorageException(new IllegalStateException(error.objectName() + ": " + error.message()));
            }
        } catch (PurgeStorageException e) {
            throw e;
        } catch (Exception e) {
            throw new PurgeStorageException(e);
        }
    }

    /** 문서 원본·추출본 위치는 s3://bucket/key 또는 key로 저장돼 있다. */
    private static String objectKey(String uri) {
        return uri.startsWith("s3://") ? uri.substring(uri.indexOf('/', "s3://".length()) + 1) : uri;
    }

    private record Step(String table, String sql, int parameters) {}

    public record PurgeResult(Map<String, Integer> deletedRows, int deletedObjects) {}

    public static class PurgeStorageException extends RuntimeException {
        PurgeStorageException(Exception cause) {
            super("object storage 삭제 실패", cause);
        }
    }
}
