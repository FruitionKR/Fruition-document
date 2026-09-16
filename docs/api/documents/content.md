# Document Content API

[서비스 문서](../../README.md) / [document-svc](../README.md) / [Documents](README.md)

문서 본문·원본·asset·편집 잠금 API다.

- API 수: 9

채팅 Wiki page화로 만들어진 `chat_export` 문서는 읽기 전용이다(`editable: false`). 편집 잠금 획득·본문 저장은
400 `INVALID_MARKDOWN_CONTENT`로 거절한다. 본문을 고치면 문답 provenance가 끊기기 때문이다.
Ingest는 `POST /api/workspaces/{workspace_id}/documents/{document_id}/ingest`로 별도 요청하며 저장된 문답과 출처 블록을 사용한다.

## API 목차

| API | 목적 |
|---|---|
| [`GET /api/workspaces/{workspace_id}/assets/{asset_id}/content`](#summary-get-api-workspaces-workspace-id-assets-asset-id-content) | 워크스페이스 멤버에게 관리 이미지 bytes를 반환합니다. |
| [`POST /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock`](#summary-post-api-workspaces-workspace-id-documents-document-id-edit-lock) | 편집기 진입 시 호출한다. 비었거나 만료됐거나 본인 보유면 잠금을 부여(200)한다. 다른 사용자가 편집 중이면 423과 보유자 정보를 반환한다. |
| [`DELETE /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock`](#summary-delete-api-workspaces-workspace-id-documents-document-id-edit-lock) | 편집기 종료 시 호출한다. 보유자 본인의 잠금만 해제하며 멱등이다. |
| [`POST /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock/heartbeat`](#summary-post-api-workspaces-workspace-id-documents-document-id-edit-lock-heartbeat) | 편집 중 주기적으로 호출해 잠금을 연장한다. 보유자가 아니거나 만료됐으면 409. |
| [`GET /api/workspaces/{workspace_id}/documents/{document_id}/blocks`](#summary-get-api-workspaces-workspace-id-documents-document-id-blocks) | 원본 문서를 block 단위로 나눈 텍스트 목록을 반환합니다. 답변 인용 클릭 시 원본 block 하이라이트에 사용됩니다. |
| [`PUT /api/workspaces/{workspace_id}/documents/{document_id}/content`](#summary-put-api-workspaces-workspace-id-documents-document-id-content) | 전체 Markdown과 신규 이미지를 저장합니다. base_revision이 현재 편집 revision과 일치할 때만 반영하며 revision_write_id 재시도는 기존 결과를 반환합니다. 이미지 포함 저장은 metadata part를 사용합니다. |
| [`GET /api/workspaces/{workspace_id}/documents/{document_id}/export`](#summary-get-api-workspaces-workspace-id-documents-document-id-export) | 최신 Markdown 편집본을 내보냅니다. 관리 이미지가 있으면 이미지와 Markdown을 ZIP으로 반환합니다. |
| [`GET /api/workspaces/{workspace_id}/documents/{document_id}/original`](#summary-get-api-workspaces-workspace-id-documents-document-id-original) | MinIO에 저장된 원본 파일을 스트리밍합니다. PDF는 inline, 그 외는 attachment로 반환됩니다. |
| [`GET /internal/documents/{document_id}/pipeline-source`](#summary-get-internal-documents-document-id-pipeline-source) | AI pipeline이 사용할 문서 원본 위치와 소유 범위를 조회합니다. |

## 한눈에 보기

<a id="summary-get-api-workspaces-workspace-id-assets-asset-id-content"></a>
### `GET /api/workspaces/{workspace_id}/assets/{asset_id}/content`

| 항목 | 내용 |
|---|---|
| 목적 | 워크스페이스 멤버에게 관리 이미지 bytes를 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `asset_id`: `string` |
| 출력 | `200` 이미지 반환 — `string`<br>`304` 캐시된 이미지 사용 — `string` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` asset 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-assets-asset-id-content"></a>
### `GET /api/workspaces/{workspace_id}/assets/{asset_id}/content` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/assets/{asset_id}/content`

#### 2. 목적

워크스페이스 멤버에게 관리 이미지 bytes를 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `asset_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 이미지 반환
- Content-Type: `*/*`

```text
<binary>
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `304` | 캐시된 이미지 사용 | `없음` |
| `404` | asset 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

```text
<binary>
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/assets/55555555-5555-5555-5555-555555555555/content" \
  -H 'Authorization: Bearer <access_token>'
```

```text
<binary>
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentAssetController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: getContent`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-assets-asset-id-content)

</details>

<a id="summary-post-api-workspaces-workspace-id-documents-document-id-edit-lock"></a>
### `POST /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock`

| 항목 | 내용 |
|---|---|
| 목적 | 편집기 진입 시 호출한다. 비었거나 만료됐거나 본인 보유면 잠금을 부여(200)한다. 다른 사용자가 편집 중이면 423과 보유자 정보를 반환한다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string` |
| 출력 | `200` 잠금 획득/갱신 — `EditLockResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `403` 문서 소유자가 아님 — `ErrorResponse`<br>`404` 문서 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`423` 다른 사용자가 편집 중 — `EditLockResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-documents-document-id-edit-lock"></a>
### `POST /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock`

#### 2. 목적

편집기 진입 시 호출한다. 비었거나 만료됐거나 본인 보유면 잠금을 부여(200)한다. 다른 사용자가 편집 중이면 423과 보유자 정보를 반환한다.

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

- HTTP `200`: 잠금 획득/갱신
- Content-Type: `*/*` (`EditLockResponse`)

```json
{
  "expires_at": "2026-08-13T04:25:24.371948Z",
  "holder_display_name": "표시 이름",
  "holder_user_id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `403` | 문서 소유자가 아님 | `ErrorResponse` |
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `423` | 다른 사용자가 편집 중 | `EditLockResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/edit-lock" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "expires_at": "2026-08-13T04:25:24.371948Z",
  "holder_display_name": "표시 이름",
  "holder_user_id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentEditLockController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: acquire`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-documents-document-id-edit-lock)

</details>

<a id="summary-delete-api-workspaces-workspace-id-documents-document-id-edit-lock"></a>
### `DELETE /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock`

| 항목 | 내용 |
|---|---|
| 목적 | 편집기 종료 시 호출한다. 보유자 본인의 잠금만 해제하며 멱등이다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string` |
| 출력 | `204` 잠금 해제 성공 — 본문 없음 |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | 공통 오류 계약 적용 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-delete-api-workspaces-workspace-id-documents-document-id-edit-lock"></a>
### `DELETE /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock` 상세

#### 1. Method + Path

`DELETE /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock`

#### 2. 목적

편집기 종료 시 호출한다. 보유자 본인의 잠금만 해제하며 멱등이다.

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

- HTTP `204`: 잠금 해제 성공
- Body: 없음

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
curl -X DELETE "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/edit-lock" \
  -H 'Authorization: Bearer <access_token>'
```

응답 본문 없음.

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentEditLockController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: release`)

[↑ 요약으로 돌아가기](#summary-delete-api-workspaces-workspace-id-documents-document-id-edit-lock)

</details>

<a id="summary-post-api-workspaces-workspace-id-documents-document-id-edit-lock-heartbeat"></a>
### `POST /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock/heartbeat`

| 항목 | 내용 |
|---|---|
| 목적 | 편집 중 주기적으로 호출해 잠금을 연장한다. 보유자가 아니거나 만료됐으면 409. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string` |
| 출력 | `200` 잠금 연장 — `EditLockResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `409` 잠금 상실(만료/타인 보유) — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-documents-document-id-edit-lock-heartbeat"></a>
### `POST /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock/heartbeat` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock/heartbeat`

#### 2. 목적

편집 중 주기적으로 호출해 잠금을 연장한다. 보유자가 아니거나 만료됐으면 409.

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

- HTTP `200`: 잠금 연장
- Content-Type: `*/*` (`EditLockResponse`)

```json
{
  "expires_at": "2026-08-13T04:25:24.371948Z",
  "holder_display_name": "표시 이름",
  "holder_user_id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `409` | 잠금 상실(만료/타인 보유) | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/edit-lock/heartbeat" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "expires_at": "2026-08-13T04:25:24.371948Z",
  "holder_display_name": "표시 이름",
  "holder_user_id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentEditLockController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: heartbeat`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-documents-document-id-edit-lock-heartbeat)

</details>

<a id="summary-get-api-workspaces-workspace-id-documents-document-id-blocks"></a>
### `GET /api/workspaces/{workspace_id}/documents/{document_id}/blocks`

| 항목 | 내용 |
|---|---|
| 목적 | 원본 문서를 block 단위로 나눈 텍스트 목록을 반환합니다. 답변 인용 클릭 시 원본 block 하이라이트에 사용됩니다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string` |
| 출력 | `200` 조회 성공 — `DocumentBlocksResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 문서 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-documents-document-id-blocks"></a>
### `GET /api/workspaces/{workspace_id}/documents/{document_id}/blocks` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents/{document_id}/blocks`

#### 2. 목적

원본 문서를 block 단위로 나눈 텍스트 목록을 반환합니다. 답변 인용 클릭 시 원본 block 하이라이트에 사용됩니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | 문서 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`DocumentBlocksResponse`)

```json
{
  "blocks": [
    {
      "block_id": "string",
      "text": "string"
    }
  ],
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/blocks" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "blocks": [
    {
      "block_id": "string",
      "text": "string"
    }
  ],
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: blocks`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-documents-document-id-blocks)

</details>

<a id="summary-put-api-workspaces-workspace-id-documents-document-id-content"></a>
### `PUT /api/workspaces/{workspace_id}/documents/{document_id}/content`

| 항목 | 내용 |
|---|---|
| 목적 | 전체 Markdown과 신규 이미지를 저장합니다. base_revision이 현재 편집 revision과 일치할 때만 반영하며 revision_write_id 재시도는 기존 결과를 반환합니다. 이미지 포함 저장은 metadata part를 사용합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string`<br>**Body** — `apply_operation_id`, `base_revision`, `markdown`, `metadata`, `revision_write_id`, `source` |
| 출력 | `200` 저장 성공 또는 동일 본문 no-op — `DocumentContentSaveResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 Markdown, base_revision 또는 revision_write_id — `ErrorResponse`<br>`403` 문서 소유자가 아님 — `ErrorResponse`<br>`404` 문서 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` 편집 revision 또는 revision_write_id 충돌 — `ErrorResponse`<br>`413` Markdown 5MB 또는 이미지 제한 초과 — `ErrorResponse`<br>`415` 지원하지 않는 이미지 형식 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-put-api-workspaces-workspace-id-documents-document-id-content"></a>
### `PUT /api/workspaces/{workspace_id}/documents/{document_id}/content` 상세

#### 1. Method + Path

`PUT /api/workspaces/{workspace_id}/documents/{document_id}/content`

#### 2. 목적

전체 Markdown과 신규 이미지를 저장합니다. base_revision이 현재 편집 revision과 일치할 때만 반영하며 revision_write_id 재시도는 기존 결과를 반환합니다. 이미지 포함 저장은 metadata part를 사용합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | - |

- Content-Type: `multipart/form-data`

```json
{
  "apply_operation_id": "string",
  "base_revision": "string",
  "markdown": "string",
  "metadata": "string",
  "revision_write_id": "string",
  "source": "string"
}
```

#### 5. Response body

- HTTP `200`: 저장 성공 또는 동일 본문 no-op
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
| `400` | 잘못된 Markdown, base_revision 또는 revision_write_id | `ErrorResponse` |
| `403` | 문서 소유자가 아님 | `ErrorResponse` |
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | 편집 revision 또는 revision_write_id 충돌 | `ErrorResponse` |
| `413` | Markdown 5MB 또는 이미지 제한 초과 | `ErrorResponse` |
| `415` | 지원하지 않는 이미지 형식 | `ErrorResponse` |

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
curl -X PUT "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/content" \
  -H 'Authorization: Bearer <access_token>' \
  -F 'apply_operation_id=<value>' \
  -F 'base_revision=<value>' \
  -F 'markdown=<value>' \
  -F 'metadata=<value>' \
  -F 'revision_write_id=<value>' \
  -F 'source=<value>'
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
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: saveContent`)

[↑ 요약으로 돌아가기](#summary-put-api-workspaces-workspace-id-documents-document-id-content)

</details>

<a id="summary-get-api-workspaces-workspace-id-documents-document-id-export"></a>
### `GET /api/workspaces/{workspace_id}/documents/{document_id}/export`

| 항목 | 내용 |
|---|---|
| 목적 | 최신 Markdown 편집본을 내보냅니다. 관리 이미지가 있으면 이미지와 Markdown을 ZIP으로 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string` |
| 출력 | `200` Markdown 다운로드 — `string` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` workspace, Markdown 문서 또는 편집 상태를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-documents-document-id-export"></a>
### `GET /api/workspaces/{workspace_id}/documents/{document_id}/export` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents/{document_id}/export`

#### 2. 목적

최신 Markdown 편집본을 내보냅니다. 관리 이미지가 있으면 이미지와 Markdown을 ZIP으로 반환합니다.

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

- HTTP `200`: Markdown 다운로드
- Content-Type: `*/*`

```text
<binary>
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | workspace, Markdown 문서 또는 편집 상태를 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/export" \
  -H 'Authorization: Bearer <access_token>'
```

```text
<binary>
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: export`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-documents-document-id-export)

</details>

<a id="summary-get-api-workspaces-workspace-id-documents-document-id-original"></a>
### `GET /api/workspaces/{workspace_id}/documents/{document_id}/original`

| 항목 | 내용 |
|---|---|
| 목적 | MinIO에 저장된 원본 파일을 스트리밍합니다. PDF는 inline, 그 외는 attachment로 반환됩니다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string` |
| 출력 | `200` 원본 파일 반환 — `string` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 문서, 원본 파일 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-documents-document-id-original"></a>
### `GET /api/workspaces/{workspace_id}/documents/{document_id}/original` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents/{document_id}/original`

#### 2. 목적

MinIO에 저장된 원본 파일을 스트리밍합니다. PDF는 inline, 그 외는 attachment로 반환됩니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | 문서 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 원본 파일 반환
- Content-Type: `*/*`

```text
<binary>
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 문서, 원본 파일 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/original" \
  -H 'Authorization: Bearer <access_token>'
```

```text
<binary>
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: getOriginal`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-documents-document-id-original)

</details>

<a id="summary-get-internal-documents-document-id-pipeline-source"></a>
### `GET /internal/documents/{document_id}/pipeline-source`

| 항목 | 내용 |
|---|---|
| 목적 | AI pipeline이 사용할 문서 원본 위치와 소유 범위를 조회합니다. |
| 입력 | **Path** — `document_id`: `string`<br>**Header** — `X-Internal-Token`(필수, 인증 계층 검증): `string` |
| 출력 | `200` 성공 — `object` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `401` 내부 인증 토큰 누락 또는 불일치 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-internal-documents-document-id-pipeline-source"></a>
### `GET /internal/documents/{document_id}/pipeline-source` 상세

#### 1. Method + Path

`GET /internal/documents/{document_id}/pipeline-source`

#### 2. 목적

AI pipeline이 사용할 문서 원본 위치와 소유 범위를 조회합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `document_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `string` | 예 (인증 계층 검증) | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*`

```json
{
}
```

#### 6. Error response

- HTTP `401`: 내부 인증 토큰 누락 또는 불일치

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/internal/documents/<value>/pipeline-source" \
  -H 'X-Internal-Token: <value>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/InternalDocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: findPipelineSource`)

[↑ 요약으로 돌아가기](#summary-get-internal-documents-document-id-pipeline-source)

</details>
