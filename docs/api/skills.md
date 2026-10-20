# Skills API

[서비스 문서](../README.md) / [document-svc](README.md)

Skill 작성·게시·설정 Gateway와 내부 참조 문서 읽기 API다. 공개 요청은 Backend가 권한과
workspace AI 모델을 확인한 뒤 ai-svc 내부 HTTP로 전달한다.

- API 수: 10

작성·게시·수정 요청은 선택 query parameter `run_id`를 받고 응답에도 `run_id`를 반환한다.
호출자가 ID를 미리 지정하면 HTTP 응답을 기다리는 중에도 [공통 취소 API](tasks.md)를
호출할 수 있다. Backend는 `/skills/tasks` 내부 API에 원래 body와 검증한 actor·모델을 전달하며,
취소는 생성한 Skill 삭제 또는 이전 게시 버전·설정 복구까지 확인한다.
응답 대기가 `app.skill.timeout-seconds`(기본 60초)를 넘기면 core가 같은 run에 취소를 요청해 AI가 변경을 복구하고, 호출자에는 `503 SKILL_AI_UNAVAILABLE`을 반환한다. AI도 55초에 자체 중단한다.

## API 목차

| API | 목적 |
|---|---|
| [`GET /api/workspaces/{workspace_id}/skills`](#summary-get-api-workspaces-workspace-id-skills) | 현재 사용자가 사용할 수 있는 워크스페이스 Skill 목록을 반환합니다. |
| [`POST /api/workspaces/{workspace_id}/skills/author`](#summary-post-api-workspaces-workspace-id-skills-author) | 자연어 요구를 바탕으로 저장 전 Skill 초안을 생성합니다. |
| [`POST /api/workspaces/{workspace_id}/skills/author/publish`](#summary-post-api-workspaces-workspace-id-skills-author-publish) | 작성된 Skill 정의를 검토 후 게시합니다. |
| [`POST /api/workspaces/{workspace_id}/skills/test-run`](#summary-post-api-workspaces-workspace-id-skills-test-run) | 게시 전 Skill 정의를 문서 하나에 저장하지 않고 적용해 봅니다. |
| [`GET /api/workspaces/{workspace_id}/skills/{skill_id}`](#summary-get-api-workspaces-workspace-id-skills-skill-id) | Skill의 현재 정의와 실행 설정을 반환합니다. |
| [`PATCH /api/workspaces/{workspace_id}/skills/{skill_id}`](#summary-patch-api-workspaces-workspace-id-skills-skill-id) | Skill의 정의를 수정합니다. |
| [`DELETE /api/workspaces/{workspace_id}/skills/{skill_id}`](#delete-skill) | Skill과 버전을 삭제하고 실행 기록을 보존합니다. |
| [`POST /api/workspaces/{workspace_id}/skills/{skill_id}/disable`](#summary-post-api-workspaces-workspace-id-skills-skill-id-disable) | Skill을 Agent 실행 대상에서 제외합니다. |
| [`POST /api/workspaces/{workspace_id}/skills/{skill_id}/enable`](#summary-post-api-workspaces-workspace-id-skills-skill-id-enable) | Skill을 Agent 실행 대상에 포함합니다. |
| [`POST /internal/agent/skill-authoring/references/read`](#summary-post-internal-agent-skill-authoring-references-read) | Skill 작성에 사용할 참조 문서의 범위와 권한을 검증한 뒤 본문을 반환합니다. |

## 한눈에 보기

<a id="summary-get-api-workspaces-workspace-id-skills"></a>
### `GET /api/workspaces/{workspace_id}/skills`

| 항목 | 내용 |
|---|---|
| 목적 | 현재 사용자가 사용할 수 있는 워크스페이스 Skill 목록을 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string` |
| 출력 | `200` 목록 조회 성공 — 배열<`SkillResponse`> |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` Skill 요청 충돌 — `ErrorResponse`<br>`410` Skill이 더 이상 유효하지 않음 — `ErrorResponse`<br>`503` llmPipeline 사용 불가 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-skills"></a>
### `GET /api/workspaces/{workspace_id}/skills` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/skills`

#### 2. 목적

현재 사용자가 사용할 수 있는 워크스페이스 Skill 목록을 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 목록 조회 성공
- Content-Type: `*/*`

```json
[
  {
    "enabled_version": {
      "allowed_tools": [
        "string"
      ],
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "instructions_markdown": "string",
      "lint_result": {
      },
      "name": "meeting-notes",
      "result_mode": "auto",
      "status": "published",
      "targets": [
        "current_document"
      ],
      "version": 3
    },
    "id": "string",
    "latest_version": {
      "allowed_tools": [
        "string"
      ],
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "instructions_markdown": "string",
      "lint_result": {
      },
      "name": "meeting-notes",
      "result_mode": "auto",
      "status": "published",
      "targets": [
        "current_document"
      ],
      "version": 3
    },
    "owner_user_id": "string",
    "scope_type": "personal",
    "slug": "meeting-notes",
    "status": "published",
    "workspace_id": "string"
  }
]
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Skill 요청 충돌 | `ErrorResponse` |
| `410` | Skill이 더 이상 유효하지 않음 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

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
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/skills" \
  -H 'Authorization: Bearer <access_token>'
```

```json
[
  {
    "enabled_version": {
      "allowed_tools": [
        "string"
      ],
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "instructions_markdown": "string",
      "lint_result": {
      },
      "name": "meeting-notes",
      "result_mode": "auto",
      "status": "published",
      "targets": [
        "current_document"
      ],
      "version": 3
    },
    "id": "string",
    "latest_version": {
      "allowed_tools": [
        "string"
      ],
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "instructions_markdown": "string",
      "lint_result": {
      },
      "name": "meeting-notes",
      "result_mode": "auto",
      "status": "published",
      "targets": [
        "current_document"
      ],
      "version": 3
    },
    "owner_user_id": "string",
    "scope_type": "personal",
    "slug": "meeting-notes",
    "status": "published",
    "workspace_id": "string"
  }
]
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/skill/controller/SkillController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: list_2`)
- 호출자: 프론트엔드 — `src/entities/skill/api/skill.ts:11`(`fetchSkills`)
- 하위 호출: ai-svc `GET ${SKILL_ENDPOINT}?workspace_id&user_id` (`PipelineSkillRequester`, `X-Agent-Service-Token`)
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-skills)

</details>

<a id="summary-post-api-workspaces-workspace-id-skills-author"></a>
### `POST /api/workspaces/{workspace_id}/skills/author`

| 항목 | 내용 |
|---|---|
| 목적 | 자연어 요구를 바탕으로 저장 전 Skill 초안을 생성합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Body** — `SkillAuthoringRequest` |
| 출력 | `200` 초안 작성 성공 — `allowed_tools`, `capabilities`, `description`, `instructions_markdown`, `issues`, `name`, `question`, `scope_type`, … |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 요청 — `ErrorResponse`<br>`403` 팀 Skill인데 `skill.author` 권한이 없음(`WORKSPACE_PERMISSION_REQUIRED`) — `ErrorResponse`<br>`404` 워크스페이스 또는 참조 문서를 찾을 수 없음 — `ErrorResponse`<br>`409` Skill 이름 또는 버전 충돌 — `ErrorResponse`<br>`410` Skill이 더 이상 유효하지 않음 — `ErrorResponse`<br>`413` 참조 문서 또는 요청 본문이 너무 큼 — `JsonNode` / `ErrorResponse`<br>`422` Skill 요청 검증 실패 — `ErrorResponse`<br>`503` llmPipeline 사용 불가 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-skills-author"></a>
### `POST /api/workspaces/{workspace_id}/skills/author` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/skills/author`

#### 2. 목적

자연어 요구를 바탕으로 저장 전 Skill 초안을 생성합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Content-Type: `application/json` (`SkillAuthoringRequest`)

```json
{
  "authoring_mode": "enhance",
  "description": "string",
  "instruction": "string",
  "name": "meeting-notes",
  "reference_document_ids": [
    "string"
  ],
  "scope_type": "personal"
}
```

#### 5. Response body

- HTTP `200`: 초안 작성 성공
- Content-Type: `*/*`

```json
{
  "allowed_tools": [
    "list_root_items"
  ],
  "capabilities": [
    "document-create"
  ],
  "description": "string",
  "instructions_markdown": "string",
  "issues": [],
  "name": "string",
  "question": "string",
  "result_mode": "auto",
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "targets": [
    "current_document"
  ],
  "version_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | AI가 거절한 요청(아래 code 표) | `ErrorResponse` |
| `403` | 팀 Skill인데 `skill.author` 권한이 없음(`WORKSPACE_PERMISSION_REQUIRED`, OWNER는 항상 가능). 개인 Skill은 권한이 필요 없다 | `ErrorResponse` |
| `404` | 워크스페이스 또는 참조 문서를 찾을 수 없음 | `ErrorResponse` |
| `409` | Skill 이름 또는 버전 충돌 | `ErrorResponse` |
| `410` | Skill이 더 이상 유효하지 않음 | `ErrorResponse` |
| `413` | 참조 문서 또는 요청 본문이 너무 큼 | `없음` |
| `422` | Skill 요청 검증 실패 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

AI가 작성 요청을 거절하면 사유별 `code`로 `400`을 반환합니다. `message`는 화면에 그대로 보여 줄 수 있는 한국어 문장입니다.
AI가 사유 code를 주지 않거나 응답 형식이 다르면 `SKILL_REQUEST_REJECTED`로 반환합니다.
위험 표현은 오류가 아니라 `200` `status: "blocked"`와 `issues[]`로 반환합니다(변경 없음).

| code | 의미 | AI code |
|---|---|---|
| `SKILL_INTENT_AMBIGUOUS` | 수행할 작업을 특정할 수 없음(무의미한 입력 포함) | `intent_ambiguous` |
| `SKILL_INTENT_UNSUPPORTED` | 지원하지 않는 작업 | `intent_unsupported` |
| `SKILL_INSTRUCTION_INVALID` | 지침 길이, 이름 형식, 참조 문서 오류. `message`가 항목별로 다름 | `invalid_instruction_length`, `invalid_name`, `invalid_reference` |
| `SKILL_REQUEST_REJECTED` | 그 밖의 거절 | `skill_request_invalid`, 그 밖의 값 |
| `SKILL_AI_UNAVAILABLE` | AI 장애·타임아웃(`503`) | - |

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
- `scope_type=team`이면 `skill.author` 권한(OWNER는 항상 가능)이 필요하다. `personal`은 필요 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/skills/author" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"authoring_mode":"enhance","description":"<value>","instruction":"<value>","name":"meeting-notes","reference_document_ids":["<value>"],"scope_type":"personal"}'
```

```json
{
  "allowed_tools": [
    "list_root_items"
  ],
  "capabilities": [
    "document-create"
  ],
  "description": "string",
  "instructions_markdown": "string",
  "issues": [],
  "name": "string",
  "question": "string",
  "result_mode": "auto",
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "targets": [
    "current_document"
  ],
  "version_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/skill/controller/SkillController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: author`)
- 호출자: 프론트엔드 — `src/entities/skill/api/skill.ts:16`(`authorSkill`)
- 하위 호출: ai-svc `POST ${SKILL_ENDPOINT}/tasks` (`PipelineSkillRequester`), access-svc `WorkspaceAiModelClient`, `PipelineTaskCancellationClient`로 작업 등록
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-skills-author)

</details>

<a id="summary-post-api-workspaces-workspace-id-skills-author-publish"></a>
### `POST /api/workspaces/{workspace_id}/skills/author/publish`

| 항목 | 내용 |
|---|---|
| 목적 | 작성된 Skill 정의를 검토 후 게시합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Body** — `SkillPublishRequest` |
| 출력 | `200` 게시 성공 — `allowed_tools`, `capabilities`, `description`, `instructions_markdown`, `issues`, `name`, `question`, `scope_type`, … |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 Skill 정의(`SKILL_RESULT_MODE_INVALID`, `SKILL_TARGETS_INVALID` 등) — `ErrorResponse`<br>`403` 팀 Skill인데 `skill.author` 권한이 없음(`WORKSPACE_PERMISSION_REQUIRED`) — `ErrorResponse`<br>`404` 워크스페이스 또는 참조 문서를 찾을 수 없음 — `ErrorResponse`<br>`409` Skill 이름 또는 버전 충돌 — `ErrorResponse`<br>`410` Skill이 더 이상 유효하지 않음 — `ErrorResponse`<br>`413` 요청 본문이 너무 큼 — `JsonNode` / `ErrorResponse`<br>`422` Skill 요청 검증 실패 — `ErrorResponse`<br>`503` llmPipeline 사용 불가 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-skills-author-publish"></a>
### `POST /api/workspaces/{workspace_id}/skills/author/publish` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/skills/author/publish`

#### 2. 목적

작성된 Skill 정의를 검토 후 게시합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Content-Type: `application/json` (`SkillPublishRequest`)

```json
{
  "allowed_tools": [
    "list_root_items"
  ],
  "capabilities": [
    "document-create"
  ],
  "description": "string",
  "instructions_markdown": "string",
  "name": "meeting-notes",
  "scope_type": "personal"
}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `capabilities` | 예 | 1개 이상 |
| `allowed_tools` | 아니요 | 생략하거나 `[]`이면 Backend가 ai-svc payload에서 키를 빼고, ai-svc가 `capabilities`의 canonical Tool 집합으로 계산한다. 보내면 지금처럼 ai-svc가 검증만 한다. 응답 `allowed_tools`는 확정된 값이다. 빈 문자열 항목은 `400 INVALID_REQUEST`. |
| `targets` | 아니요 | 실행 대상 `current_document` \| `selection`, 최대 2개. 생략하면 키를 보내지 않고 ai-svc 기본값(`["current_document"]`)을 쓴다. 중복 검사는 ai-svc가 하며, 중복이거나 문서 Skill이 아닌데 보내면 `400 SKILL_TARGETS_INVALID`. |
| `result_mode` | 아니요 | 결과 방식 `create`(새 문서) \| `edit`(현재 문서 수정) \| `auto`(상황에 맞게). `capabilities`와 맞는지는 ai-svc가 검증해 어긋나면 `400 SKILL_RESULT_MODE_INVALID`. 생략하면 키를 보내지 않는다. |

> `targets`·`result_mode` 저장·검증은 ai-svc가 맡는다(FruitionKR/Fruition-ai#115). 그 전의 ai-svc는 모르는 필드를 `422`로 거절하므로, 두 필드는 ai-svc 배포 뒤에 보낸다. 생략한 요청은 배포 순서와 관계없이 지금과 같다.
>
> 배포 순서: 생략 계산은 ai-svc가 맡는다(FruitionKR/Fruition-ai#116). 그 전의 ai-svc는 `allowed_tools`를 필수로 받아 생략하면 `422 SKILL_REQUEST_REJECTED`가 되므로, 클라이언트는 ai-svc 배포를 확인한 뒤 필드를 뺀다. 값을 보내는 기존 요청은 배포 순서와 관계없이 그대로 동작한다.

#### 5. Response body

- HTTP `200`: 게시 성공
- Content-Type: `*/*`

```json
{
  "allowed_tools": [
    "list_root_items"
  ],
  "capabilities": [
    "document-create"
  ],
  "description": "string",
  "instructions_markdown": "string",
  "issues": [],
  "name": "string",
  "question": "string",
  "result_mode": "auto",
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "targets": [
    "current_document"
  ],
  "version_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 Skill 정의 | `ErrorResponse` |
| `403` | 팀 Skill인데 `skill.author` 권한이 없음(`WORKSPACE_PERMISSION_REQUIRED`, OWNER는 항상 가능). 개인 Skill은 권한이 필요 없다 | `ErrorResponse` |
| `404` | 워크스페이스 또는 참조 문서를 찾을 수 없음 | `ErrorResponse` |
| `409` | Skill 이름 또는 버전 충돌 | `ErrorResponse` |
| `410` | Skill이 더 이상 유효하지 않음 | `ErrorResponse` |
| `413` | 요청 본문이 너무 큼 | `없음` |
| `422` | Skill 요청 검증 실패 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

`400`은 사유별 `code`로 반환합니다. `message`는 화면에 그대로 보여 줄 수 있는 한국어 문장입니다(ai-svc 영문 문장은 내보내지 않음).

| code | 의미 | AI code |
|---|---|---|
| `SKILL_RESULT_MODE_INVALID` | `result_mode`가 `capabilities`와 맞지 않음(`create`는 `document-create`, `edit`는 `document-edit`, `auto`는 둘 다 필요, 문서 Skill이 아니면 지정 불가). 수정에서 `result_mode`를 생략하면 기존 값을 이어받으므로, 새 `capabilities`와 기존 값이 어긋나도 이 code가 난다 | `invalid_result_mode` |
| `SKILL_TARGETS_INVALID` | `targets` 중복이거나 문서 Skill(`document-create`/`document-edit`)이 아닌데 `targets` 지정 | `invalid_targets` |
| `SKILL_INTENT_AMBIGUOUS`, `SKILL_INTENT_UNSUPPORTED`, `SKILL_INSTRUCTION_INVALID` | 초안 작성(`POST /skills/author`)과 같음 | 같음 |
| `SKILL_REQUEST_REJECTED` | 그 밖의 거절. AI가 사유 code를 주지 않거나 응답 형식이 다를 때 포함 | `skill_request_invalid`, 그 밖의 값 |

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
- `scope_type=team`이면 `skill.author` 권한(OWNER는 항상 가능)이 필요하다. `personal`은 필요 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/skills/author/publish" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"capabilities":["document-create"],"description":"<value>","instructions_markdown":"<value>","name":"meeting-notes","scope_type":"personal"}'
```

```json
{
  "allowed_tools": [
    "list_root_items"
  ],
  "capabilities": [
    "document-create"
  ],
  "description": "string",
  "instructions_markdown": "string",
  "issues": [],
  "name": "string",
  "question": "string",
  "result_mode": "auto",
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "targets": [
    "current_document"
  ],
  "version_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/skill/controller/SkillController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: publish`)
- 호출자: 프론트엔드 — `src/entities/skill/api/skill.ts:25`(`publishSkill`)
- 하위 호출: ai-svc `POST ${SKILL_ENDPOINT}/tasks` (`PipelineSkillRequester`), access-svc `WorkspaceAiModelClient`
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-skills-author-publish)

</details>

<a id="summary-post-api-workspaces-workspace-id-skills-test-run"></a>
### `POST /api/workspaces/{workspace_id}/skills/test-run`

| 항목 | 내용 |
|---|---|
| 목적 | 게시 전 Skill 정의를 문서 하나에 저장하지 않고 적용해 섹션별 변경 전/후를 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Query** — `run_id`: `string`(선택)<br>**Body** — `SkillTestRunRequest` |
| 출력 | `200` — `SkillTestRunResponse` (`status`: `completed` \| `blocked`) |
| 조건 | 인증 필요<br>path의 `workspace_id` 활성 멤버십과 `document_id` 읽기 권한(같은 워크스페이스의 삭제되지 않은 문서)을 검증한다.<br>팀 Skill 게시 권한(`skill.author`)은 요구하지 않는다(저장하지 않음). |
| 주요 오류 | `400` 형식·Markdown 아닌 문서·적용 범위 오류(`INVALID_REQUEST`), `SKILL_TEST_RUN_UNSUPPORTED`<br>`402` `INSUFFICIENT_CREDIT`<br>`404` 워크스페이스 또는 문서 없음<br>`409` 이미 사용한 `run_id`<br>`413` 본문 5MB 초과<br>`503` `SKILL_AI_UNAVAILABLE`, `SKILL_TEST_RUN_UNAVAILABLE` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-skills-test-run"></a>
### `POST /api/workspaces/{workspace_id}/skills/test-run` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/skills/test-run?run_id=`

#### 2. 목적

Figma "스킬 만들기" 시트의 "테스트 실행" 카드용. 게시 전 정의(`name`, `description`, `instructions_markdown`, `capabilities`)를 문서 하나에 적용한 결과를 돌려준다. 안전 검토와 편집 생성은 ai-svc가 하고(FruitionKR/Fruition-ai#114), Backend는 권한·문서 본문·워크스페이스 모델을 확인해 ai-svc 내부 `POST /skills/test-run`에 전달한다.

- 문서·Skill·Agent run 어느 것도 만들거나 바꾸지 않는다. 문서 버전이 오르지 않고 이벤트도 발행하지 않는다.
- LLM을 호출하므로 게시와 같이 과금한다. 실행은 `ai_task_runs`에 `kind=skill_test_run`으로 등록돼 크레딧을 예약하고(`app.billing.enforce`면 최저 비용 미만일 때 `402`), ai-svc가 `X-Request-Id`(=`run_id`)로 기록한 사용량을 정산한다. 실행 기록에는 문서 본문 대신 `markdown_sha256`만 남긴다. 반복 테스트 횟수 제한은 두지 않는다.
- 타임아웃(`app.skill.timeout-seconds`)·취소는 다른 Skill 작업과 같다. `run_id`를 미리 정하면 [공통 취소 API](tasks.md)로 취소할 수 있다.
- 다른 Skill 작업과 달리 **`run_id`는 한 번만 쓸 수 있다.** ai-svc 테스트 실행은 `run_id`로 중복 실행을 막지 않으므로, 같은 `run_id` 재요청은 크레딧 사전 확인 없이 다시 실행되지 않게 `409`로 거절한다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| query | `run_id` | `string` | 아니요 | 생략하면 `skill_test_<uuid>`. `[a-zA-Z0-9:_-]{1,120}`. 한 번만 쓸 수 있다 |
| body | `name` | `string` | 예 | 게시와 같은 형식 |
| body | `description` | `string` | 예 | 500자 이하 |
| body | `instructions_markdown` | `string` | 예 | 30000자 이하 |
| body | `capabilities` | `string[]` | 예 | 1개 이상. Tool 허용 목록은 ai-svc가 계산한다 |
| body | `document_id` | `string` | 예 | 테스트할 문서 |
| body | `markdown` | `string` | 아니요 | 에디터의 저장 안 된 본문(UTF-8 5MB 이하). 생략하면 저장된 최신 본문 |
| body | `target` | `object` | 아니요 | `{type, start_line, end_line}`. `type`은 `selection` \| `current_section` \| `whole_document`, 1-based 줄(끝 포함). 생략하면 문서 전체. 줄 범위는 실제로 쓰는 본문 기준으로 검사한다 |

```json
{
  "name": "research-summary",
  "description": "연구 문서의 개요를 핵심 문장 3개로 정리합니다.",
  "instructions_markdown": "'연구 개요' 섹션만 고친다. 다른 섹션은 바꾸지 않는다.",
  "capabilities": ["document-edit"],
  "document_id": "doc_123",
  "target": { "type": "selection", "start_line": 10, "end_line": 24 }
}
```

ai-svc로는 다음을 보낸다(`X-Agent-Service-Token`, `X-Request-Id: <run_id>`). `base_version`은 저장된 본문의 편집 revision이다.

```json
{
  "workspace_id": "ws_123",
  "user_id": "user_123",
  "provider": "openai",
  "model": "gpt-6-luna",
  "definition": { "name": "research-summary", "description": "...", "instructions_markdown": "...", "capabilities": ["document-edit"] },
  "document": { "document_id": "doc_123", "base_version": 7, "markdown": "# 연구 개요\n..." },
  "target": { "type": "selection", "start_line": 10, "end_line": 24 }
}
```

#### 5. Response body

- HTTP `200`: ai-svc 응답에 `run_id`를 붙여 그대로 반환한다.

```json
{
  "run_id": "skill_test_3f2a",
  "status": "completed",
  "changes": [
    { "section": "연구 개요", "start_line": 1, "end_line": 6, "before": "...", "after": "..." }
  ],
  "untouched_sections": ["방법", "결과"],
  "result_markdown": "# 연구 개요\n...",
  "issues": []
}
```

- 안전 검토에서 막히면 실행하지 않고 `200 {"status": "blocked", "changes": [], "issues": [...]}`(`/author`의 `blocked`와 같은 issue 형식).
- 문서 생성형 capability(`document-create`, `template`)는 `changes` 없이 만들어질 문서를 `result_markdown`으로만 준다.

#### 6. Error response

| HTTP 상태 | code | 설명 |
|---|---|---|
| `400` | `INVALID_REQUEST` | 요청 형식, Markdown이 아닌 원본 문서, 문서 줄 수를 벗어난 `target` |
| `400` | `SKILL_TEST_RUN_UNSUPPORTED` | 테스트 실행을 지원하지 않는 capability(`folder-organize`). ai code `test_run_unsupported` |
| `400` | `SKILL_REQUEST_REJECTED` | 그 밖의 AI 거절 |
| `402` | `INSUFFICIENT_CREDIT` | 크레딧 부족 |
| `404` | `WORKSPACE_NOT_FOUND`, `DOCUMENT_NOT_FOUND` | 멤버가 아니거나 문서가 없음(다른 워크스페이스 문서 포함) |
| `409` | - | 이미 사용한 `run_id`. 테스트 실행은 `run_id`마다 한 번만 실행한다(재시도도 새 `run_id`) |
| `413` | `MARKDOWN_CONTENT_TOO_LARGE` | 쓰려는 본문(요청 `markdown` 또는 저장본)이 UTF-8 기준 5MB 초과 |
| `503` | `SKILL_AI_UNAVAILABLE` | AI 장애·타임아웃 |
| `503` | `SKILL_TEST_RUN_UNAVAILABLE` | ai-svc에 테스트 실행 API가 아직 없음(ai `404`). ai#114 배포 전 |

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id` 활성 멤버십을 검증한다. 멤버는 모든 문서를 읽을 수 있으므로(문서·폴더 `view` 설정은 쓰기만 막는다) `document_id`가 같은 워크스페이스의 삭제되지 않은 문서이면 읽기 권한이 있다.
- 저장하지 않으므로 `skill.author`는 요구하지 않는다. 팀 Skill 게시는 게시 API에서 따로 검사한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/skills/test-run?run_id=skill_test_3f2a" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"name":"research-summary","description":"<value>","instructions_markdown":"<value>","capabilities":["document-edit"],"document_id":"doc_123"}'
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/skill/controller/SkillController.java`
- 서비스: `src/main/java/fruition/core/skill/service/SkillTestRunService.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: testRun`)
- 하위 호출: ai-svc `POST ${SKILL_ENDPOINT}/test-run` (`PipelineSkillRequester.testRun`), access-svc `WorkspaceAiModelClient`, `AiTaskCancellationService`로 작업 등록
- 배선 상태: document 배선됨, ai-svc 미구현(FruitionKR/Fruition-ai#114)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-skills-test-run)

</details>

<a id="summary-get-api-workspaces-workspace-id-skills-skill-id"></a>
### `GET /api/workspaces/{workspace_id}/skills/{skill_id}`

| 항목 | 내용 |
|---|---|
| 목적 | Skill의 현재 정의와 실행 설정을 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `skill_id`: `string` |
| 출력 | `200` 상세 조회 성공 — `SkillResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` Skill 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` Skill 요청 충돌 — `ErrorResponse`<br>`410` Skill이 더 이상 유효하지 않음 — `ErrorResponse`<br>`503` llmPipeline 사용 불가 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-skills-skill-id"></a>
### `GET /api/workspaces/{workspace_id}/skills/{skill_id}` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/skills/{skill_id}`

#### 2. 목적

Skill의 현재 정의와 실행 설정을 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `skill_id` | `string` | 예 | 조회할 Skill ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 상세 조회 성공
- Content-Type: `*/*` (`SkillResponse`)

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "result_mode": "auto",
    "status": "published",
    "targets": [
      "current_document"
    ],
    "version": 3
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "result_mode": "auto",
    "status": "published",
    "targets": [
      "current_document"
    ],
    "version": 3
  },
  "owner_user_id": "string",
  "scope_type": "personal",
  "slug": "meeting-notes",
  "status": "published",
  "workspace_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | Skill 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Skill 요청 충돌 | `ErrorResponse` |
| `410` | Skill이 더 이상 유효하지 않음 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

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
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/skills/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "result_mode": "auto",
    "status": "published",
    "targets": [
      "current_document"
    ],
    "version": 3
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "result_mode": "auto",
    "status": "published",
    "targets": [
      "current_document"
    ],
    "version": 3
  },
  "owner_user_id": "string",
  "scope_type": "personal",
  "slug": "meeting-notes",
  "status": "published",
  "workspace_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/skill/controller/SkillController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: get_1`)
- 호출자: 프론트엔드 — `src/entities/skill/api/skill.ts` 상세 조회 경로
- 하위 호출: ai-svc `GET ${SKILL_ENDPOINT}/{skillId}` (`PipelineSkillRequester`)
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-skills-skill-id)

</details>

<a id="summary-patch-api-workspaces-workspace-id-skills-skill-id"></a>
### `PATCH /api/workspaces/{workspace_id}/skills/{skill_id}`

| 항목 | 내용 |
|---|---|
| 목적 | Skill의 정의를 수정합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `skill_id`: `string`<br>**Body** — `SkillUpdateRequest` |
| 출력 | `200` 수정 성공 — `description`, `instructions_markdown`, `issues`, `name`, `question`, `scope_type`, `skill_id`, `skill_markdown`, … |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 Skill 정의(`SKILL_RESULT_MODE_INVALID`, `SKILL_TARGETS_INVALID` 등) — `ErrorResponse`<br>`403` 대상이 팀 Skill인데 `skill.author` 권한이 없음(`WORKSPACE_PERMISSION_REQUIRED`) — `ErrorResponse`<br>`404` Skill 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` Skill 이름 또는 버전 충돌 — `ErrorResponse`<br>`410` Skill이 더 이상 유효하지 않음 — `ErrorResponse`<br>`413` 요청 본문이 너무 큼 — `JsonNode` / `ErrorResponse`<br>`422` Skill 요청 검증 실패 — `ErrorResponse`<br>`503` llmPipeline 사용 불가 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-patch-api-workspaces-workspace-id-skills-skill-id"></a>
### `PATCH /api/workspaces/{workspace_id}/skills/{skill_id}` 상세

#### 1. Method + Path

`PATCH /api/workspaces/{workspace_id}/skills/{skill_id}`

#### 2. 목적

Skill의 정의를 수정합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `skill_id` | `string` | 예 | 수정할 Skill ID |

- Content-Type: `application/json` (`SkillUpdateRequest`)

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "name": "meeting-notes",
  "result_mode": "auto",
  "targets": [
    "current_document"
  ]
}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `targets` | 아니요 | 게시와 같은 값·검증. 생략하면 키를 보내지 않아 ai-svc가 기존 값을 유지한다. |
| `result_mode` | 아니요 | 게시와 같은 값·검증. 생략하면 기존 값을 유지한다. |

두 필드는 ai-svc(FruitionKR/Fruition-ai#115) 배포 뒤에 보낸다. 응답·`GET /skills`·`GET /skills/{skill_id}`의 버전 객체에는 ai-svc가 저장한 `targets`·`result_mode`가 그대로 내려오며, ai-svc 배포 전에는 없다.

#### 5. Response body

- HTTP `200`: 수정 성공
- Content-Type: `*/*`

```json
{
  "allowed_tools": [],
  "capabilities": [],
  "description": "string",
  "instructions_markdown": "string",
  "issues": [],
  "name": "string",
  "question": "string",
  "result_mode": "auto",
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "targets": [
    "current_document"
  ],
  "version_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 Skill 정의 | `ErrorResponse` |
| `403` | 대상이 팀 Skill인데 `skill.author` 권한이 없음(`WORKSPACE_PERMISSION_REQUIRED`, OWNER는 항상 가능). 개인 Skill은 권한이 필요 없다 | `ErrorResponse` |
| `404` | Skill 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Skill 이름 또는 버전 충돌 | `ErrorResponse` |
| `410` | Skill이 더 이상 유효하지 않음 | `ErrorResponse` |
| `413` | 요청 본문이 너무 큼 | `없음` |
| `422` | Skill 요청 검증 실패 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

`400`은 사유별 `code`로 반환합니다. `message`는 화면에 그대로 보여 줄 수 있는 한국어 문장입니다(ai-svc 영문 문장은 내보내지 않음).

| code | 의미 | AI code |
|---|---|---|
| `SKILL_RESULT_MODE_INVALID` | `result_mode`가 `capabilities`와 맞지 않음(`create`는 `document-create`, `edit`는 `document-edit`, `auto`는 둘 다 필요, 문서 Skill이 아니면 지정 불가). 수정에서 `result_mode`를 생략하면 기존 값을 이어받으므로, 새 `capabilities`와 기존 값이 어긋나도 이 code가 난다 | `invalid_result_mode` |
| `SKILL_TARGETS_INVALID` | `targets` 중복이거나 문서 Skill(`document-create`/`document-edit`)이 아닌데 `targets` 지정 | `invalid_targets` |
| `SKILL_INTENT_AMBIGUOUS`, `SKILL_INTENT_UNSUPPORTED`, `SKILL_INSTRUCTION_INVALID` | 초안 작성(`POST /skills/author`)과 같음 | 같음 |
| `SKILL_REQUEST_REJECTED` | 그 밖의 거절. AI가 사유 code를 주지 않거나 응답 형식이 다를 때 포함 | `skill_request_invalid`, 그 밖의 값 |

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
- 대상 Skill이 팀 범위이면 `skill.author` 권한(OWNER는 항상 가능)이 필요하다. 범위는 Skill 조회로 확인하며 개인 Skill은 필요 없다.

#### 9. 예시 요청/응답

```bash
curl -X PATCH "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/skills/<value>" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"description":"<value>","instructions_markdown":"<value>","name":"meeting-notes"}'
```

```json
{
  "allowed_tools": [],
  "capabilities": [],
  "description": "string",
  "instructions_markdown": "string",
  "issues": [],
  "name": "string",
  "question": "string",
  "result_mode": "auto",
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "targets": [
    "current_document"
  ],
  "version_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/skill/controller/SkillController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: update_1`)
- 호출자: 프론트엔드 — `src/entities/skill/api/skill.ts:38`(`updateSkill`)
- 하위 호출: ai-svc `POST ${SKILL_ENDPOINT}/tasks` (`PipelineSkillRequester`)
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-patch-api-workspaces-workspace-id-skills-skill-id)

</details>

<a id="summary-post-api-workspaces-workspace-id-skills-skill-id-disable"></a>
### `POST /api/workspaces/{workspace_id}/skills/{skill_id}/disable`

| 항목 | 내용 |
|---|---|
| 목적 | Skill을 Agent 실행 대상에서 제외합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `skill_id`: `string` |
| 출력 | `200` 비활성화 성공 — `SkillResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `403` 대상이 팀 Skill인데 `skill.author` 권한이 없음(`WORKSPACE_PERMISSION_REQUIRED`) — `ErrorResponse`<br>`404` Skill 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` Skill 요청 충돌 — `ErrorResponse`<br>`410` Skill이 더 이상 유효하지 않음 — `ErrorResponse`<br>`503` llmPipeline 사용 불가 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-skills-skill-id-disable"></a>
### `POST /api/workspaces/{workspace_id}/skills/{skill_id}/disable` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/skills/{skill_id}/disable`

#### 2. 목적

Skill을 Agent 실행 대상에서 제외합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `skill_id` | `string` | 예 | 비활성화할 Skill ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 비활성화 성공
- Content-Type: `*/*` (`SkillResponse`)

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "result_mode": "auto",
    "status": "published",
    "targets": [
      "current_document"
    ],
    "version": 3
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "result_mode": "auto",
    "status": "published",
    "targets": [
      "current_document"
    ],
    "version": 3
  },
  "owner_user_id": "string",
  "scope_type": "personal",
  "slug": "meeting-notes",
  "status": "published",
  "workspace_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `403` | 대상이 팀 Skill인데 `skill.author` 권한이 없음(`WORKSPACE_PERMISSION_REQUIRED`, OWNER는 항상 가능). 개인 Skill은 권한이 필요 없다 | `ErrorResponse` |
| `404` | Skill 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Skill 요청 충돌 | `ErrorResponse` |
| `410` | Skill이 더 이상 유효하지 않음 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

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
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.
- 대상 Skill이 팀 범위이면 `skill.author` 권한(OWNER는 항상 가능)이 필요하다. 범위는 Skill 조회로 확인하며 개인 Skill은 필요 없다. 활성화·비활성화는 사용자별 설정이 아니라 Skill 자체의 상태를 바꿔 워크스페이스 전체에 적용되므로 수정과 같이 판정한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/skills/<value>/disable" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "result_mode": "auto",
    "status": "published",
    "targets": [
      "current_document"
    ],
    "version": 3
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "result_mode": "auto",
    "status": "published",
    "targets": [
      "current_document"
    ],
    "version": 3
  },
  "owner_user_id": "string",
  "scope_type": "personal",
  "slug": "meeting-notes",
  "status": "published",
  "workspace_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/skill/controller/SkillController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: disable`)
- 호출자: 프론트엔드 — `src/entities/skill/api/skill.ts:52`(`disableSkill`)
- 하위 호출: ai-svc `POST ${SKILL_ENDPOINT}/{skillId}/disable` (`PipelineSkillRequester`)
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-skills-skill-id-disable)

</details>

<a id="summary-post-api-workspaces-workspace-id-skills-skill-id-enable"></a>
### `POST /api/workspaces/{workspace_id}/skills/{skill_id}/enable`

| 항목 | 내용 |
|---|---|
| 목적 | Skill을 Agent 실행 대상에 포함합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `skill_id`: `string` |
| 출력 | `200` 활성화 성공 — `SkillResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `403` 대상이 팀 Skill인데 `skill.author` 권한이 없음(`WORKSPACE_PERMISSION_REQUIRED`) — `ErrorResponse`<br>`404` Skill 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` Skill 요청 충돌 — `ErrorResponse`<br>`410` Skill이 더 이상 유효하지 않음 — `ErrorResponse`<br>`503` llmPipeline 사용 불가 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-skills-skill-id-enable"></a>
### `POST /api/workspaces/{workspace_id}/skills/{skill_id}/enable` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/skills/{skill_id}/enable`

#### 2. 목적

Skill을 Agent 실행 대상에 포함합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `skill_id` | `string` | 예 | 활성화할 Skill ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 활성화 성공
- Content-Type: `*/*` (`SkillResponse`)

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "result_mode": "auto",
    "status": "published",
    "targets": [
      "current_document"
    ],
    "version": 3
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "result_mode": "auto",
    "status": "published",
    "targets": [
      "current_document"
    ],
    "version": 3
  },
  "owner_user_id": "string",
  "scope_type": "personal",
  "slug": "meeting-notes",
  "status": "published",
  "workspace_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `403` | 대상이 팀 Skill인데 `skill.author` 권한이 없음(`WORKSPACE_PERMISSION_REQUIRED`, OWNER는 항상 가능). 개인 Skill은 권한이 필요 없다 | `ErrorResponse` |
| `404` | Skill 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Skill 요청 충돌 | `ErrorResponse` |
| `410` | Skill이 더 이상 유효하지 않음 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

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
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.
- 대상 Skill이 팀 범위이면 `skill.author` 권한(OWNER는 항상 가능)이 필요하다. 범위는 Skill 조회로 확인하며 개인 Skill은 필요 없다. 활성화·비활성화는 사용자별 설정이 아니라 Skill 자체의 상태를 바꿔 워크스페이스 전체에 적용되므로 수정과 같이 판정한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/skills/<value>/enable" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "result_mode": "auto",
    "status": "published",
    "targets": [
      "current_document"
    ],
    "version": 3
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "result_mode": "auto",
    "status": "published",
    "targets": [
      "current_document"
    ],
    "version": 3
  },
  "owner_user_id": "string",
  "scope_type": "personal",
  "slug": "meeting-notes",
  "status": "published",
  "workspace_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/skill/controller/SkillController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: enable`)
