package fruition.core.document.service;

import fruition.core.document.domain.DocumentContentVersion;
import fruition.core.document.repository.DocumentContentVersionRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;

final class HistoryRepositoryFixture {
    static List<DocumentContentVersion> install(DocumentContentVersionRepository repository) {
        List<DocumentContentVersion> rows = new ArrayList<>();
        lenient().when(repository.findTopByIdDocumentIdOrderByIdVersionDesc(anyString())).thenAnswer(call ->
                rows.stream().filter(v -> v.getDocumentId().equals(call.getArgument(0)))
                        .max(Comparator.comparingLong(DocumentContentVersion::getVersion)));
        lenient().when(repository.findFirstByIdDocumentIdAndRevisionOrderByIdVersionDesc(anyString(), anyLong()))
                .thenAnswer(call -> rows.stream().filter(v -> v.getDocumentId().equals(call.getArgument(0))
                        && v.getRevision() == (long) call.getArgument(1))
                        .max(Comparator.comparingLong(DocumentContentVersion::getVersion)));
        lenient().when(repository.insertSnapshot(anyString(), anyLong(), anyLong(), anyString(), anyString(),
                nullable(String.class), any(), anyString(), nullable(Long.class))).thenAnswer(call -> {
                    rows.add(new DocumentContentVersion(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                            call.getArgument(3), call.getArgument(4), call.getArgument(5), call.getArgument(6), call.getArgument(7)));
                    return 1;
                });
        return rows;
    }
}
