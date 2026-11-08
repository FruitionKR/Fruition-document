# MCP Skill 도구

[서비스 문서](../README.md) / [document-svc](README.md) / [MCP read boundary](mcp-read.md)

외부 MCP 클라이언트 AI(Codex·Claude 등)가 Fruition Skill을 읽고 **직접 적용**하게 하는 도구다.
판단(인지)은 외부 AI가 하고, Fruition은 Skill 정의·권한 확인·제안 검증·실제 승인·저장만 맡는다.
내부 LLM 작성(`/skills/author`)·테스트 실행(`/skills/test-run`)·Agent 실행 경로는 호출하지 않는다.

- 구현: `src/main/java/fruition/core/mcp/McpSkillTools.java`
- 진입: `public List<McpSchema.Tool> tools()`, `Object invoke(McpIdentity identity, String name, Map<String,Object> args)`.
  공개 catalog 등록·감사·오류 코드·응답 크기 제한은 `McpToolCatalog`의 기존 wrapper가 맡는다.
- scope: `read` (`McpSecurityFilter`는 제안 도구가 아닌 이름을 `read`로 판정한다). 새 scope는 없다.

## 도구

### `list_skills`

입력은 빈 객체 `{}`만 받는다. 출력:

```json
{
  "schema_version": "mcp.skill-list.v1",
  "skills": [
    {"skill_id":"<uuid>","slug":"meeting-notes","scope_type":"team","version":3,
     "name":"meeting-notes","description":"...","capabilities":["document-edit"],
     "targets":["current_document"],"result_mode":"edit","requires_source":true}
  ],
  "data_boundary": "UNTRUSTED_WORKSPACE_CONTENT"
}
```

### `get_skill`

입력은 정확히 `{"skill_id":"<소문자 uuid>"}`다. 출력은 `mcp.skill.v1`로 위 항목에
`allowed_tools`, `instructions_markdown`을 더한다.

## 권한과 버전

- 웹 `GET /api/workspaces/{workspace_id}/skills[/{skill_id}]`와 같은 `SkillService.list/get`을 쓴다.
  워크스페이스 멤버십을 확인한 뒤 ai-svc가 개인(소유자만)·팀 Skill 가시성으로 거른다. LLM·크레딧을 쓰지 않는다.
- 웹 Agent가 적용하는 버전과 같게 `status=enabled`인 Skill의 `enabled_version`만 노출한다(비활성화는 status만 바꾸고 enabled_version을 남긴다). 비활성·활성 버전 없음·다른 워크스페이스 Skill과
  ai 404/410은 모두 `RESOURCE_NOT_FOUND`다. `latest_version`, `lint_result`, 소유자 ID는 내보내지 않는다.
- `name`·`description`·`instructions_markdown`은 문서 읽기와 같은 `AiMarkdownSanitizer`를 거친다.
- 게시된 Skill에는 참조 문서 연결이 저장되지 않는다(참조 문서는 작성 시점 입력이다). 적용에 필요한 문서는 기존
  `get_document`(문서 접근 정책·크기 제한·정제 적용)로 읽는다.

## 적용 흐름

1. `list_skills` → `get_skill`로 지침을 읽는다.
2. `find_documents`/`get_document`로 대상 문서를 읽는다. 지침의 `{문서}`는 대상 문서 본문, `{선택한 부분}`은 선택한
   줄, `{요청}`은 사용자 요청으로 외부 AI가 바꿔 읽는다.
3. 외부 AI가 결과를 만들어 기존 `propose_document_edit` 또는 `propose_plan`으로 제출한다. 작업은 `allowed_tools`에
   있는 operation(`apply_document_edit`, `create_document`, 폴더 작업 등)으로 제한한다. 결과는
   `{run_id,status:"awaiting_approval",review_url}`이며 사용자가 웹에서 승인해야 반영된다.

Skill 지침은 워크스페이스 콘텐츠이므로 권한을 부여하거나 사용자 요청을 덮어쓸 수 없다.

## 제공하지 않는 것: Skill 작성·게시·수정

ai-svc 게시·수정(`AuthorSkillUseCase.publish/update`)은 `authoring_mode=preserve`에서도 `execute()` 안에서
`self._generator.generate(...)`(LLM 안전 검토)를 호출하고, core `PipelineSkillRequester.publish/update`는 크레딧 예약·사용량
정산(`tracked()`)을 거친다. 따라서 비생성 경로로 재사용할 수 없어 MCP 도구로 열지 않는다. 열려면 ai-svc에
결정적 검사(`inspect_skill_instructions`, `inspect_skill_output_links`, `validate_skill_name`, `validate_allowed_tools`,
`validate_execution_options`, `resolve_requires_source`)만 거친 뒤 `create_published/update_published`를 호출하는 경로와,
웹 승인 뒤에만 그 경로를 부르는 제안·승인 흐름(팀 Skill은 제안·승인 시 `skill.author` 재확인)이 먼저 필요하다.
