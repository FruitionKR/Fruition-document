# Document History API

[서비스 문서](../../README.md) / [document-svc](../README.md) / [Documents](README.md)

문서 삭제·복구와 콘텐츠 버전 이력 API다.

- API 수: 7

## API 목차

| API | 목적 |
|---|---|
| [`GET /api/workspaces/{workspace_id}/documents/trash`](#summary-get-api-workspaces-workspace-id-documents-trash) | 워크스페이스에서 소프트 삭제된 문서를 삭제 시각 역순으로 반환합니다. |
| [`DELETE /api/workspaces/{workspace_id}/documents/{document_id}`](#summary-delete-api-workspaces-workspace-id-documents-document-id) | 원본과 편집 상태를 유지한 채 문서를 소프트 삭제합니다. |
| [`GET /api/workspaces/{workspace_id}/documents/{document_id}/diff`](#summary-get-api-workspaces-workspace-id-documents-document-id-diff) | 두 Markdown 버전을 줄 단위로 비교해 GitHub 스타일 diff hunk를 반환합니다. |
| [`POST /api/workspaces/{workspace_id}/documents/{document_id}/restore`](#summary-post-api-workspaces-workspace-id-documents-document-id-restore) | 삭제 문서를 역할별 최상위 마지막 위치에 복구합니다. |
| [`GET /api/workspaces/{workspace_id}/documents/{document_id}/versions`](#summary-get-api-workspaces-workspace-id-documents-document-id-versions) | 편집 가능 Markdown 문서의 콘텐츠 버전 이력을 최신 순으로 반환합니다. 본문은 제외한 메타데이터만 제공합니다. |
| [`GET /api/workspaces/{workspace_id}/documents/{document_id}/versions/{version}`](#summary-get-api-workspaces-workspace-id-documents-document-id-versions-version) | 특정 버전의 전체 Markdown 본문을 반환합니다. |
| [`POST /api/workspaces/{workspace_id}/documents/{document_id}/versions/{version}/restore`](#summary-post-api-workspaces-workspace-id-documents-document-id-versions-version-restore) | 과거 버전을 새 버전으로 복원합니다(비파괴적). base_version이 현재 version과 일치할 때만 반영합니다. |

## 한눈에 보기

<a id="summary-get-api-workspaces-workspace-id-documents-trash"></a>
### `GET /api/workspaces/{workspace_id}/documents/trash`

| 항목 | 내용 |
|---|---|
| 목적 | 워크스페이스에서 소프트 삭제된 문서를 삭제 시각 역순으로 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string` |
| 출력 | `200` 휴지통 조회 성공 — `DocumentTrashResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-documents-trash"></a>
### `GET /api/workspaces/{workspace_id}/documents/trash` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents/trash`

#### 2. 목적

워크스페이스에서 소프트 삭제된 문서를 삭제 시각 역순으로 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 휴지통 조회 성공
- Content-Type: `*/*` (`DocumentTrashResponse`)

```json
{
  "documents": [
    {
      "current_version": 3,
      "delete_operation_id": "55555555-5555-5555-5555-555555555555",
      "deleted_at": "2026-08-13T04:25:24.371948Z",
      "deleted_by": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
      "display_name": "설계문서",
      "document_role": "EDITABLE",
      "filename": "설계문서.pdf",
      "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "source_document_id": "string"
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/trash" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "documents": [
    {
      "current_version": 3,
      "delete_operation_id": "55555555-5555-5555-5555-555555555555",
      "deleted_at": "2026-08-13T04:25:24.371948Z",
      "deleted_by": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
      "display_name": "설계문서",
      "document_role": "EDITABLE",
      "filename": "설계문서.pdf",
      "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "source_document_id": "string"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: trash`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-documents-trash)

</details>

<a id="summary-delete-api-workspaces-workspace-id-documents-document-id"></a>
### `DELETE /api/workspaces/{workspace_id}/documents/{document_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 원본과 편집 상태를 유지한 채 문서를 소프트 삭제합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string`<br>**Header** — `Idempotency-Key`: `string`<br>**Body** — `DocumentLifecycleRequest` |
| 출력 | `200` 삭제 성공 — `DocumentLifecycleResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 base_version 또는 Idempotency-Key — `ErrorResponse`<br>`403` 문서 소유자가 아님 — `ErrorResponse`<br>`404` 문서 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` 문서 version 또는 멱등 키 충돌 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-delete-api-workspaces-workspace-id-documents-document-id"></a>
### `DELETE /api/workspaces/{workspace_id}/documents/{document_id}` 상세

#### 1. Method + Path

`DELETE /api/workspaces/{workspace_id}/documents/{document_id}`

#### 2. 목적

원본과 편집 상태를 유지한 채 문서를 소프트 삭제합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | 문서 ID |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`DocumentLifecycleRequest`)

```json
{
  "base_version": 1
}
```

#### 5. Response body

- HTTP `200`: 삭제 성공
- Content-Type: `*/*` (`DocumentLifecycleResponse`)

```json
{
  "current_version": 2,
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "sort_order": 1024
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 base_version 또는 Idempotency-Key | `ErrorResponse` |
| `403` | 문서 소유자가 아님 | `ErrorResponse` |
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | 문서 version 또는 멱등 키 충돌 | `ErrorResponse` |

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
curl -X DELETE "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":1}'
```

```json
{
  "current_version": 2,
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "sort_order": 1024
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: delete_2`)

[↑ 요약으로 돌아가기](#summary-delete-api-workspaces-workspace-id-documents-document-id)

</details>

<a id="summary-get-api-workspaces-workspace-id-documents-document-id-diff"></a>
### `GET /api/workspaces/{workspace_id}/documents/{document_id}/diff`

| 항목 | 내용 |
|---|---|
| 목적 | 두 Markdown 버전을 줄 단위로 비교해 GitHub 스타일 diff hunk를 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string`<br>**Query** — `from_version`: `integer`, `to_version`: `integer` |
| 출력 | `200` 비교 성공 — `DocumentContentDiffResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>필터링: `from_version`, `to_version`<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 편집 가능한 Markdown 문서가 아님 — `ErrorResponse`<br>`404` 문서 또는 비교할 버전을 찾을 수 없음 — `ErrorResponse`<br>`422` 문서 차이가 너무 커서 안전하게 비교할 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-documents-document-id-diff"></a>
### `GET /api/workspaces/{workspace_id}/documents/{document_id}/diff` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents/{document_id}/diff`

#### 2. 목적

두 Markdown 버전을 줄 단위로 비교해 GitHub 스타일 diff hunk를 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | - |
| query | `from_version` | `integer` | 예 | - |
| query | `to_version` | `integer` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 비교 성공
- Content-Type: `*/*` (`DocumentContentDiffResponse`)

```json
{
  "additions": 12,
  "deletions": 4,
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "from_version": 2,
  "hunks": [
    {
      "lines": [
        {
          "content": "string",
          "new_line": 10,
          "old_line": 10,
          "type": "CONTEXT"
        }
      ],
      "new_lines": 5,
      "new_start": 10,
      "old_lines": 3,
      "old_start": 10
    }
  ],
  "to_version": 3
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 편집 가능한 Markdown 문서가 아님 | `ErrorResponse` |
| `404` | 문서 또는 비교할 버전을 찾을 수 없음 | `ErrorResponse` |
| `422` | 문서 차이가 너무 커서 안전하게 비교할 수 없음 | `ErrorResponse` |

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
- 필터링: `from_version`, `to_version`

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/diff?from_version=1&to_version=1" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "additions": 12,
  "deletions": 4,
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "from_version": 2,
  "hunks": [
    {
      "lines": [
        {
          "content": "string",
          "new_line": 10,
          "old_line": 10,
          "type": "CONTEXT"
        }
      ],
      "new_lines": 5,
      "new_start": 10,
      "old_lines": 3,
      "old_start": 10
    }
  ],
  "to_version": 3
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: compareVersions`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-documents-document-id-diff)

</details>

<a id="summary-post-api-workspaces-workspace-id-documents-document-id-restore"></a>
### `POST /api/workspaces/{workspace_id}/documents/{document_id}/restore`

| 항목 | 내용 |
|---|---|
| 목적 | 삭제 문서를 역할별 최상위 마지막 위치에 복구합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string`<br>**Header** — `Idempotency-Key`: `string`<br>**Body** — `DocumentLifecycleRequest` |
| 출력 | `200` 복구 성공 — `DocumentLifecycleResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 base_version 또는 Idempotency-Key — `ErrorResponse`<br>`403` 문서 소유자가 아님 — `ErrorResponse`<br>`404` 삭제 문서 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` 문서 version 또는 멱등 키 충돌 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-documents-document-id-restore"></a>
### `POST /api/workspaces/{workspace_id}/documents/{document_id}/restore` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/{document_id}/restore`

#### 2. 목적

삭제 문서를 역할별 최상위 마지막 위치에 복구합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | - |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`DocumentLifecycleRequest`)

```json
{
  "base_version": 1
}
```

#### 5. Response body

- HTTP `200`: 복구 성공
- Content-Type: `*/*` (`DocumentLifecycleResponse`)

```json
{
  "current_version": 2,
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "sort_order": 1024
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 base_version 또는 Idempotency-Key | `ErrorResponse` |
| `403` | 문서 소유자가 아님 | `ErrorResponse` |
| `404` | 삭제 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | 문서 version 또는 멱등 키 충돌 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/restore" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":1}'
```

```json
{
  "current_version": 2,
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "sort_order": 1024
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: restore_1`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-documents-document-id-restore)

</details>

<a id="summary-get-api-workspaces-workspace-id-documents-document-id-versions"></a>
### `GET /api/workspaces/{workspace_id}/documents/{document_id}/versions`

| 항목 | 내용 |
|---|---|
| 목적 | 편집 가능 Markdown 문서의 콘텐츠 버전 이력을 최신 순으로 반환합니다. 본문은 제외한 메타데이터만 제공합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string` |
| 출력 | `200` 조회 성공 — `DocumentContentVersionListResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 편집 가능한 Markdown 문서가 아님 — `ErrorResponse`<br>`404` 문서 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-documents-document-id-versions"></a>
### `GET /api/workspaces/{workspace_id}/documents/{document_id}/versions` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents/{document_id}/versions`

#### 2. 목적

편집 가능 Markdown 문서의 콘텐츠 버전 이력을 최신 순으로 반환합니다. 본문은 제외한 메타데이터만 제공합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`DocumentContentVersionListResponse`)

```json
{
  "current_version": 4,
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "versions": [
    {
      "content_hash": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "created_by": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
      "version": 3
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 편집 가능한 Markdown 문서가 아님 | `ErrorResponse` |
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/versions" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "current_version": 4,
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "versions": [
    {
      "content_hash": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "created_by": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
      "version": 3
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: listVersions`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-documents-document-id-versions)

