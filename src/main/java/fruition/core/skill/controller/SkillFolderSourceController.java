package fruition.core.skill.controller;

import fruition.core.skill.dto.SkillFolderSourceReadRequest;
import fruition.core.skill.dto.SkillFolderSourceReadResponse;
import fruition.core.skill.service.SkillFolderSourceService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Skill 실행이 폴더를 원문으로 쓸 때 ai-svc가 부른다. X-Agent-Service-Token은 AgentServiceTokenFilter가 검증한다. */
@RestController
@RequestMapping("/internal/agent/skill-execution/folder-sources")
public class SkillFolderSourceController {

    private final SkillFolderSourceService folderSourceService;

    public SkillFolderSourceController(SkillFolderSourceService folderSourceService) {
        this.folderSourceService = folderSourceService;
    }

    @Operation(summary = "Skill 폴더 원문 읽기",
            description = "폴더 바로 아래 Markdown 편집 문서 중 사용자가 읽을 수 있는 문서의 최신 본문을 최근 수정순으로 반환합니다.")
    @PostMapping("/read")
    public ResponseEntity<SkillFolderSourceReadResponse> readFolderSources(
            @Valid @RequestBody SkillFolderSourceReadRequest request) {
        return ResponseEntity.ok(folderSourceService.read(
                request.workspaceId(), request.userId(), request.folderId(), request.maxDocuments()));
    }
}
