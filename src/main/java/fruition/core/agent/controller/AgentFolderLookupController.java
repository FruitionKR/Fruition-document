package fruition.core.agent.controller;

import fruition.core.agent.dto.AgentFolderTreeRequest;
import fruition.core.document.dto.DocumentTreeResponse;
import fruition.core.document.service.FolderService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 작업 탭의 폴더 내용 조회(folder_lookup)가 ai-svc에서 부른다. X-Agent-Service-Token은 AgentServiceTokenFilter가 검증한다. */
@RestController
@RequestMapping("/internal/agent/folder-lookup")
public class AgentFolderLookupController {

    private final FolderService folderService;

    public AgentFolderLookupController(FolderService folderService) {
        this.folderService = folderService;
    }

    @Operation(summary = "Agent 폴더 트리 읽기",
            description = "사이드바 트리(GET 트리 조회)와 같은 폴더·문서 트리를 사용자 기준으로 반환합니다.")
    @PostMapping("/tree")
    public ResponseEntity<DocumentTreeResponse> readFolderTree(@Valid @RequestBody AgentFolderTreeRequest request) {
        return ResponseEntity.ok(folderService.tree(request.workspaceId(), request.userId()));
    }
}
