# Agent API

[서비스 문서](../README.md) / [document-svc](README.md)

사용자용 Agent 실행·승인과 내부 Tool 실행 API다. Agent turn은 Kafka `ai.agent.command`,
계획 조회·승인·거절·취소·수정은 ai-svc 내부 HTTP로 전달한다.

- API 수: 10

## API 목차

| API | 목적 |
|---|---|
| [`GET /api/workspaces/{workspace_id}/agent/runs/{run_id}`](#summary-get-api-workspaces-workspace-id-agent-runs-run-id) | 자율 AgentRun 계획과 실행 상태를 조회합니다. |
| [`POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/approve`](#summary-post-api-workspaces-workspace-id-agent-runs-run-id-approve) | 현재 AgentRun 계획을 승인합니다. |
| [`POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/cancel`](#summary-post-api-workspaces-workspace-id-agent-runs-run-id-cancel) | 현재 AgentRun을 취소합니다. |
| [`POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/reject`](#summary-post-api-workspaces-workspace-id-agent-runs-run-id-reject) | 현재 AgentRun 계획을 거절합니다. |
| [`POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/revise`](#summary-post-api-workspaces-workspace-id-agent-runs-run-id-revise) | 현재 AgentRun에 새 계획을 요청합니다. |
| [`POST /api/workspaces/{workspace_id}/agent/turn`](#summary-post-api-workspaces-workspace-id-agent-turn) | 사용자 요청을 비동기 Agent 실행 대기열에 등록합니다. |
| [`GET /api/workspaces/{workspace_id}/agent/turn/{run_id}`](#summary-get-api-workspaces-workspace-id-agent-turn-run-id) | 워크스페이스의 Agent 실행 결과를 조회합니다. |
| [`GET /api/workspaces/{workspace_id}/agent/turn/{run_id}/events`](#summary-get-api-workspaces-workspace-id-agent-turn-run-id-events) | Agent turn의 진행 상황과 최종 결과를 Server-Sent Events로 전달합니다. |
| [`POST /internal/agent/tools/execute/{tool_name}`](#summary-post-internal-agent-tools-execute-tool-name) | 승인된 Agent Tool 변경 작업을 실행합니다. |
| [`POST /internal/agent/tools/read/{tool_name}`](#summary-post-internal-agent-tools-read-tool-name) | 승인된 Agent Tool 읽기 작업을 실행합니다. |

## 한눈에 보기

<a id="summary-get-api-workspaces-workspace-id-agent-runs-run-id"></a>
### `GET /api/workspaces/{workspace_id}/agent/runs/{run_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 자율 AgentRun 계획과 실행 상태를 조회합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `run_id`: `string` |
| 출력 | `200` 성공 — `JsonNode` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | 공통 오류 계약 적용 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-agent-runs-run-id"></a>
### `GET /api/workspaces/{workspace_id}/agent/runs/{run_id}` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/agent/runs/{run_id}`

#### 2. 목적

자율 AgentRun 계획과 실행 상태를 조회합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `run_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*` (`JsonNode`)

```json
{
}
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/agent/runs/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: getRun`)
- 호출자: 프론트엔드 — `src/features/agent-chat/api/agentPlan.ts:15`(`fetchAgentPlanRun`)
- 하위 호출: ai-svc `GET ${AGENT_RUN_ENDPOINT}/{runId}` (`PipelineAgentRunStatusRequester`, `X-Agent-Service-Token`)
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-agent-runs-run-id)

</details>

<a id="summary-post-api-workspaces-workspace-id-agent-runs-run-id-approve"></a>
### `POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/approve`

| 항목 | 내용 |
|---|---|
| 목적 | 현재 AgentRun 계획을 승인합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `run_id`: `string`<br>**Body** — `AgentRunApproveRequest` |
| 출력 | `200` 성공 — `JsonNode` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | 공통 오류 계약 적용 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-agent-runs-run-id-approve"></a>
### `POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/approve` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/approve`

#### 2. 목적

현재 AgentRun 계획을 승인합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `run_id` | `string` | 예 | - |

- Content-Type: `application/json` (`AgentRunApproveRequest`)

```json
{
  "operation_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "plan_version": 1
}
```

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*` (`JsonNode`)

```json
{
}
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/agent/runs/<value>/approve" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"operation_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","plan_version":1}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: approve`)
- 호출자: 프론트엔드 — `src/features/agent-chat/api/agentPlan.ts:25`(`decideAgentPlan(decision="approve")`)
- 하위 호출: ai-svc `POST ${AGENT_RUN_ENDPOINT}/{runId}/approve` (`PipelineAgentRunStatusRequester`, `X-Agent-Service-Token`)
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-agent-runs-run-id-approve)

</details>

<a id="summary-post-api-workspaces-workspace-id-agent-runs-run-id-cancel"></a>
### `POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/cancel`

| 항목 | 내용 |
|---|---|
| 목적 | 현재 AgentRun을 취소합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `run_id`: `string` |
| 출력 | `200` 성공 — `JsonNode` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | 공통 오류 계약 적용 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-agent-runs-run-id-cancel"></a>
### `POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/cancel` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/cancel`

#### 2. 목적

현재 AgentRun을 취소합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `run_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*` (`JsonNode`)

```json
{
}
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/agent/runs/<value>/cancel" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: cancel`)
- 호출자: 없음 — 프론트엔드 `apiFetch` 호출 지점에 이 경로가 없고, ai-svc·access-svc도 이 서비스의 `/api/**`를 호출하지 않는다. `decideAgentPlan`은 `approve`·`reject`만 보낸다(`src/features/agent-chat/api/agentPlan.ts:25`)
- 하위 호출: ai-svc `POST ${AGENT_RUN_ENDPOINT}/{runId}/cancel` (`PipelineAgentRunStatusRequester`)
- 배선 상태: **미배선 — 호출자 없음**

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-agent-runs-run-id-cancel)

</details>

<a id="summary-post-api-workspaces-workspace-id-agent-runs-run-id-reject"></a>
### `POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/reject`

| 항목 | 내용 |
|---|---|
| 목적 | 현재 AgentRun 계획을 거절합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `run_id`: `string` |
| 출력 | `200` 성공 — `JsonNode` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | 공통 오류 계약 적용 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-agent-runs-run-id-reject"></a>
### `POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/reject` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/reject`

#### 2. 목적

현재 AgentRun 계획을 거절합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `run_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*` (`JsonNode`)

```json
{
}
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/agent/runs/<value>/reject" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: reject`)
- 호출자: 프론트엔드 — `src/features/agent-chat/api/agentPlan.ts:25`(`decideAgentPlan(decision="reject")`)
- 하위 호출: ai-svc `POST ${AGENT_RUN_ENDPOINT}/{runId}/reject` (`PipelineAgentRunStatusRequester`, `X-Agent-Service-Token`)
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-agent-runs-run-id-reject)

</details>

<a id="summary-post-api-workspaces-workspace-id-agent-runs-run-id-revise"></a>
### `POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/revise`

| 항목 | 내용 |
|---|---|
| 목적 | 현재 AgentRun에 새 계획을 요청합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `run_id`: `string`<br>**Body** — `AgentRunReviseRequest` |
| 출력 | `200` 성공 — `JsonNode` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | 공통 오류 계약 적용 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-agent-runs-run-id-revise"></a>
### `POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/revise` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/revise`

#### 2. 목적

현재 AgentRun에 새 계획을 요청합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `run_id` | `string` | 예 | - |

- Content-Type: `application/json` (`AgentRunReviseRequest`)

```json
{
  "instruction": "표를 목록으로 바꿔줘"
}
```

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*` (`JsonNode`)

```json
{
}
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/agent/runs/<value>/revise" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"instruction":"표를 목록으로 바꿔줘"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: revise`)
- 호출자: 없음 — 프론트엔드 `apiFetch` 호출 지점에 이 경로가 없고, ai-svc·access-svc도 이 서비스의 `/api/**`를 호출하지 않는다. `decideAgentPlan`은 `approve`·`reject`만 보낸다(`src/features/agent-chat/api/agentPlan.ts:25`)
- 하위 호출: ai-svc `POST ${AGENT_RUN_ENDPOINT}/{runId}/revise` (`PipelineAgentRunStatusRequester`)
- 배선 상태: **미배선 — 호출자 없음**

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-agent-runs-run-id-revise)

</details>

<a id="summary-post-api-workspaces-workspace-id-agent-turn"></a>
### `POST /api/workspaces/{workspace_id}/agent/turn`

| 항목 | 내용 |
|---|---|
| 목적 | 사용자 요청을 비동기 Agent 실행 대기열에 등록합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Body** — `AgentTurnRequest` |
| 출력 | `202` Agent 실행이 대기열에 등록됨 — `AgentTurnResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 요청 — `ErrorResponse`<br>`404` 문서 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` 문서 version 충돌 — `ErrorResponse`<br>`423` 다른 사용자가 문서를 편집 중 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-agent-turn"></a>
### `POST /api/workspaces/{workspace_id}/agent/turn` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/agent/turn`

#### 2. 목적

사용자 요청을 비동기 Agent 실행 대기열에 등록합니다.

질의와 편집을 나누지 않고 이 입구 하나로 받는다. 무엇을 할지는 AI가 정하며, 질의로 판정하면
근거와 함께 답하고 편집으로 판정하면 편집안을 만든다. 어느 쪽이든 `session_id`가 가리키는
채팅 세션에 문답으로 남는다.

문서를 열지 않은 상태에서도 보낼 수 있다. 그때는 `documentId`·`baseVersion`·`editorSnapshot`을
모두 생략하며, 적용할 대상이 없어 AI는 답변·되물음만 낸다. 셋은 함께 있거나 함께 없어야 하고
하나만 오면 `400`이다.

열린 문서에서 저장·반영을 명시한 편집 요청은 `workspace_workflow` AgentRun으로 전환한다.
이때 편집 대상 문서와 기준 버전을 계획에 고정하고,
사용자가 그 계획을 승인해야 실제 문서에 반영한다. 저장을 명시하지 않은 편집 요청은 기존처럼
`markdown_edit` 미리보기만 반환한다.

`markdown_edit`의 `editorSnapshot.markdown`은 숨겨진 문서 식별 주석을 제외한 본문이다.
서버는 AI 편집 결과에 `document_edit_states`의 기존 `fruition-note` 또는 `fruition-workspace`
주석을 붙이고 마지막 줄바꿈을 보장한 저장용 Markdown을 `ready_markdown`으로 기록한다.
기존 주석이 없는 문서는 편집기와 동일하게 `<!-- fruition-note: {documentId} -->`를 사용한다.
적용 시에는 이 전체 문자열과 기준 버전이 정확히 일치해야 하며, 본문·주석 변조를 허용하지 않는다.
자율 Agent Tool은 이미 완성된 저장용 Markdown을 제공하므로 이 편집기 변환을 적용하지 않는다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| body | `session_id` | `string` | 예 | 이 턴을 남길 채팅 세션 ID |
| body | `message` | `string` | 예 | 사용자 지시문 |
| body | `documentId` | `string` | 아니오 | 편집 대상 문서. 생략하면 `baseVersion`·`editorSnapshot`도 함께 생략한다 |
| body | `baseVersion` | `integer` | 아니오 | 편집 기준 문서 버전 |
| body | `editorSnapshot` | `object` | 아니오 | 편집 시작 시점의 에디터 상태 |
| body | `allow_web_search` | `boolean` | 아니오 | Query와 웹 근거 기반 새 문서 생성에서 웹 검색을 허용할지. 편집·Skill 갈래에는 영향이 없다 |
| body | `conversationContext.selected_pair_ids` | `string[]` | 아니오 | 맥락으로 쓸 문답 ID(최대 20개). 비우면 세션의 최근 완결 문답을 쓴다 |

- Content-Type: `application/json` (`AgentTurnRequest`)

```json
{
  "baseVersion": 3,
  "conversationContext": {
    "pendingSkillProposal": {
      "allowed_tools": [
        "list_root_items"
      ],
      "capabilities": [
        "document-create"
      ],
      "description": "string",
      "instructions_markdown": "string",
      "name": "string",
      "scope_type": "string"
    },
    "referenceContext": {
    },
    "selected_pair_ids": [
      "string"
    ]
  },
  "documentId": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "editorSnapshot": {
    "markdown": "string",
    "target": {
      "endLine": 24,
      "startLine": 10,
      "type": "selection"
    }
  },
  "allow_web_search": false,
  "message": "이 문단을 표로 정리해줘",
  "model": "gpt-6-luna",
  "provider": "openai",
  "session_id": "session_0ff8564ea24047cd8144d3f48badfe3f",
  "skill_draft_excluded_literals": [
    "string"
  ],
  "skill_draft_sources": [
    {
      "run_id": "string"
    }
  ],
  "skill_draft_user_directives": [
    "string"
  ]
}
```

#### 5. Response body

- HTTP `202`: Agent 실행이 대기열에 등록됨
- Content-Type: `*/*` (`AgentTurnResponse`)

```json
{
  "apply_operation_id": "string",
  "baseVersion": 3,
  "documentId": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "error": "string",
  "requestId": "agent_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "result": {
  },
  "status": "completed"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 요청 | `ErrorResponse` |
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | 문서 version 충돌 | `ErrorResponse` |
| `423` | 다른 사용자가 문서를 편집 중 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "details": [
      {
        "field": "email",
        "reason": "email은 필수입니다."
      }
    ],
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/agent/turn" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"session_id":"session_0ff8564ea24047cd8144d3f48badfe3f","documentId":"doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83","baseVersion":3,"message":"이 문단을 표로 정리해줘","conversationContext":{"selected_pair_ids":["pair_01"],"referenceContext":{}},"editorSnapshot":{"markdown":"# 회의록\n\n정리할 문단","target":{"endLine":3,"startLine":3,"type":"selection"}},"provider":"openai","model":"gpt-6-luna"}'
```

```json
{
  "apply_operation_id": "string",
  "baseVersion": 3,
  "documentId": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "error": "string",
  "requestId": "agent_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "result": {
  },
  "status": "completed"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: turn`)
- 호출자: 프론트엔드 — `src/features/agent-chat/api/agent.ts:18`(`requestAgentTurn`)
- 하위 호출: HTTP 호출 없음. Kafka `ai.agent.command`로 ai-svc에 전달한다
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-agent-turn)

