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
  "model": "gpt-5-nano",
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
  --data '{"session_id":"session_0ff8564ea24047cd8144d3f48badfe3f","documentId":"doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83","baseVersion":3,"message":"이 문단을 표로 정리해줘","conversationContext":{"selected_pair_ids":["pair_01"],"referenceContext":{}},"editorSnapshot":{"markdown":"# 회의록\n\n정리할 문단","target":{"endLine":3,"startLine":3,"type":"selection"}},"provider":"openai","model":"gpt-5-nano"}'
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

[↑ 요약으로 돌아가기](#summary-post-internal-agent-tools-read-tool-name)

</details>
