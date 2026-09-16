# Wiki Schema API

[서비스 문서](../README.md) / [document-svc](README.md)

Wiki 스키마 조회·초안·미리보기·활성화 Gateway API다. Backend가 워크스페이스 권한을 검증하고
사용자·워크스페이스 정보를 추가해 ai-svc 내부 HTTP로 전달한다.

- API 수: 4

## API 목차

| API | 목적 |
|---|---|
| [`GET /api/workspaces/{workspace_id}/wiki-schema/active`](#summary-get-api-workspaces-workspace-id-wiki-schema-active) | 활성 Schema가 없으면 null을 포함한 200 응답을 반환합니다. |
| [`POST /api/workspaces/{workspace_id}/wiki-schema/drafts`](#summary-post-api-workspaces-workspace-id-wiki-schema-drafts) | 검토할 Wiki 생성 규칙을 초안 상태로 저장합니다. |
| [`POST /api/workspaces/{workspace_id}/wiki-schema/preview`](#summary-post-api-workspaces-workspace-id-wiki-schema-preview) | Schema 규칙을 저장하지 않고 적용해 예상 Wiki 구조를 반환합니다. |
| [`POST /api/workspaces/{workspace_id}/wiki-schema/{schema_id}/activate`](#summary-post-api-workspaces-workspace-id-wiki-schema-schema-id-activate) | 선택한 Wiki Schema ID의 활성화를 요청합니다. |

## 한눈에 보기

<a id="summary-get-api-workspaces-workspace-id-wiki-schema-active"></a>
### `GET /api/workspaces/{workspace_id}/wiki-schema/active`

| 항목 | 내용 |
|---|---|
| 목적 | 활성 Schema가 없으면 null을 포함한 200 응답을 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string` |
| 출력 | `200` 활성 Schema 조회 성공(null 가능) — `WikiSchemaResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 워크스페이스를 찾을 수 없음 — `JsonNode` / `ErrorResponse`<br>`503` llmPipeline 사용 불가 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-wiki-schema-active"></a>
### `GET /api/workspaces/{workspace_id}/wiki-schema/active` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/wiki-schema/active`

#### 2. 목적

활성 Schema가 없으면 null을 포함한 200 응답을 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 활성 Schema 조회 성공(null 가능)
- Content-Type: `*/*` (`WikiSchemaResponse`)

```json
{
  "activated_at": "string",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": false,
  "id": "string",
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": "string",
      "severity": "unclear",
      "text": "string"
    }
  ],
  "name": "설계 문서 스키마",
  "preview_markdown": "string",
  "raw_markdown": "string",
  "schema_version": "v1",
  "status": "active",
  "user_id": "user_123",
  "workspace_id": "ws_9d47a0e9a6324341b47562553b75f92a"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 워크스페이스를 찾을 수 없음 | `없음` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

```json
{
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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki-schema/active" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "activated_at": "string",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": false,
  "id": "string",
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": "string",
      "severity": "unclear",
      "text": "string"
    }
  ],
  "name": "설계 문서 스키마",
  "preview_markdown": "string",
  "raw_markdown": "string",
  "schema_version": "v1",
  "status": "active",
  "user_id": "user_123",
  "workspace_id": "ws_9d47a0e9a6324341b47562553b75f92a"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/wikischema/controller/WikiSchemaController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: getActive`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-wiki-schema-active)

</details>

<a id="summary-post-api-workspaces-workspace-id-wiki-schema-drafts"></a>
### `POST /api/workspaces/{workspace_id}/wiki-schema/drafts`

| 항목 | 내용 |
|---|---|
| 목적 | 검토할 Wiki 생성 규칙을 초안 상태로 저장합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Body** — `WikiSchemaDraftRequest` |
| 출력 | `200` 초안 생성 성공 — `WikiSchemaDraftResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 Schema 정의 — `JsonNode` / `ErrorResponse`<br>`404` 워크스페이스를 찾을 수 없음 — `JsonNode` / `ErrorResponse`<br>`422` Schema 요청 검증 실패 — `JsonNode` / `ErrorResponse`<br>`503` llmPipeline 사용 불가 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-wiki-schema-drafts"></a>
### `POST /api/workspaces/{workspace_id}/wiki-schema/drafts` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/wiki-schema/drafts`

#### 2. 목적

검토할 Wiki 생성 규칙을 초안 상태로 저장합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Content-Type: `application/json` (`WikiSchemaDraftRequest`)

```json
{
  "name": "설계 문서 스키마",
  "rawMarkdown": "# 설계\n\n## 구성요소"
}
```

#### 5. Response body

- HTTP `200`: 초안 생성 성공
- Content-Type: `*/*` (`WikiSchemaDraftResponse`)

```json
{
  "wiki_schema": {
    "activated_at": "string",
    "created_at": "2026-08-13T04:25:24.371948Z",
    "fragments": {
      "concept_markdown": "string",
      "edit_markdown": "string",
      "global_markdown": "string",
      "ingest_markdown": "string",
      "query_markdown": "string",
      "template_markdown": "string"
    },
    "has_blocked_issues": false,
    "id": "string",
    "issues": [
      {
        "category": "string",
        "reason": "string",
        "section": "string",
        "severity": "unclear",
        "text": "string"
      }
    ],
    "name": "설계 문서 스키마",
    "preview_markdown": "string",
    "raw_markdown": "string",
    "schema_version": "v1",
    "status": "draft",
    "user_id": "user_123",
    "workspace_id": "ws_9d47a0e9a6324341b47562553b75f92a"
  }
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 Schema 정의 | `없음` |
| `404` | 워크스페이스를 찾을 수 없음 | `없음` |
| `422` | Schema 요청 검증 실패 | `없음` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

```json
{
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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki-schema/drafts" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"name":"설계 문서 스키마","rawMarkdown":"# 설계\n\n## 구성요소"}'
```

```json
{
  "wiki_schema": {
    "activated_at": "string",
    "created_at": "2026-08-13T04:25:24.371948Z",
    "fragments": {
      "concept_markdown": "string",
      "edit_markdown": "string",
      "global_markdown": "string",
      "ingest_markdown": "string",
      "query_markdown": "string",
      "template_markdown": "string"
    },
    "has_blocked_issues": false,
    "id": "string",
    "issues": [
      {
        "category": "string",
        "reason": "string",
        "section": "string",
        "severity": "unclear",
        "text": "string"
      }
    ],
    "name": "설계 문서 스키마",
    "preview_markdown": "string",
    "raw_markdown": "string",
    "schema_version": "v1",
    "status": "draft",
    "user_id": "user_123",
    "workspace_id": "ws_9d47a0e9a6324341b47562553b75f92a"
  }
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/wikischema/controller/WikiSchemaController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: createDraft`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-wiki-schema-drafts)

</details>

<a id="summary-post-api-workspaces-workspace-id-wiki-schema-preview"></a>
### `POST /api/workspaces/{workspace_id}/wiki-schema/preview`

| 항목 | 내용 |
|---|---|
| 목적 | Schema 규칙을 저장하지 않고 적용해 예상 Wiki 구조를 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Body** — `WikiSchemaPreviewRequest` |
| 출력 | `200` 미리보기 생성 성공 — `WikiSchemaPreviewResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 Schema 또는 입력 — `JsonNode` / `ErrorResponse`<br>`404` 워크스페이스를 찾을 수 없음 — `JsonNode` / `ErrorResponse`<br>`422` Schema 요청 검증 실패 — `JsonNode` / `ErrorResponse`<br>`503` llmPipeline 사용 불가 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-wiki-schema-preview"></a>
### `POST /api/workspaces/{workspace_id}/wiki-schema/preview` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/wiki-schema/preview`

#### 2. 목적

Schema 규칙을 저장하지 않고 적용해 예상 Wiki 구조를 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Content-Type: `application/json` (`WikiSchemaPreviewRequest`)

```json
{
  "rawMarkdown": "# 설계\n\n## 구성요소"
}
```

#### 5. Response body

- HTTP `200`: 미리보기 생성 성공
- Content-Type: `*/*` (`WikiSchemaPreviewResponse`)

```json
{
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": false,
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": "string",
      "severity": "unclear",
      "text": "string"
    }
  ],
  "preview_markdown": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 Schema 또는 입력 | `없음` |
| `404` | 워크스페이스를 찾을 수 없음 | `없음` |
| `422` | Schema 요청 검증 실패 | `없음` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

```json
{
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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki-schema/preview" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"rawMarkdown":"# 설계\n\n## 구성요소"}'
```

```json
{
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": false,
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": "string",
      "severity": "unclear",
      "text": "string"
    }
  ],
  "preview_markdown": "string"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/wikischema/controller/WikiSchemaController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: preview`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-wiki-schema-preview)

</details>

<a id="summary-post-api-workspaces-workspace-id-wiki-schema-schema-id-activate"></a>
### `POST /api/workspaces/{workspace_id}/wiki-schema/{schema_id}/activate`

| 항목 | 내용 |
|---|---|
| 목적 | 선택한 Wiki Schema ID의 활성화를 요청합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `schema_id`: `string` |
| 출력 | `200` 활성화 성공 — `WikiSchemaResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` Schema 또는 워크스페이스를 찾을 수 없음 — `JsonNode` / `ErrorResponse`<br>`503` llmPipeline 사용 불가 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-wiki-schema-schema-id-activate"></a>
### `POST /api/workspaces/{workspace_id}/wiki-schema/{schema_id}/activate` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/wiki-schema/{schema_id}/activate`

#### 2. 목적

선택한 Wiki Schema ID의 활성화를 요청합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `schema_id` | `string` | 예 | 활성화할 Wiki Schema ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 활성화 성공
- Content-Type: `*/*` (`WikiSchemaResponse`)

```json
{
  "activated_at": "string",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": false,
  "id": "string",
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": "string",
      "severity": "unclear",
      "text": "string"
    }
  ],
  "name": "설계 문서 스키마",
  "preview_markdown": "string",
  "raw_markdown": "string",
  "schema_version": "v1",
  "status": "active",
  "user_id": "user_123",
  "workspace_id": "ws_9d47a0e9a6324341b47562553b75f92a"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | Schema 또는 워크스페이스를 찾을 수 없음 | `없음` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

```json
{
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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki-schema/<value>/activate" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "activated_at": "string",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": false,
  "id": "string",
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": "string",
      "severity": "unclear",
      "text": "string"
    }
  ],
  "name": "설계 문서 스키마",
  "preview_markdown": "string",
  "raw_markdown": "string",
  "schema_version": "v1",
  "status": "active",
  "user_id": "user_123",
  "workspace_id": "ws_9d47a0e9a6324341b47562553b75f92a"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/wikischema/controller/WikiSchemaController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: activate`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-wiki-schema-schema-id-activate)

</details>