</details>

<a id="summary-get-api-workspaces-workspace-id-agent-turn-run-id"></a>
### `GET /api/workspaces/{workspace_id}/agent/turn/{run_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 워크스페이스의 Agent 실행 결과를 조회합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `run_id`: `string` |
| 출력 | `200` 결과 조회 성공 — `AgentTurnResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` Agent run ID 형식이 올바르지 않음 — `ErrorResponse`<br>`404` 실행 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`503` Agent 상태 파이프라인 사용 불가 — `JsonNode` / `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-agent-turn-run-id"></a>
### `GET /api/workspaces/{workspace_id}/agent/turn/{run_id}` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/agent/turn/{run_id}`

#### 2. 목적

워크스페이스의 Agent 실행 결과를 조회합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `run_id` | `string` | 예 | 조회할 Agent 실행 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 결과 조회 성공
- Content-Type: `*/*` (`AgentTurnResponse`)

```json
{
  "apply_operation_id": "string",
  "baseVersion": 3,
  "documentId": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "error": "string",
  "requestId": "agent_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "result": {
  },
  "status": "completed"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | Agent run ID 형식이 올바르지 않음 | `ErrorResponse` |
| `404` | 실행 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `503` | Agent 상태 파이프라인 사용 불가 | `없음` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "details": [
      {
        "field": "email",
        "reason": "email은 필수입니다."
      }
    ],
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/agent/turn/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "apply_operation_id": "string",
  "baseVersion": 3,
  "documentId": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "error": "string",
  "requestId": "agent_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "result": {
  },
  "status": "completed"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: getTurn`)
