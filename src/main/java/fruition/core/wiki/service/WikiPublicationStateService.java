package fruition.core.wiki.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.dto.OperationResultRequest;
import fruition.core.aihistory.service.WikiObjectReader;
import fruition.core.wiki.domain.WikiPageContributionId;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import fruition.core.wiki.repository.WikiWorkspaceVersionRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Wiki-owned verification and version update for committed publication receipts. */
@Service
public class WikiPublicationStateService {
    private final WikiPageVersionRepository versions;
    private final WikiPageContributionRepository contributions;
    private final WikiWorkspaceVersionRepository workspaceVersions;
    private final WikiObjectReader objectReader;

    public WikiPublicationStateService(WikiPageVersionRepository versions,
                                       WikiPageContributionRepository contributions,
                                       WikiWorkspaceVersionRepository workspaceVersions,
                                       WikiObjectReader objectReader) {
        this.versions = versions;
        this.contributions = contributions;
        this.workspaceVersions = workspaceVersions;
        this.objectReader = objectReader;
    }

    public boolean matchesManualResult(OperationLog operation, OperationResultRequest result) {
        OperationResultRequest.ChangedPage page = result.changedPages().getFirst();
        OperationResultRequest.LedgerContribution ledger = result.ledgerContributionsOrEmpty().getFirst();
        var version = versions.findFirstByIdPageIdAndOperationId(page.pageId(), operation.getOperationId())
                .orElse(null);
        var contribution = contributions.findById(
                new WikiPageContributionId(page.pageId(), operation.getOperationId())).orElse(null);
        return version != null && contribution != null
                && page.pageId().equals(ledger.pageId())
                && operation.getOperationId().equals(ledger.operationId())
                && Objects.equals(operation.getTargetDocumentId(), result.targetDocumentId())
                && Objects.equals(operation.getSummary(), result.summary())
                && operation.getChangedResourceCount() == 1
                && ledger.documentId() == null && ledger.linkedByOperationId() == null
                && Boolean.TRUE.equals(page.contributionStored())
                && objectReader.markdownKey(operation.getWorkspaceId(), page.pageId(), operation.getOperationId())
                        .equals(page.markdownKey())
                && objectReader.contributionKey(operation.getWorkspaceId(), page.pageId(), operation.getOperationId())
                        .equals(page.contributionKey())
                && Objects.equals(page.contributionKey(), ledger.contributionKey())
                && version.getMarkdownKey().equals(page.markdownKey())
                && version.getContentHash().equals(page.contentHash())
                && objectReader.sha256(version.getMarkdown()).equals(version.getContentHash())
                && operation.getUserId().equals(version.getCreatedBy())
                && contribution.getSourceDocumentId() == null && contribution.isActive()
                && contribution.getLintOperationId() == null
                && contribution.getSequenceRevision() == version.getRevision()
                && contribution.getObjectKey().equals(ledger.contributionKey());
    }

    public void bumpWorkspaceVersion(String workspaceId) {
        workspaceVersions.bump(workspaceId);
    }
}