</details>

<a id="summary-get-api-workspaces-workspace-id-documents-document-id-versions-version"></a>
### `GET /api/workspaces/{workspace_id}/documents/{document_id}/versions/{version}`

| 항목 | 내용 |
|---|---|
| 목적 | 특정 버전의 전체 Markdown 본문을 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string`, `version`: `integer` |
| 출력 | `200` 조회 성공 — `DocumentContentVersionResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 문서 또는 해당 버전을 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-documents-document-id-versions-version"></a>
### `GET /api/workspaces/{workspace_id}/documents/{document_id}/versions/{version}` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents/{document_id}/versions/{version}`

#### 2. 목적

특정 버전의 전체 Markdown 본문을 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | - |
| path | `version` | `integer` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`DocumentContentVersionResponse`)

```json
{
  "content_hash": "string",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "created_by": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "markdown": "string",
  "version": 3
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 문서 또는 해당 버전을 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/versions/1" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "content_hash": "string",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "created_by": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "markdown": "string",
  "version": 3
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: getVersion`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-documents-document-id-versions-version)

</details>

<a id="summary-post-api-workspaces-workspace-id-documents-document-id-versions-version-restore"></a>
### `POST /api/workspaces/{workspace_id}/documents/{document_id}/versions/{version}/restore`