- 호출자: 프론트엔드 — `src/features/agent-chat/api/agentPlan.ts:6`, `src/features/agent-chat/api/agent.ts:37`
- 하위 호출: ai-svc `GET ${AGENT_STATUS_ENDPOINT}/{runId}` (`PipelineAgentRunStatusRequester`, `X-Internal-Token`)
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-agent-turn-run-id)

</details>

<a id="summary-get-api-workspaces-workspace-id-agent-turn-run-id-events"></a>
### `GET /api/workspaces/{workspace_id}/agent/turn/{run_id}/events`

| 항목 | 내용 |
|---|---|
| 목적 | Agent turn의 진행 상황과 최종 결과를 Server-Sent Events로 전달합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `run_id`: `string` |
| 출력 | `200` SSE 구독 시작 — `string` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십과 해당 run의 소유를 검증한다.<br>그 밖의 조건은 상세 권한 규칙 참고 |
| 주요 오류 | `400` Agent run ID 형식이 올바르지 않음 — `ErrorResponse`<br>`404` 실행 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-agent-turn-run-id-events"></a>
### `GET /api/workspaces/{workspace_id}/agent/turn/{run_id}/events` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/agent/turn/{run_id}/events`

#### 2. 목적

Agent turn의 진행 상황과 최종 결과를 Server-Sent Events로 전달합니다.

AI는 요청 확인·처리 유형 결정·편집안 작성·결과 정리 단계를 `query.log`로 전달한다. 질의 갈래는 검색 진행 단계도 전달한다. 클라이언트는 접수 응답의 `requestId`로 구독하고, 완료 이벤트 후 결과를 조회한다. 편집·생성 결과의 `message`는 스킬 사용 여부와 변경 요약을 담으며 적용할 Markdown 본문과 분리된다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `run_id` | `string` | 예 | 구독할 Agent 실행 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: SSE 구독 시작
- Content-Type: `text/event-stream`
- `Cache-Control: no-store, no-transform`, `X-Accel-Buffering: no`: 중간 프록시의 압축·버퍼링으로 진행 이벤트가 지연되지 않게 합니다.

```text
string
```

전달하는 이벤트는 질의 SSE와 같은 세 가지다. 두 갈래가 같은 broker를 쓰므로 이름도 같다.

