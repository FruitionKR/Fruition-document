# Document History API

[서비스 문서](../../README.md) / [document-svc](../README.md) / [Documents](README.md)

문서 삭제·복구와 콘텐츠 버전 이력 API다.

일반 이력은 10분 단위로, 승인된 AI 적용은 즉시 기록한다. 내부 revision과 이력 version은 분리된다.
번호 의미와 프론트 연결 변경은 [문서 이력 정책](../../adr/0026-document-history-policy.md)을 따른다.
이력 목록의 `current_revision`을 복원 충돌 검사에 사용하고, `current_version`은 현재 이력 번호 또는 null이다.
`to_version=0`은 미기록 편집을 포함한 최신 본문과 비교한다.

- API 수: 7

## API 계약과 필요한 프론트 연결

이번 작업의 코드 변경 범위는 Fruition-document다. 프론트 이력 화면은 다음 계약에 맞춰 연결해야 한다.

| 응답/요청 | 번호 의미 |
|---|---|
| 문서 상세 `edit_revision` | 현재 내부 편집 revision, 기존 계약 유지 |
| 본문 저장 응답 `current_version` | 기존 필드 유지: 내부 편집 revision |
| 본문 저장 응답 `current_revision` | 위 값과 동일한 명시적 필드 |
| 이력 목록 `current_version` | 최신 본문과 일치하는 최신 이력 번호. 미기록 편집이 있거나 이력이 없으면 null |
| 이력 목록 `current_revision` | 최신 편집 revision. 복원 충돌 검사에 사용 |
| 이력 항목 `version`, `revision`, `record_type` | 이력 번호, 원본 편집 revision, 기록 종류 |
| `GET .../diff?from_version=N&to_version=M` | 두 이력 번호 비교 |
| `GET .../diff?from_version=N&to_version=0` | 이력 N과 미기록 편집을 포함한 최신 본문 비교 |
| `POST .../versions/N/restore` | 복원 대상 N은 이력 번호 |
| 복원 body `base_revision` (기존 `base_version`도 허용) | 직전에 조회한 목록의 current_revision. 이력 번호를 보내지 않는다 |

필요한 최소 프론트 변경:

1. 이력 응답에서 `current_revision`을 보관하고, 복원 요청의 충돌 검사 값에 사용한다.
2. `current_version`이 null일 때도 `to_version=0`으로 최신 본문과 비교하고 복원할 수 있게 한다.
3. 목록의 current_version은 현재 배지에만 사용한다. 마지막 이력 번호를 최신 revision으로 취급하지 않는다.
4. 자동저장 응답의 기존 current_version은 revision으로 유지되므로 기존 저장 흐름은 그대로 동작한다.

기존 프론트는 이력 목록의 current_version을 복원 base 값으로도 사용하므로, 번호 분리 뒤 프론트 수정 없이
배포하면 콘텐츠 이력 화면의 복원이 409로 실패하거나 미기록 본문 비교가 표시되지 않을 수 있다.
서버에서 현재 revision을 임의로 대입해 충돌 검사를 우회하지 않는다. AI 로그의 되돌리기는 별도의 revision
경로로 처리하므로 이 프론트 이력 화면의 계약과 독립적이다.

## API 목차

