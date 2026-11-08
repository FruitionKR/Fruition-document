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

## `propose_skill_definition`: 외부 AI가 작성한 Skill 정의 제안

외부 AI가 직접 쓴 Skill 정의를 **웹 승인 대기**로 올린다. 승인 전에는 아무것도 저장하지 않는다. scope는 `write_propose`
(core 보안 필터가 이 이름을 매핑한다)이고 annotation은 `readOnlyHint=false`, `destructiveHint=false`다.

입력(알 수 없는 필드 거절, `definition`의 열 개 필드는 모두 보내고 생략 값은 `null`):

```json
{"idempotency_key":"k-1","request_summary":"회의록 Skill",
 "definition":{"skill_id":null,"expected_version":null,"scope_type":"personal","name":"meeting-notes",
  "description":"...","instructions_markdown":"...","capabilities":["document-edit"],
  "targets":null,"result_mode":null,"requires_source":null}}
```

- 생성은 `skill_id`·`expected_version`이 `null`이고 `scope_type`이 필요하다. 수정은 `skill_id`와 `get_skill`의 `version`을
  `expected_version`으로 보내며 `null` 필드는 현재 값을 이어받는다(웹 `PATCH`와 같다). `allowed_tools`·actor·승인 값은 받지 않는다.
- 웹 `SkillService.requireAuthorForScope`와 같이 개인 Skill이 아니면(수정은 현재 Skill 범위) 현재 grant의 `skill.author`가
  필요하다(`PERMISSION_REVOKED`).
- Document는 형식만 확인하고 기존 `mcp.agent-proposal.v1`(`POST /agent/runs/mcp-proposals`, actor `tool_name`
  `propose_skill_definition`, proposal `{request_summary, definition}`)으로 보낸다. ai가 LLM 없이 게시·수정과 같은 결정적
  규칙으로 검증해 `publish_skill_definition` operation 하나(arguments = 이어받은 값까지 확정한 저장 정의와 계산한
  `allowed_tools`)를 만든다. 결과는 `{run_id,status:"awaiting_approval",review_url}`이다.

## 승인과 실행

- 승인 화면은 기존 `/mcp/workspaces/{workspace_id}/runs/{run_id}`이고 승인·견적·예약은 기존 MCP 승인 경로와 같다.
  **Skill 정의 저장은 무료다**: `publish_skill_definition`은 요금 대상 operation 수에서 빠져 견적 `maximum_charge_minor`·예약·
  정산이 0이고, 영수증도 청구에서 제외한다. Skill을 실제로 적용한 결과(`propose_document_edit`/`propose_plan`)만 그
  operation의 기존 MCP 요금이 붙으며 Skill 별도 가산 요금은 없다. 승인 시점과 실행 시점(`McpAgentApprovalService.authorizeExecution`)에 현재 grant·policy·
  `skill.author`를 다시 확인하고, 실행 시점에는 `expected_version`이 현재 최신 버전과 같아야 한다(다르면 409).
- 실행(`AgentToolService`)은 ai `POST /skills/mcp-acceptances`(같은 run·operation이면 저장 증거 재생)로 저장한 뒤 그 증거로
  `mcp_document_edit_receipts`에 resource_type `SKILL` 영수증을 남긴다(생성 before null, 수정 before = expected_version,
  after = 새 버전 = expected_version + 1). ai 저장 뒤 영수증 전에 끊기면 같은 operation 재실행이나 영수증 조회
  (`lookup`)가 ai 저장 증거(`POST /skills/mcp-acceptances/lookup`)로 같은 시도의 영수증을 복구한다.
- 취소 시 되돌리기는 ai가 같은 트랜잭션에 남긴 `skill_undo` 증거로 기존 Skill 되돌리기 경로를 쓴다.

## 쓰지 않는 경로

웹 게시·수정(`/skills/author/publish`, `PATCH`)의 ai 경로는 `authoring_mode=preserve`에서도 LLM 안전 검토
(`AuthorSkillUseCase.execute` → generator)와 크레딧 예약(`PipelineSkillRequester.tracked()`)을 거치므로 MCP에서 쓰지 않는다.
MCP 정의 저장은 같은 결정적 검사만 쓰는 ai `accept_external_skill_definition`으로 한다.