| event | 의미 | payload |
|---|---|---|
| `query.log` | AI worker가 단계마다 발행한 진행 상황을 중계 | `request_id`, `sequence`, `received_at`, `stage`, `message`, `data` |
| `query.completed` | 최종 결과 반영 완료 | `request_id`, `status` |
| `query.failed` | 실패 확정 | `request_id`, `status`, `error` |

- 구독 시점 이전 이벤트는 Redis buffer에서 최대 200건까지 재생한다.
- 종료 이벤트는 최초 반영에서 한 번만 낸다. 결과가 재전송돼도 두 번 끝나지 않는다.
- `query.failed`의 `error`는 사용자에게 보일 문장이다. 내부 오류 코드는 로그와 `ai_task_result_receipts`에만 남는다.

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | Agent run ID 형식이 올바르지 않음 | `ErrorResponse` |
| `404` | 실행 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십과 해당 run의 소유를 검증한다.
- 자격 검증은 적용 표(`agent_apply_projections`)만으로 한다. 결과 조회와 달리 pipeline을 부르지 않아, pipeline이 멈춰 있어도 버퍼에 쌓인 이벤트를 구독할 수 있다.

#### 9. 예시 요청/응답

```bash
curl -N -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/agent/turn/<value>/events" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Accept: text/event-stream'
```

```text
string
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: subscribeTurnEvents`)
- 호출자: 프론트엔드 — `src/features/agent-chat/api/agent.ts:29`(SSE를 `EventSource` 대신 인증된 `fetch` 스트림으로 읽는다. `src/shared/lib/runEvents.ts`)
- 하위 호출: 없음. 적용 표(`agent_apply_projections`)만으로 자격을 검증하고 버퍼 이벤트를 전달한다
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-agent-turn-run-id-events)

</details>

<a id="summary-post-internal-agent-tools-execute-tool-name"></a>
### `POST /internal/agent/tools/execute/{tool_name}`

| 항목 | 내용 |
|---|---|
| 목적 | 승인된 Agent Tool 변경 작업을 실행합니다. |
| 입력 | **Path** — `tool_name`: `string`<br>**Header** — `X-Agent-Service-Token`(필수, 인증 계층 검증): `string`<br>**Body** — `AgentToolExecuteRequest` |
| 출력 | `200` 성공 — `object` |
| 조건 | 인증 필요<br>`X-Agent-Service-Token`을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `401` Agent 서비스 인증 토큰 누락 또는 불일치 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-internal-agent-tools-execute-tool-name"></a>
### `POST /internal/agent/tools/execute/{tool_name}` 상세

#### 1. Method + Path

`POST /internal/agent/tools/execute/{tool_name}`

#### 2. 목적

승인된 Agent Tool 변경 작업을 실행합니다.

#### 3. Auth 필요 여부

- 필요
- `X-Agent-Service-Token`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `tool_name` | `string` | 예 | - |
| header | `X-Agent-Service-Token` | `string` | 예 (인증 계층 검증) | - |

- Content-Type: `application/json` (`AgentToolExecuteRequest`)

```json
{
  "arguments": {
  },
  "idempotency_key": "string",
  "operation_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "operation_id": "string",
  "plan_id": "string",
  "plan_version": 1,
  "run_id": "string",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*`

```json
{
}
```

#### 6. Error response

- HTTP `401`: Agent 서비스 인증 토큰 누락 또는 불일치

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/internal/agent/tools/execute/<value>" \
  -H 'X-Agent-Service-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"arguments":{},"idempotency_key":"<value>","operation_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","operation_id":"<value>","plan_id":"<value>","plan_version":1,"run_id":"<value>","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/agent/controller/AgentToolController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: execute`)
- 호출자: ai-svc — `pipeline/app/modules/agent_run/infrastructure/backend_tool_gateway.py:51`(조립), `:67`(전송). base URL은 `AGENT_BACKEND_URL`(기본 `http://document-svc:8080`, 같은 파일 `:92`)이며 `DOCUMENT_INTERNAL_BASE_URL`이 아니다
- 하위 호출: ai-svc `POST ${AGENT_STATUS_ENDPOINT}/tool-authorizations/execute`(`PipelineAgentToolAuthorizationClient`), `POST .../artifacts/list|resolve`(`PipelineAgentArtifactClient`)
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-post-internal-agent-tools-execute-tool-name)

</details>

<a id="summary-post-internal-agent-tools-read-tool-name"></a>
### `POST /internal/agent/tools/read/{tool_name}`

| 항목 | 내용 |
|---|---|
| 목적 | 승인된 Agent Tool 읽기 작업을 실행합니다. |
| 입력 | **Path** — `tool_name`: `string`<br>**Header** — `X-Agent-Service-Token`(필수, 인증 계층 검증): `string`<br>**Body** — `AgentToolReadRequest` |
| 출력 | `200` 성공 — `object` |
| 조건 | 인증 필요<br>`X-Agent-Service-Token`을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `401` Agent 서비스 인증 토큰 누락 또는 불일치 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-internal-agent-tools-read-tool-name"></a>
### `POST /internal/agent/tools/read/{tool_name}` 상세

#### 1. Method + Path

`POST /internal/agent/tools/read/{tool_name}`

#### 2. 목적

승인된 Agent Tool 읽기 작업을 실행합니다.

#### 3. Auth 필요 여부

- 필요
- `X-Agent-Service-Token`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `tool_name` | `string` | 예 | - |
| header | `X-Agent-Service-Token` | `string` | 예 (인증 계층 검증) | - |

- Content-Type: `application/json` (`AgentToolReadRequest`)

```json
{
  "arguments": {
  },
  "run_id": "string",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*`

```json
{
}
```

#### 6. Error response

