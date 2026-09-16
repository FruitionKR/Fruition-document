package fruition.core.document.service;

import fruition.core.document.domain.DocumentConvertQueue;
import fruition.core.document.repository.DocumentConvertQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentConvertWorkerTest {

    @Mock DocumentConvertQueueRepository queueRepository;
    @Mock DocumentService documentService;
    @Mock TransactionTemplate transactionTemplate;

    DocumentConvertWorker worker;

    @BeforeEach
    void setUp() {
        worker = new DocumentConvertWorker(queueRepository, documentService, transactionTemplate);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(null));
    }

    private DocumentConvertQueue pendingItem() {
        DocumentConvertQueue item = new DocumentConvertQueue("doc_placeholder", "doc_source_pdf");
        ReflectionTestUtils.setField(item, "id", 7L);
        return item;
    }

    @Test
    @DisplayName("pending 항목을 processing으로 바꾸고 변환 수행 후 큐에서 삭제한다")
    void processNext_pendingItem_convertsAndDeletes() {
        DocumentConvertQueue item = pendingItem();
        when(queueRepository.findFirstByStatusOrderByCreatedAtAsc("pending"))
                .thenReturn(Optional.of(item));

        worker.processNext();

        assertThat(item.getStatus()).isEqualTo("processing");
        verify(documentService).doConvert(7L, "doc_placeholder", "doc_source_pdf");
        verify(queueRepository).deleteById(7L);
    }

    @Test
    @DisplayName("변환 수행이 예외로 끝나도 큐 행은 삭제한다 (실패는 문서 상태에 반영됨)")
    void processNext_convertThrows_stillDeletesQueueRow() {
        DocumentConvertQueue item = pendingItem();
        when(queueRepository.findFirstByStatusOrderByCreatedAtAsc("pending"))
                .thenReturn(Optional.of(item));
        doThrow(new RuntimeException("unexpected"))
                .when(documentService).doConvert(7L, "doc_placeholder", "doc_source_pdf");

        try {
            worker.processNext();
        } catch (RuntimeException ignored) {
            // 예외 전파 여부와 무관하게 큐 정리를 검증한다.
        }

        verify(queueRepository).deleteById(7L);
    }

    @Test
    @DisplayName("pending 항목이 없으면 아무 것도 하지 않는다")
    void processNext_emptyQueue_doesNothing() {
        when(queueRepository.findFirstByStatusOrderByCreatedAtAsc("pending"))
                .thenReturn(Optional.empty());

        worker.processNext();

        verify(documentService, never()).doConvert(anyLong(), anyString(), anyString());
        verify(queueRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("서버 재시작 시 processing으로 남은 항목을 pending으로 되돌린다")
    void resetStuckItems_processingItems_backToPending() {
        DocumentConvertQueue stuck = pendingItem();
        stuck.setStatus("processing");
        when(queueRepository.findAllByStatus("processing")).thenReturn(List.of(stuck));

        worker.resetStuckItems();

        assertThat(stuck.getStatus()).isEqualTo("pending");
        verify(queueRepository).save(stuck);
    }
}