| 항목 | 내용 |
|---|---|
| 목적 | 과거 버전을 새 버전으로 복원합니다(비파괴적). base_version이 현재 version과 일치할 때만 반영합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string`, `version`: `integer`<br>**Body** — `DocumentContentRestoreRequest` |
| 출력 | `200` 복원 성공 또는 동일 본문 no-op — `DocumentContentSaveResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 편집 가능한 Markdown 문서가 아니거나 base_version 오류 — `ErrorResponse`<br>`403` 문서 소유자가 아님 — `ErrorResponse`<br>`404` 문서 또는 해당 버전을 찾을 수 없음 — `ErrorResponse`<br>`409` 문서 version 충돌 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-documents-document-id-versions-version-restore"></a>
### `POST /api/workspaces/{workspace_id}/documents/{document_id}/versions/{version}/restore` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/{document_id}/versions/{version}/restore`

#### 2. 목적

과거 버전을 새 버전으로 복원합니다(비파괴적). base_version이 현재 version과 일치할 때만 반영합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | - |
| path | `version` | `integer` | 예 | - |

- Content-Type: `application/json` (`DocumentContentRestoreRequest`)

```json
{
  "base_version": 4
}
```

#### 5. Response body

- HTTP `200`: 복원 성공 또는 동일 본문 no-op
- Content-Type: `*/*` (`DocumentContentSaveResponse`)

```json
{
  "attachments": [
    {
      "asset_id": "55555555-5555-5555-5555-555555555555",
      "attachment_id": "55555555-5555-5555-5555-555555555555",
      "content_path": "string"
    }
  ],
  "changed": true,
  "content_hash": "string",
  "current_version": 4,
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "markdown": "string",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 편집 가능한 Markdown 문서가 아니거나 base_version 오류 | `ErrorResponse` |
| `403` | 문서 소유자가 아님 | `ErrorResponse` |
| `404` | 문서 또는 해당 버전을 찾을 수 없음 | `ErrorResponse` |
| `409` | 문서 version 충돌 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/versions/1/restore" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":4}'
```

```json
{
  "attachments": [
    {
      "asset_id": "55555555-5555-5555-5555-555555555555",
      "attachment_id": "55555555-5555-5555-5555-555555555555",
      "content_path": "string"
    }
  ],
  "changed": true,
  "content_hash": "string",
  "current_version": 4,
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "markdown": "string",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: restoreVersion`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-documents-document-id-versions-version-restore)

</details>
