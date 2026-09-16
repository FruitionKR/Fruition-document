# Skills API

[서비스 문서](../README.md) / [document-svc](README.md)

Skill 작성·게시·설정 Gateway와 내부 참조 문서 읽기 API다. 공개 요청은 Backend가 권한과
workspace AI 모델을 확인한 뒤 ai-svc 내부 HTTP로 전달한다.

- API 수: 8

작성·게시·수정 요청은 선택 query parameter `run_id`를 받고 응답에도 `run_id`를 반환한다.
호출자가 ID를 미리 지정하면 HTTP 응답을 기다리는 중에도 [공통 취소 API](tasks.md)를
호출할 수 있다. Backend는 `/skills/tasks` 내부 API에 원래 body와 검증한 actor·모델을 전달하며,
취소는 생성한 Skill 삭제 또는 이전 게시 버전·설정 복구까지 확인한다.

## API 목차

| API | 목적 |
|---|---|
| [`GET /api/workspaces/{workspace_id}/skills`](#summary-get-api-workspaces-workspace-id-skills) | 현재 사용자가 사용할 수 있는 워크스페이스 Skill 목록을 반환합니다. |
| [`POST /api/workspaces/{workspace_id}/skills/author`](#summary-post-api-workspaces-workspace-id-skills-author) | 자연어 요구를 바탕으로 저장 전 Skill 초안을 생성합니다. |
| [`POST /api/workspaces/{workspace_id}/skills/author/publish`](#summary-post-api-workspaces-workspace-id-skills-author-publish) | 작성된 Skill 정의를 검토 후 게시합니다. |
| [`GET /api/workspaces/{workspace_id}/skills/{skill_id}`](#summary-get-api-workspaces-workspace-id-skills-skill-id) | Skill의 현재 정의와 실행 설정을 반환합니다. |
| [`PATCH /api/workspaces/{workspace_id}/skills/{skill_id}`](#summary-patch-api-workspaces-workspace-id-skills-skill-id) | Skill의 정의를 수정합니다. |
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
      "status": "published",
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
      "status": "published",
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
      "status": "published",
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
      "status": "published",
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
| 주요 오류 | `400` 잘못된 요청 — `ErrorResponse`<br>`404` 워크스페이스 또는 참조 문서를 찾을 수 없음 — `ErrorResponse`<br>`409` Skill 이름 또는 버전 충돌 — `ErrorResponse`<br>`410` Skill이 더 이상 유효하지 않음 — `ErrorResponse`<br>`413` 참조 문서 또는 요청 본문이 너무 큼 — `JsonNode` / `ErrorResponse`<br>`422` Skill 요청 검증 실패 — `ErrorResponse`<br>`503` llmPipeline 사용 불가 — `ErrorResponse` |

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
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "version_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 요청 | `ErrorResponse` |
| `404` | 워크스페이스 또는 참조 문서를 찾을 수 없음 | `ErrorResponse` |
| `409` | Skill 이름 또는 버전 충돌 | `ErrorResponse` |
| `410` | Skill이 더 이상 유효하지 않음 | `ErrorResponse` |
| `413` | 참조 문서 또는 요청 본문이 너무 큼 | `없음` |
| `422` | Skill 요청 검증 실패 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

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
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "version_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/skill/controller/SkillController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: author`)

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
| 주요 오류 | `400` 잘못된 Skill 정의 — `ErrorResponse`<br>`404` 워크스페이스 또는 참조 문서를 찾을 수 없음 — `ErrorResponse`<br>`409` Skill 이름 또는 버전 충돌 — `ErrorResponse`<br>`410` Skill이 더 이상 유효하지 않음 — `ErrorResponse`<br>`413` 요청 본문이 너무 큼 — `JsonNode` / `ErrorResponse`<br>`422` Skill 요청 검증 실패 — `ErrorResponse`<br>`503` llmPipeline 사용 불가 — `ErrorResponse` |

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
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "version_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 Skill 정의 | `ErrorResponse` |
| `404` | 워크스페이스 또는 참조 문서를 찾을 수 없음 | `ErrorResponse` |
| `409` | Skill 이름 또는 버전 충돌 | `ErrorResponse` |
| `410` | Skill이 더 이상 유효하지 않음 | `ErrorResponse` |
| `413` | 요청 본문이 너무 큼 | `없음` |
| `422` | Skill 요청 검증 실패 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/skills/author/publish" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"allowed_tools":["list_root_items"],"capabilities":["document-create"],"description":"<value>","instructions_markdown":"<value>","name":"meeting-notes","scope_type":"personal"}'
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
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "version_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/skill/controller/SkillController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: publish`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-skills-author-publish)

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
    "status": "published",
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
    "status": "published",
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
    "status": "published",
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
    "status": "published",
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
| 주요 오류 | `400` 잘못된 Skill 정의 — `ErrorResponse`<br>`404` Skill 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` Skill 이름 또는 버전 충돌 — `ErrorResponse`<br>`410` Skill이 더 이상 유효하지 않음 — `ErrorResponse`<br>`413` 요청 본문이 너무 큼 — `JsonNode` / `ErrorResponse`<br>`422` Skill 요청 검증 실패 — `ErrorResponse`<br>`503` llmPipeline 사용 불가 — `ErrorResponse` |

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
  "name": "meeting-notes"
}
```

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
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "version_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 Skill 정의 | `ErrorResponse` |
| `404` | Skill 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Skill 이름 또는 버전 충돌 | `ErrorResponse` |
| `410` | Skill이 더 이상 유효하지 않음 | `ErrorResponse` |
| `413` | 요청 본문이 너무 큼 | `없음` |
| `422` | Skill 요청 검증 실패 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

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
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "version_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/skill/controller/SkillController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: update_1`)

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
| 주요 오류 | `404` Skill 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` Skill 요청 충돌 — `ErrorResponse`<br>`410` Skill이 더 이상 유효하지 않음 — `ErrorResponse`<br>`503` llmPipeline 사용 불가 — `ErrorResponse` |

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
    "status": "published",
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
    "status": "published",
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
    "status": "published",
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
    "status": "published",
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
| 주요 오류 | `404` Skill 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` Skill 요청 충돌 — `ErrorResponse`<br>`410` Skill이 더 이상 유효하지 않음 — `ErrorResponse`<br>`503` llmPipeline 사용 불가 — `ErrorResponse` |

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
    "status": "published",
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
    "status": "published",
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
    "status": "published",
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
    "status": "published",
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

[↑ 요약으로 돌아가기](#summary-post-internal-agent-skill-authoring-references-read)

</details>