- 호출자: 프론트엔드 — `src/entities/skill/api/skill.ts:47`(`enableSkill`)
- 하위 호출: ai-svc `POST ${SKILL_ENDPOINT}/{skillId}/enable` (`PipelineSkillRequester`)
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-skills-skill-id-enable)

</details>

<a id="summary-post-internal-agent-skill-authoring-references-read"></a>
### `POST /internal/agent/skill-authoring/references/read`

| 항목 | 내용 |
|---|---|
| 목적 | Skill 작성에 사용할 참조 문서의 범위와 권한을 검증한 뒤 본문을 반환합니다. |
| 입력 | **Header** — `X-Agent-Service-Token`(필수, 인증 계층 검증): `string`<br>**Body** — `SkillReferenceReadRequest` |
| 출력 | `200` 성공 — `SkillReferenceReadResponse` |
| 조건 | 인증 필요<br>`X-Agent-Service-Token`을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `401` Agent 서비스 인증 토큰 누락 또는 불일치 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-internal-agent-skill-authoring-references-read"></a>
### `POST /internal/agent/skill-authoring/references/read` 상세

#### 1. Method + Path

`POST /internal/agent/skill-authoring/references/read`

#### 2. 목적

Skill 작성에 사용할 참조 문서의 범위와 권한을 검증한 뒤 본문을 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `X-Agent-Service-Token`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Agent-Service-Token` | `string` | 예 (인증 계층 검증) | - |

- Content-Type: `application/json` (`SkillReferenceReadRequest`)

```json
{
  "document_id": "string",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*` (`SkillReferenceReadResponse`)

```json
{
  "document_role": "string",
  "markdown": "string"
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
curl -X POST "$DOCUMENT/internal/agent/skill-authoring/references/read" \
  -H 'X-Agent-Service-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"document_id":"<value>","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "document_role": "string",
  "markdown": "string"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: read_1`)
- 호출자: ai-svc — `pipeline/app/modules/skill/infrastructure/backend_skill_reference_reader.py:27`(base URL `DOCUMENT_INTERNAL_BASE_URL`, 헤더 `X-Agent-Service-Token`, 조립 `build_skill_reference_reader()` 같은 파일 `:73`)
- 하위 호출: 객체 저장소(MinIO/S3) 읽기
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-post-internal-agent-skill-authoring-references-read)

</details>

<a id="delete-skill"></a>
## `DELETE /api/workspaces/{workspace_id}/skills/{skill_id}`

- 인증: Bearer 토큰. Path의 워크스페이스 멤버십을 확인하고 인증된 사용자 ID를 AI 서비스에 전달한다. 요청 본문은 없다.
- 개인 스킬은 소유자, 워크스페이스 스킬은 OWNER 또는 `skill.author` 보유자만 삭제할 수 있다.
- 내부 호출: `DELETE /skills/{skill_id}?workspace_id=...&user_id=...`, `X-Agent-Service-Token` 사용.
- 권한: 대상 Skill이 팀 범위이면 `skill.author` 권한(OWNER는 항상 가능)이 필요하며 없으면 `403 WORKSPACE_PERMISSION_REQUIRED`다. 개인 Skill은 필요 없다. 범위는 Skill 조회로 확인한다.
- 성공: `204 No Content`. 미인증 `401`, 워크스페이스·스킬이 없거나 삭제 권한이 없으면 `404`, AI 서비스 사용 불가 `503`.
- 스킬·저장된 버전·버전 출처를 삭제한다. 과거 실행 기록은 보존하고 삭제된 버전의 참조만 해제한다. 실행 중 작업의 취소는 별도 계약이다.
- OFF는 목록에 남는 비활성화이며, DELETE는 되돌릴 수 없는 삭제다.
