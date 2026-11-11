package fruition.core.document.service;

import fruition.core.document.repository.ConvertCommandOutbox;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import fruition.core.document.repository.PostgresDocumentEditStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class McpDocumentDataGatewayTest {
    @Test void findDocumentsUsesTheWebDocumentSearchQueryWithTheMcpCandidateLimit() {
        DocumentRepository documents = mock(DocumentRepository.class);
        var gateway = new McpDocumentDataGateway(documents, mock(FolderRepository.class),
                mock(DocumentAccessPolicy.class), mock(PostgresDocumentEditStore.class),
                mock(DocumentEditStateInitializer.class), mock(ConvertCommandOutbox.class));

        gateway.search("ws", "보고서", 21);

        verify(documents).searchVisibleByWorkspaceId("ws", "보고서", PageRequest.of(0, 21));
    }
}