- HTTP `401`: Agent 서비스 인증 토큰 누락 또는 불일치

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/internal/agent/tools/read/<value>" \
  -H 'X-Agent-Service-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"arguments":{},"run_id":"<value>","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/agent/controller/AgentToolController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: read`)
- 호출자: ai-svc — `pipeline/app/modules/agent_run/infrastructure/backend_tool_gateway.py:27`
- 하위 호출: ai-svc `POST ${AGENT_STATUS_ENDPOINT}/tool-authorizations/read`(`PipelineAgentToolAuthorizationClient`), `POST .../artifacts/list|resolve`(`PipelineAgentArtifactClient`)
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-post-internal-agent-tools-read-tool-name)

</details>

## 본인 모델 사용량 조회

`GET /api/workspaces/{workspace_id}/usage/models?from_at=...&to_at=...`

로그인 사용자의 workspace 멤버 권한을 확인한 뒤 AI 내부 `/usage/models`에서 집계를 조회한다. 다른 사용자의 ID를 지정할 수 없다. 기간은 시간대 포함 ISO 8601, 시작 포함·종료 제외이며 생략 시 UTC 이번 달이다. 모델별 입력·출력·캐시·추론 토큰과 실패·미확인 호출 수를 반환한다. 캐시와 추론은 각각 입력과 출력의 부분 집합이므로 중복 합산하지 않는다. 금액 환산은 백엔드 책임이며 현재 응답은 사용량이다. 잘못된 기간 400, 권한 없음 404, AI 장애·빈 응답 503.

- 호출자: 없음 — 프론트엔드 `apiFetch` 호출 지점에 이 경로가 없고, ai-svc·access-svc도 이 서비스의 `/api/**`를 호출하지 않는다
- 하위 호출: ai-svc `GET ${MODEL_USAGE_ENDPOINT}?workspace_id&user_id[&from_at&to_at]` (`src/main/java/fruition/core/usage/service/ModelUsageService.java`, `X-Internal-Token`)
- 진입점: `src/main/java/fruition/core/usage/controller/ModelUsageController.java`
- 배선 상태: **미배선 — 호출자 없음**

## AI 사용량 정산 (OWNER)

| Method + Path | 동작 |
|---|---|
| `GET /api/workspaces/{workspace_id}/usage/settlement?from_at=...&to_at=...` | 미리보기. 계산만 하고 저장하지 않는다. |
| `POST /api/workspaces/{workspace_id}/usage/settlements` (`{"from_at","to_at"}`) | 마감. 계산 결과를 저장한다. 같은 기간을 다시 마감하면 저장된 결과를 돌려준다. |
| `GET /api/workspaces/{workspace_id}/usage/settlements` | 마감한 정산 목록(최근 기간부터). |

- 권한: OWNER만. 비멤버 404, MEMBER 403.
- 대상: 기간 `[from_at, to_at)`에 멤버였던 사용자 전원. 탈퇴·제거된 사용자도 access 멤버십 이력(`GET /internal/workspaces/{id}/member-users`)으로 포함한다. 사용량이 없는 사용자는 응답에 넣지 않는다.
- 단가: `ai_model_prices`의 실제 응답 모델(`provider`, `model`)별 USD / 1M tokens. 기간 중 단가가 바뀌면 그 시점으로 기간을 나눠 사용자 × 구간마다 AI `/usage/models`를 조회하고 구간마다 그때 단가를 곱한다.
- 금액: (입력 − 캐시 읽기 − 캐시 생성) × 입력 단가 + 캐시 읽기 × 캐시 읽기 단가 + 캐시 생성 × 캐시 생성 단가 + 출력 × 출력 단가. 입력은 캐시를 포함한 합계이고, reasoning은 출력에 포함돼 따로 과금하지 않는다.
- 단가가 없는 모델: `amount_usd`가 `null`, `price_missing`이 `true`이며 합계(`total_usd`, 사용자 `amount_usd`)에서 빠진다. 사용량 미확인(`unknown_usage_calls`)·미완료(`unfinished_calls`) 호출은 건수로만 표시한다.
- 마감 후 단가표를 바꿔도 저장된 금액은 바뀌지 않는다. 마감한 기간과 겹치는 기간을 마감하면 409.
- 오류: 시작 ≥ 종료 또는 366일 초과 400, access·AI 장애 503.

```json
{
  "workspace_id": "ws_1", "from_at": "2026-09-01T00:00:00Z", "to_at": "2026-10-01T00:00:00Z",
  "currency": "USD", "total_usd": 3.000000, "price_missing": false,
  "users": [{
    "user_id": "user_1", "amount_usd": 3.000000, "price_missing": false,
    "models": [{"provider": "openai", "model": "gpt-6-luna", "calls": 12, "unknown_usage_calls": 0,
      "unfinished_calls": 0, "input_tokens": 2000000, "cached_input_tokens": 0, "cache_creation_tokens": 0,
      "output_tokens": 0, "reasoning_tokens": 0, "amount_usd": 3.000000, "price_missing": false}]
  }],
  "closed_at": null, "closed_by": null
}
```

- 진입점: `src/main/java/fruition/core/usage/controller/ModelUsageController.java`, `src/main/java/fruition/core/usage/service/UsageSettlementService.java`

## 본인 AI 사용 금액 (호출 단위 청구)

`GET /api/users/me/usage/charges?from_at=...&to_at=...`

로그인 사용자 본인의 호출 단위 청구(`usage_charges`)를 워크스페이스를 가로질러 합쳐 모델별로 돌려준다. 청구 단위는 실행을 시작한 사용자다. 기간은 호출 시작 시각 기준 `[from_at, to_at)`, 둘 다 필수이며 366일 이하다. 금액은 부가세 포함 milli-KRW 정수(`charge_krw_milli`, 1000 = 1원)다. 시작 ≥ 종료·366일 초과 400.

```json
{
  "user_id": "user_1", "from_at": "2026-09-01T00:00:00Z", "to_at": "2026-10-01T00:00:00Z",
  "currency": "KRW", "charge_krw_milli": 3334, "unpriced_calls": 0, "needs_review_calls": 1,
  "models": [{"provider": "openai", "model": "gpt-6-luna", "calls": 2, "input_tokens": 1000,
    "cached_input_tokens": 400, "cache_creation_tokens": 100, "output_tokens": 500, "reasoning_tokens": 300,
    "audio_seconds": 0, "tts_characters": 0, "charge_krw_milli": 3334, "unpriced_calls": 0, "needs_review_calls": 1}]
}
```

- 단가: `ai_model_prices`(모델별 토큰 USD / 1M, 오디오 USD / 분, TTS USD / 1M 글자), 환율 `fx_rates`(USD→KRW 고정), 정책 `pricing_policies`(마진·부가세 basis point). 모두 수정하지 않고 `effective_from`이 다른 새 행을 넣으며, 호출 `started_at`에 적용 중인 행을 고른다. 이슈 #78의 단위별 단가 행 대신 정산(#59)이 쓰는 `ai_model_prices`에 오디오·TTS 열을 더해 두 계산이 같은 단가표를 쓴다.
- 단가 조회 순서(#99): 실제 응답 모델(`model`)과 정확히 같은 행 → 요청 모델(`requested_model`) 행 → 없으면 `unpriced`. 단가를 찾은 이름은 `usage_charges.price_model`에 남긴다. OpenAI 응답 모델은 `gpt-5-nano-2025-08-07` 같은 스냅샷 이름이라 대개 요청 모델 단가로 계산된다.
- 모델 전환 분류(`usage_charges.model_routing`): 응답 모델이 요청 모델과 같으면 `same`, 요청 모델 뒤에 스냅샷·버전 접미사(`-YYYY-MM-DD`, `-YYYYMMDD`, `-NNN`, `-latest`)만 붙었으면 `snapshot`, 그 밖은 공급사가 다른 모델로 보낸 `routed`다. `routed`면 `[AI 모델 전환 감지] provider=… requested=… actual=…`, 단가를 끝내 못 찾으면 `[AI 단가 미등록] provider=… model=… requested=…`를 WARN으로 남긴다. 같은 조합은 프로세스·UTC 날짜마다 한 번만 남긴다. 운영 알람(CloudWatch metric filter)이 이 접두사를 쓰므로 바꾸지 않는다.
- 단가 시드: V70이 `AiModelCatalog`의 모든 모델에 공급사 공식 단가를 `effective_from = 2026-01-01T00:00:00Z`로 넣는다(`ON CONFLICT DO NOTHING`). 조회는 `started_at` 이전 행 중 가장 늦은 행이라 운영자가 더 늦은 시각으로 넣은 행이 그 뒤 호출에 우선한다. 카탈로그 모델마다 지금 적용 중인 단가가 있는지 `AiModelCatalogPriceIntegrationTest`가 확인한다. 공급사 단가·폐기 일정 변화는 주간 워크플로(`.github/workflows/ai-price-drift.yml`, `scripts/ai_price_drift.py`)가 LiteLLM 단가표·공급사 폐기 페이지와 비교해 이슈 `chore: AI 모델 단가·폐기 일정 주간 점검 결과`로 알린다.
- 원가(토큰 우선, #93): 입력·출력 토큰 중 하나라도 있으면 (입력 − 캐시 읽기 − 캐시 생성) × 입력 단가 + 캐시 읽기 × 캐시 읽기 단가 + 캐시 생성 × 캐시 생성 단가 + 출력 × 출력 단가만 계산한다. ai는 실시간 전사·TTS에 토큰과 `audio_seconds`·`input_characters`를 함께 남기므로, 이때 오디오 길이·글자 수는 저장만 하고 과금하지 않는다(이중 청구 방지). 토큰이 없을 때만 오디오 분당·TTS 100만 글자당 단가로 계산하며, 그 단가가 없으면 `unpriced`다. 입력은 캐시를 포함한 합계이고 reasoning은 출력에 포함돼 따로 과금하지 않는다(`UsagePricing`, 정산과 같은 식). `cost_usd_micro`는 micro-USD로 올림한다.
- 사용자 금액: 원가 × 환율 × (1 + 마진) × (1 + 부가세)를 milli-KRW로 올림한다. 올림 전 원가로 한 번에 계산한다.
- 상태: `charged` 금액 확정. `unpriced` 단가·환율·정책이 없어 금액을 비워 두고 합계에서 뺀다(0원으로 넘기지 않음, 경고 로그). `needs_review` 0원이며 경고 로그를 남긴다. 대상은 `succeeded`가 아닌 호출(`unknown`·`failed`·`abandoned`)과, `succeeded`지만 입력·출력 토큰·`audio_seconds`·`input_characters`를 모두 모르는 호출(공급사가 usage를 주지 않음, #98)이다. AI에서 아직 `started`인 호출은 청구 행을 만들지 않는다.
- 같은 호출(AI 원장 `id` = `call_id` UNIQUE)은 여러 번 수집해도 한 행이다. `charged` 행은 다시 계산하지 않아 단가가 바뀌어도 이전 청구가 변하지 않는다. `unpriced`·`needs_review` 행은 다시 수집하거나 재계산 작업이 갱신한다.
- 재계산(#99): 매시간(`app.usage-charge.recompute-interval-ms`, 기본 1시간) 최근 90일의 `succeeded` 호출 중 `unpriced`·`needs_review` 행을 AI 원장을 다시 조회하지 않고 `usage_charges`에 저장된 사용량으로 같은 규칙에 따라 다시 계산한다. `charged` 행은 건드리지 않는다. 500행씩 `FOR UPDATE SKIP LOCKED`로 잠가 여러 Pod가 같은 행을 동시에 계산하지 않는다. 새로 `charged`가 된 행의 실행은 커밋 뒤 수집과 같은 크레딧 정산(`CreditService.settle`)으로 차감한다. V69 이전 행은 `requested_model`이 없어 응답 모델 단가로만 찾는다.
- 수집: 비동기 실행은 결과 반영(`beginResult`)과 같은 트랜잭션에, PDF 변환은 완료(`AiCommandOutboxWriter.complete`)나 재시도 소진 실패와 같은 트랜잭션에, 동기 호출은 호출이 끝난 뒤(실패 포함) run_id를 `usage_collect_queue`에 넣는다. 실패한 동기 호출과 재시도를 다 쓴 변환은 `ai_task_runs.status`를 `failed`로 닫는다. worker가 `FOR UPDATE SKIP LOCKED`로 하나씩 선점해 AI `GET ${MODEL_USAGE_CALLS_ENDPOINT}?run_id=`(`X-Internal-Token`)로 가져온다. 실패하면 시도 횟수의 제곱(분)만큼 늦춰 최대 10번 재시도한다. 대사 작업이 하루 주기로 커서(`usage_reconcile_cursor`)부터 1시간 전까지를 하루치씩 `?finished_from=&finished_to=`로 다시 조회해 빠진 호출(늦게 커밋됨·취소된 실행·수집 포기)을 채운다. 대사 트랜잭션에는 청구 행 기록과 커서 갱신만 두고, 크레딧 정산은 커밋한 뒤 실행별로 한다. 값을 해석할 수 없는 호출 1건(시각 형식 오류 등)은 오류 로그를 남기고 건너뛴다.
- 청구 사용자: 실행을 예약한 사용자(`ai_task_runs.user_id`)다. 실행 행이 없을 때만 AI 원장의 `user_id`를 쓰므로, AI가 사용자를 몰라 `unattributed`로 남긴 호출도 실행자에게 청구된다.
- 동기 AI 호출의 run_id: 실행 ID가 없는 회의록 초안·녹음 파일 전사·음성 전사·실시간 전사·Wiki 스키마 미리보기·초안은 호출마다 `ai_task_runs`에 실행(`kind:uuid`)을 등록하고, ai-svc 계약대로 **`X-Request-Id` 헤더**로 run_id를 보낸다. `workspace_id`·`user_id`는 기존처럼 본문 또는 query로 보낸다. run_id를 본문·query에 넣으면 ai-svc가 버리거나(`unattributed`로 기록) 정의되지 않은 필드로 보고 422로 거부한다. Skill은 기존 run_id를 쓴다.
- 회의록 초안(`POST /meeting-notes/preview`)과 Wiki 스키마 미리보기·초안(`POST /wiki-schema/preview`, `/wiki-schema/drafts`) 요청 본문에는 워크스페이스에 설정된 AI 모델(`WorkspaceAiModelClient.get(workspaceId)`)의 `provider`·`model` 문자열을 항상 넣는다. ai-svc는 둘이 없으면 422로 거부한다.
- AI 응답 계약(FruitionKR/Fruition-ai#60): `{"calls": [{"id", "run_id", "workspace_id", "user_id", "kind", "provider", "requested_model", "model", "status", "input_tokens", "cached_input_tokens", "cache_creation_tokens", "output_tokens", "reasoning_tokens", "audio_seconds", "input_characters", "started_at", "finished_at"}]}`. 응답 모델이 없으면 `model`에 `requested_model`을 남기고, `requested_model`은 따로 저장한다. AI의 `input_characters`(TTS 입력 문자 수)는 `usage_charges.tts_characters`에 저장한다.
- 대사: `scripts/sql/usage-cost-monthly.sql`로 월별 공급사·모델 원가 합계를 공급사 청구서와 비교한다.
- 진입점: `src/main/java/fruition/core/usage/controller/UsageChargeController.java`, `src/main/java/fruition/core/usage/service/UsageChargeService.java`

## 본인 크레딧 잔액 (선불)

`GET /api/users/me/credits`

로그인 사용자 본인의 선불 크레딧 잔액·예약액·가용액(잔액 − 예약)과 최근 원장 50건을 돌려준다. 금액은 milli-KRW 정수다. 실제 사용이 예약보다 크면 그대로 차감해 잔액이 음수일 수 있다.

```json
{
  "user_id": "user_1", "currency": "KRW",
  "balance_krw_milli": 600000, "reserved_krw_milli": 300000, "available_krw_milli": 300000,
  "entries": [{"type": "release", "amount_krw_milli": -300000, "run_id": "query-1", "reason": null,
    "created_at": "2026-10-09T03:00:00Z"}]
}
```

- 원장 `credit_entries`는 추가만 한다. 부호: `purchase`·`grant` +, `charge`·`refund`(결제 환불로 회수) −, `adjust` ±(잔액), `reserve` +, `release` −(예약). 계정 잔액 = 예약을 뺀 원장 합계, 예약 = `reserve`·`release` 합계이며 `scripts/sql/credit-balance-check.sql`로 점검한다.
- 사전 승인: AI 요청 전에 계정 행을 `FOR UPDATE`로 잠그고 kind별 예상 상한(`app.billing.estimate.<kind>`, 없으면 `app.billing.default-estimate-krw-milli`)을 `reserve`로 기록한다. 비동기 작업은 `AiCommandOutboxWriter.begin`·`reserve`(outbox 저장과 같은 트랜잭션), 동기 호출(Skill·회의록 초안·녹음 파일 전사·음성 전사·Wiki 스키마)은 `AiTaskCancellationService.register`, 실시간 전사는 연결 시작에서 예약한다. 같은 실행은 한 번만 예약하고, 사용자가 없는 시스템 작업은 예약하지 않는다.
- `app.billing.enforce`(기본 false): true면 가용 잔액이 상한보다 작거나 잔액이 음수일 때 AI 요청 전에 `402 INSUFFICIENT_CREDIT`("크레딧 잔액이 부족합니다. 크레딧을 충전한 뒤 다시 시도해 주세요.")로 거절하고 작업 등록도 롤백한다. 비동기 회의록 초안은 `failed`/`INSUFFICIENT_CREDIT`, 녹음 파일 전사는 실패 사유, 실시간 전사는 `insufficient_credit` 오류 후 close `1008`로 알린다. false면 거절하지 않고 예약·정산만 기록한다(무료 크레딧 정책 전 배포용).
- 정산: 사용 금액 수집(위 절)이 실행의 호출을 가져오면 그 실행의 청구 합계를 `charge`로 차감하고 남은 예약을 `release`한다. 실행이 아직 진행 중(`running`·`cancel_requested`·`rolling_back`)이면 차감만 하고 예약은 둔다. 차감 키는 `charge:{run_id}:{user_id}:{누적 청구액}`, 해제 키는 `release:{run_id}`라 같은 실행을 여러 번 정산해도 같은 금액은 한 번만 반영되고, 대사가 늦게 채운 호출은 늘어난 만큼만 더 차감한다. 취소된 실행도 취소 확정 시 수집 대기열에 넣어 정산한다. `needs_review` 호출은 현재 0원이다. 재계산으로 나중에 `charged`가 된 호출도 같은 정산 경로로 늘어난 만큼 차감한다.
- 종료 신호가 없는 실행의 예약은 `app.billing.stale-reservation-hours`(기본 48시간)가 지나면 정리 작업이 그때까지의 청구로 정산하고 푼다. 진행 중인 실행은 건너뛰되, `ai_task_runs.updated_at`이 그 시간보다 오래된 실행(프로세스 종료로 남은 `running` 등)은 끝난 것으로 본다.
- 실시간 전사는 연결 시작 시 `meeting_live` 상한을 예약하고, `app.billing.live-renew-seconds`(기본 60초)마다 그때까지의 호출을 수집해 차감한 뒤 남은 예약을 풀고 다음 구간 상한을 다시 예약한다(키 `release:{run_id}:{구간}`·`reserve:{run_id}:{구간}`). enforce에서 차감 뒤 가용 잔액이 상한보다 작으면 아무것도 반영하지 않고 `insufficient_credit` 오류 후 close `1008`로 연결을 닫으며, 연결 종료 정산이 사용량을 차감하고 예약을 푼다. 확정 구간은 남아 충전 뒤 새 ticket으로 이어서 녹음한다.
- 수동 지급·조정은 `scripts/sql/credit-grant-adjust.sql`(`grant` 또는 `adjust`, `adjust`는 사유 필수)로 한다.
- 보관: 탈퇴·워크스페이스 삭제 파기 대상에서 `credit_accounts`·`credit_entries`·`usage_charges`·`credit_orders`·`payment_events`는 제외한다(전자상거래법상 대금 결제 기록 5년 보관).
- 진입점: `src/main/java/fruition/core/usage/controller/UsageChargeController.java`, `src/main/java/fruition/core/usage/service/CreditService.java`

## 크레딧 충전·환불 (PG 결제)

결정과 PG·법률 검토 항목은 [ADR-0026](../adr/0026-credit-payments.md)에 있다. `app.billing.payments-enabled`(기본 false)가 false면 아래 API는 모두 404다.

| Method + Path | 동작 |
|---|---|
| `POST /api/users/me/credit-orders` (`{"product_code"}`) | 주문 생성. 금액·크레딧은 서버 상품표(`app.billing.product.<code>.amount-krw`·`.credit-krw-milli`)로 정한다. 없는 상품 400. |
| `POST /api/users/me/credit-orders/{order_id}/confirm` (`{"payment_key", "amount"}`) | 서버가 PG 승인 API를 부르고 주문·금액·상태를 대조한 뒤 `purchase`를 지급한다. 이미 지급한 주문은 PG를 다시 부르지 않고 그대로 돌려준다. 앞선 승인 응답을 받지 못해 PG가 이미 처리한 결제라고 거절하면(`ALREADY_PROCESSED_PAYMENT`) PG 결제 조회로 주문·금액·상태를 대조해 지급한다. 금액 불일치 400(PG 미호출), PG 거절 400(주문 `failed`), PG 응답 없음·불일치 502. |
| `POST /api/users/me/credit-orders/{order_id}/refund` | 주문의 남은 크레딧과 가용 잔액 중 작은 값만 환불한다. 금액은 그 비율(원 단위 내림)이며 PG 취소 성공 후 `refund`(−)를 쓴다. 취소 전에 PG 결제를 조회해, 주문에 기록하지 않은 취소(취소 뒤 회수 전 실패·응답 유실)가 있으면 새로 취소하지 않고 그 금액만큼 회수만 한다. 상태는 `refunded` 또는 `partially_refunded`. 환불할 것이 없으면 409(PG 미호출), PG 거절 409, 응답 없음 502. |
| `POST /internal/payments/webhook` | 헤더 `X-Payment-Signature` = 본문 HMAC-SHA256 hex(`app.billing.pg.webhook-secret`). 틀리면 401. `eventId`(없으면 본문 SHA-256)로 한 번만 처리하고, `data.status = DONE`이면 승인 응답을 받지 못한 주문을 지급한다. `CANCELED`·`PARTIAL_CANCELED`(관리자 콘솔 취소 등)면 PG 결제를 조회해 주문에 기록하지 않은 취소 금액만큼 주문 환불 누적과 `refund`(−)를 기록한다(전액 취소면 남은 크레딧 전부, 부분 취소면 금액 비율). 조회가 실패하면 처리 기록도 롤백돼 PG 재전송 때 다시 처리한다. |

```json
{"order_id": "order_3f2c...", "product_code": "CREDIT_10000", "amount_krw": 10000, "credit_krw_milli": 10000000,
 "status": "paid", "refunded_krw": 0, "refunded_credit_krw_milli": 0,
 "created_at": "2026-10-09T03:00:00Z", "paid_at": "2026-10-09T03:01:00Z"}
```

- 한 번만 지급: 주문 상태 조건부 갱신(`created`/`failed` → `paid`)과 원장 키 `purchase:{order_id}`를 같은 트랜잭션에서 쓴다. 승인 재시도·webhook 중복·둘의 경합에도 지급은 한 번이다.
- 환불 회수는 PG 결제의 취소 누적(`totalAmount − balanceAmount`)과 주문의 `refunded_krw` 차이만 반영하고, 원장 키 `refund:{order_id}:{그때까지 환불 크레딧}`이 같아 재시도·webhook 중복에도 한 번만 회수한다. 가용 잔액보다 많이 회수하면 잔액이 음수가 될 수 있다.
- PG 연동은 `PaymentGatewayClient` 하나(승인·조회·취소)다. 요청은 토스페이먼츠 형식 기준이며 PG 선정 후 맞춘다. 설정: `app.billing.pg.confirm-endpoint`, `app.billing.pg.payment-endpoint`·`app.billing.pg.cancel-endpoint`(`{paymentKey}` 치환), `app.billing.pg.secret-key`(Basic 인증), `app.billing.pg.webhook-secret`.
- 진입점: `src/main/java/fruition/core/billing/CreditOrderController.java`, `src/main/java/fruition/core/billing/CreditOrderService.java`