| API | 목적 |
|---|---|
| [`GET /api/workspaces/{workspace_id}/documents/trash`](#summary-get-api-workspaces-workspace-id-documents-trash) | 워크스페이스에서 소프트 삭제된 문서를 삭제 시각 역순으로 반환합니다. |
| [`DELETE /api/workspaces/{workspace_id}/documents/{document_id}`](#summary-delete-api-workspaces-workspace-id-documents-document-id) | 원본과 편집 상태를 유지한 채 문서를 소프트 삭제하고 AI에 위키 정리를 요청합니다. |
| [`GET /api/workspaces/{workspace_id}/documents/{document_id}/diff`](#summary-get-api-workspaces-workspace-id-documents-document-id-diff) | 두 Markdown 버전을 줄 단위로 비교해 GitHub 스타일 diff hunk를 반환합니다. |
| [`POST /api/workspaces/{workspace_id}/documents/{document_id}/restore`](#summary-post-api-workspaces-workspace-id-documents-document-id-restore) | 삭제 문서를 역할별 최상위 마지막 위치에 복구합니다. 편집 문서는 미편입 상태로 돌아오므로 다시 편입해야 합니다. |
| [`GET /api/workspaces/{workspace_id}/documents/{document_id}/versions`](#summary-get-api-workspaces-workspace-id-documents-document-id-versions) | 편집 가능 Markdown 문서의 콘텐츠 버전 이력을 최신 순으로 반환합니다. 본문은 제외한 메타데이터만 제공합니다. |
| [`GET /api/workspaces/{workspace_id}/documents/{document_id}/versions/{version}`](#summary-get-api-workspaces-workspace-id-documents-document-id-versions-version) | 특정 버전의 전체 Markdown 본문을 반환합니다. |
| [`POST /api/workspaces/{workspace_id}/documents/{document_id}/versions/{version}/restore`](#summary-post-api-workspaces-workspace-id-documents-document-id-versions-version-restore) | 과거 버전을 새 버전으로 복원합니다(비파괴적). base_revision(기존 base_version)이 현재 편집 revision과 일치할 때만 반영합니다. |

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
- 호출자: 없음 — 프론트엔드 `apiFetch` 호출 지점에 이 경로가 없고, ai-svc·access-svc도 이 서비스의 `/api/**`를 호출하지 않는다
- 하위 호출: 권한 확인(access-svc `GET /internal/authz/workspaces/{id}/users/{id}`, Redis 캐시 miss에만 발생) 외 없음
- 배선 상태: **미배선 — 호출자 없음**

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-documents-trash)

</details>

<a id="summary-delete-api-workspaces-workspace-id-documents-document-id"></a>
### `DELETE /api/workspaces/{workspace_id}/documents/{document_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 원본과 편집 상태를 유지한 채 문서를 소프트 삭제하고 AI에 위키 정리를 요청합니다. |
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

원본과 편집 상태를 유지한 채 문서를 소프트 삭제하고 AI에 위키 정리를 요청합니다.

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
- 호출자: 프론트엔드 — `src/entities/document/api/document.ts:131`/`:144`(`deleteDocument`)
- 하위 호출: ai-svc `DELETE ${WIKI_STATE_ENDPOINT}/workspaces/{wsId}/documents/{docId}` (`PipelineWikiStateRequester`)
- 배선 상태: 배선됨

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
- 호출자: 프론트엔드 — `src/features/document-history/api/versions.ts:54`(`fetchDocumentVersionDiff`, `?from_version&to_version`)
- 하위 호출: 권한 확인(access-svc `GET /internal/authz/workspaces/{id}/users/{id}`, Redis 캐시 miss에만 발생) 외 없음
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-documents-document-id-diff)

</details>

<a id="summary-post-api-workspaces-workspace-id-documents-document-id-restore"></a>
### `POST /api/workspaces/{workspace_id}/documents/{document_id}/restore`

| 항목 | 내용 |
|---|---|
| 목적 | 삭제 문서를 역할별 최상위 마지막 위치에 복구합니다. 편집 문서는 미편입 상태로 돌아오므로 다시 편입해야 합니다. |
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

삭제 문서를 역할별 최상위 마지막 위치에 복구합니다. 편집 문서는 미편입 상태로 돌아오므로 다시 편입해야 합니다.

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
- 호출자: 없음 — 프론트엔드 `apiFetch` 호출 지점에 이 경로가 없고, ai-svc·access-svc도 이 서비스의 `/api/**`를 호출하지 않는다
- 하위 호출: 권한 확인(access-svc `GET /internal/authz/workspaces/{id}/users/{id}`, Redis 캐시 miss에만 발생) 외 없음
- 배선 상태: **미배선 — 호출자 없음**

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
  "current_revision": 4,
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "versions": [
    {
      "content_hash": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "created_by": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
      "restored_from_version": 2,
      "revision": 3,
      "record_type": "restore",
      "version": 3
    }
  ]
}
```

- `restored_from_version`: 버전 복원(`POST .../versions/{version}/restore`)으로 만든 버전이면 복원 대상 버전 번호, 그 밖에는 `null`이다. 일반 저장·Agent 적용·복원 직후 이어진 저장은 `null`이다. 이 필드를 추가하기 전(V57 이전)에 만든 버전은 복원 여부를 알 수 없어 모두 `null`이다. 대상 버전이 나중에 정리되어도 번호는 그대로 남는다.

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
  "current_revision": 4,
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "versions": [
    {
      "content_hash": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "created_by": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
      "restored_from_version": 2,
      "revision": 3,
      "record_type": "restore",
      "version": 3
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: listVersions`)
- 호출자: 프론트엔드 — `src/features/document-history/api/versions.ts:43`(`fetchDocumentVersions`)
- 하위 호출: 권한 확인(access-svc `GET /internal/authz/workspaces/{id}/users/{id}`, Redis 캐시 miss에만 발생) 외 없음
- 배선 상태: 배선됨

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
- 호출자: 없음 — 프론트엔드 `apiFetch` 호출 지점에 이 경로가 없고, ai-svc·access-svc도 이 서비스의 `/api/**`를 호출하지 않는다. 프론트엔드는 목록과 diff만 쓴다
- 하위 호출: 객체 저장소(MinIO/S3) 읽기
- 배선 상태: **미배선 — 호출자 없음**

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-documents-document-id-versions-version)

</details>

<a id="summary-post-api-workspaces-workspace-id-documents-document-id-versions-version-restore"></a>
### `POST /api/workspaces/{workspace_id}/documents/{document_id}/versions/{version}/restore`

| 항목 | 내용 |
|---|---|
| 목적 | 과거 버전을 새 버전으로 복원합니다(비파괴적). base_revision(기존 base_version)이 현재 편집 revision과 일치할 때만 반영합니다. |
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

과거 버전을 새 버전으로 복원합니다(비파괴적). base_revision(기존 base_version)이 현재 편집 revision과 일치할 때만 반영합니다.

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
  "current_revision": 4,
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
  "current_revision": 4,
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "markdown": "string",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: restoreVersion`)
- 호출자: 프론트엔드 — `src/features/document-history/api/versions.ts:67`(`restoreDocumentVersion`)
- 하위 호출: 객체 저장소(MinIO/S3) 읽기·쓰기
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-documents-document-id-versions-version-restore)

</details>
