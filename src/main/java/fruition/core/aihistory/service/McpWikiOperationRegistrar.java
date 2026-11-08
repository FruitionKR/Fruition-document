package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.repository.OperationLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Registers a stable MCP wiki operation in the caller's approval transaction. */
@Service
public class McpWikiOperationRegistrar {
    private final OperationLogRepository operations;

    public McpWikiOperationRegistrar(OperationLogRepository operations) {
        this.operations = operations;
    }

    @Transactional
    public void registerProcessing(String operationId, String workspaceId, String userId,
                                   OperationType operationType, String targetDocumentId, Instant startedAt) {
        operations.saveAndFlush(OperationLog.processing(
                operationId, workspaceId, userId, operationType, targetDocumentId, startedAt));
    }
}
