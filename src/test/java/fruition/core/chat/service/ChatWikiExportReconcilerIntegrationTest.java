package fruition.core.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.chat.repository.ChatPartialWikiRepository;
import fruition.core.document.domain.Document;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.service.DocumentService;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 여러 Pod의 후처리가 같은 문서를 동시에 집어도 한 번만 확정한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChatWikiExportReconcilerIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentRepository documents;
    @Autowired ChatPartialWikiRepository partials;
    @Autowired DocumentService documentService;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ObjectMapper mapper;
    @Autowired AiCommandOutboxWriter taskWriter;

    @Test
    void overlappingReconcilesConfirmNameOnce() throws Exception {
        String ws = "ws-" + UUID.randomUUID();
        String doc = "chatdoc-" + UUID.randomUUID();
        String run = "ingest:" + UUID.randomUUID();
        String session = "sess-" + UUID.randomUUID();
        jdbc.update("INSERT INTO chat_sessions(id, created_at, user_id, workspace_id) VALUES (?, now(), 'user', ?)", session, ws);
        jdbc.update("INSERT INTO ai_task_runs(id, workspace_id, user_id, kind, status) VALUES (?, ?, 'user', 'ingest', 'completed')",
                run, ws);
        jdbc.update("INSERT INTO documents(id, byte_size, content_hash, filename, display_name, normalized_filename, "
                + "mime_type, status, uploaded_at, updated_at, user_id, workspace_id, current_content_hash, current_version, "
                + "document_role, sort_order, origin, pipeline_run_id, pipeline_input_blocks) "
                + "VALUES (?, 1, 'h', '[채팅] 임시', '[채팅] 임시', '[채팅] 임시', 'text/markdown', 'completed', now(), now(), "
                + "'user', ?, 'h', 1, 'EDITABLE', 0, 'chat_export', ?, ?)",
                doc, ws, run, "[{\"block_id\":\"" + session + ":pair_1\",\"text\":\"Q\"}]");
        PipelineWikiStateRequester wiki = mock(PipelineWikiStateRequester.class);
        when(wiki.documentContext(eq(ws), anyString())).thenAnswer(inv -> {
            Thread.sleep(200);  // AI 조회 동안 두 Pod가 같은 문서를 들고 있게 한다.
            return new PipelineWikiStateRequester.DocumentWikiContext(List.of(new PipelineWikiStateRequester.DocumentPage(
                    "wiki-" + doc, "source", "검색 인덱싱", "title", "source_of", 1.0)), List.of(), null);
        });

        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        List<Future<?>> pods = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            var reconciler = new ChatWikiExportReconciler(onlyDocument(doc), wiki, partials, documentService,
                    transactionTemplate, mapper, taskWriter);
            pods.add(pool.submit(() -> {
                barrier.await();
                reconciler.reconcile();
                return null;
            }));
        }
        for (Future<?> pod : pods) pod.get();
        pool.shutdown();

        assertThat(jdbc.queryForObject("SELECT filename FROM documents WHERE id = ?", String.class, doc))
                .isEqualTo("[채팅] 검색 인덱싱.md");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_partial_wiki WHERE document_id = ?", Integer.class, doc))
                .isEqualTo(1);
    }

    /** 다른 테스트가 남긴 chat_export 문서는 대상에서 뺀다. */
    private DocumentRepository onlyDocument(String id) {
        return (DocumentRepository) Proxy.newProxyInstance(DocumentRepository.class.getClassLoader(),
                new Class<?>[]{DocumentRepository.class}, (proxy, method, args) -> {
                    Object result = method.invoke(documents, args);
                    if (!method.getName().equals("findAllByOriginAndStatusAndReconciledAtIsNull")) return result;
                    return ((List<?>) result).stream().filter(d -> ((Document) d).getId().equals(id)).toList();
                });
    }
}
