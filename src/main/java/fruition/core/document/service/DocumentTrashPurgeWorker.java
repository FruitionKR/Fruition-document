package fruition.core.document.service;

import fruition.core.purge.DataPurgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 휴지통에 보관 기간보다 오래 있던 문서·폴더를 영구 삭제한다.
 *
 * <p>위키에서는 휴지통으로 옮길 때 이미 뺐으므로({@link DocumentWikiRetirement}) 여기서는 core_db 행과 원본 객체만 지운다.
 * 문서에 딸린 본문·버전·잠금·asset 참조는 CASCADE로 함께 지워진다. 이미지 asset은 다른 문서가 참조하지 않으면
 * 미참조로 표시해 {@link DocumentAssetCleanupWorker}가 보존 기간 뒤 객체와 함께 지우게 한다.
 *
 * <p>대상 행을 {@code FOR UPDATE SKIP LOCKED}로 잡으므로 여러 replica가 동시에 돌아도 한 행은 한 번만 처리된다.
 */
@Service
public class DocumentTrashPurgeWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentTrashPurgeWorker.class);
    private static final int BATCH_SIZE = 100;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final DataPurgeService purgeService;
    private final Duration retention;

    public DocumentTrashPurgeWorker(NamedParameterJdbcTemplate jdbc,
                                    TransactionTemplate transactionTemplate,
                                    DataPurgeService purgeService,
                                    @Value("${app.document-trash.retention:30d}") Duration retention) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.purgeService = purgeService;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${app.document-trash.purge-delay-ms:86400000}")
    public void runScheduled() {
        purge(Instant.now());
    }

    /** 지운 문서 수를 돌려준다. 보관 기간 안의 문서와 폴더는 건드리지 않는다. */
    public int purge(Instant now) {
        Timestamp cutoff = Timestamp.from(now.minus(retention));
        int documents = 0;
        int batch;
        do {
            Batch result = transactionTemplate.execute(status -> purgeDocuments(cutoff, Timestamp.from(now)));
            batch = result.count();
            documents += batch;
            try {
                purgeService.removeObjects(result.objectUris());
            } catch (DataPurgeService.PurgeStorageException e) {
                // ponytail: 행이 이미 사라져 다시 찾을 수 없다. 남은 원본은 키를 로그로 남겨 수동 정리한다.
                log.warn("[휴지통 원본 삭제 실패] uris={}", result.objectUris(), e);
            }
        } while (batch == BATCH_SIZE);

        int folders = 0;
        do {
            batch = transactionTemplate.execute(status -> purgeFolders(cutoff));
            folders += batch;
        } while (batch == BATCH_SIZE);

        if (documents + folders > 0) {
            log.info("[휴지통 영구 삭제] documents={} folders={}", documents, folders);
        }
        return documents;
    }

    private Batch purgeDocuments(Timestamp cutoff, Timestamp now) {
        List<String> ids = jdbc.queryForList("""
                SELECT id FROM documents WHERE deleted_at <= :cutoff
                ORDER BY deleted_at LIMIT :limit FOR UPDATE SKIP LOCKED
                """, new MapSqlParameterSource("cutoff", cutoff).addValue("limit", BATCH_SIZE), String.class);
        if (ids.isEmpty()) {
            return new Batch(0, List.of());
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids).addValue("now", now);
        List<Object> assetIds = jdbc.queryForList(
                "SELECT DISTINCT asset_id FROM document_asset_references WHERE document_id IN (:ids)", params, Object.class);
        List<String> uris = new ArrayList<>();
        jdbc.query("DELETE FROM documents WHERE id IN (:ids) RETURNING source_uri, extracted_text_uri", params, row -> {
            addIfPresent(uris, row.getString("source_uri"));
            addIfPresent(uris, row.getString("extracted_text_uri"));
        });
        if (!assetIds.isEmpty()) {
            jdbc.update("""
                    UPDATE document_assets a SET unreferenced_since = :now
                    WHERE a.id IN (:assets) AND a.unreferenced_since IS NULL
                      AND NOT EXISTS (SELECT 1 FROM document_asset_references r WHERE r.asset_id = a.id)
                    """, params.addValue("assets", assetIds));
        }
        return new Batch(ids.size(), uris);
    }

    /**
     * 문서가 남아 있지 않은 폴더만 지운다. 상위 폴더를 지우면 하위 폴더가 CASCADE로 지워지므로,
     * 아직 보관 기간 안이거나 활성인 하위 폴더가 있으면 남긴다.
     */
    private int purgeFolders(Timestamp cutoff) {
        return jdbc.update("""
                DELETE FROM folders WHERE id IN (
                    SELECT f.id FROM folders f
                    WHERE f.deleted_at <= :cutoff
                      AND NOT EXISTS (SELECT 1 FROM documents d WHERE d.folder_id = f.id)
                      AND NOT EXISTS (SELECT 1 FROM folders c WHERE c.parent_folder_id = f.id
                                      AND (c.deleted_at IS NULL OR c.deleted_at > :cutoff))
                    LIMIT :limit FOR UPDATE SKIP LOCKED)
                """, new MapSqlParameterSource("cutoff", cutoff).addValue("limit", BATCH_SIZE));
    }

    private static void addIfPresent(List<String> uris, String uri) {
        if (uri != null && !uri.isBlank()) {
            uris.add(uri);
        }
    }

    private record Batch(int count, List<String> objectUris) {}
}
