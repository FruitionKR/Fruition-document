package fruition.core.query.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineQueryResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("evidence_snippets가 원본 block 기준 필드(rank/source_document_id/source_block_ids/text)로 역직렬화된다")
    void deserialize_evidenceSnippets_originalBlockFields() throws Exception {
        String json = """
                {
                  "answer": "Self-Attention은 입력 토큰 간의 관계를 학습하는 메커니즘입니다. [1]",
                  "related_pages": [],
                  "evidence_snippets": [
                    {
                      "rank": 1,
                      "source_document_id": "doc_1f9a74af",
                      "source_block_ids": ["B0005", "B0006"],
                      "text": "원본 block citation이 붙어 있던 근거 문장"
                    }
                  ],
                  "graph_context": { "nodes": [], "edges": [] },
                  "traversal_paths": [],
                  "web_search_requested": true,
                  "web_search_executed": true,
                  "result_count": 2,
                  "error_code": null
                }
                """;

        PipelineQueryResponse response = objectMapper.readValue(json, PipelineQueryResponse.class);

        assertThat(response.evidenceSnippets()).hasSize(1);
        PipelineQueryResponse.EvidenceSnippet snippet = response.evidenceSnippets().get(0);
        assertThat(snippet.rank()).isEqualTo(1);
        assertThat(snippet.sourceDocumentId()).isEqualTo("doc_1f9a74af");
        assertThat(snippet.sourceBlockIds()).isEqualTo(List.of("B0005", "B0006"));
        assertThat(snippet.text()).isEqualTo("원본 block citation이 붙어 있던 근거 문장");
        assertThat(response.webSearchRequested()).isTrue();
        assertThat(response.webSearchExecuted()).isTrue();
        assertThat(response.resultCount()).isEqualTo(2);
        assertThat(response.errorCode()).isNull();
    }

    @Test
    @DisplayName("evidence_snippets의 source_refs가 다중 문서 참조 배열로 역직렬화된다")
    void deserialize_evidenceSnippets_sourceRefs() throws Exception {
        String json = """
                {
                  "answer": "답변 [1]",
                  "related_pages": [],
                  "evidence_snippets": [
                    {
                      "rank": 1,
                      "source_document_id": "doc_1f9a74af",
                      "source_block_ids": ["B0005"],
                      "source_refs": [
                        { "source_document_id": "doc_1f9a74af", "source_block_id": "B0005" },
                        { "source_document_id": "doc_cross", "source_block_id": "B0009" }
                      ],
                      "text": "근거 문장"
                    }
                  ],
                  "graph_context": { "nodes": [], "edges": [] },
                  "traversal_paths": []
                }
                """;

        PipelineQueryResponse response = objectMapper.readValue(json, PipelineQueryResponse.class);

        PipelineQueryResponse.EvidenceSnippet snippet = response.evidenceSnippets().get(0);
        assertThat(snippet.sourceRefs()).containsExactly(
                new PipelineQueryResponse.SourceRef("doc_1f9a74af", "B0005"),
                new PipelineQueryResponse.SourceRef("doc_cross", "B0009"));
    }
}
