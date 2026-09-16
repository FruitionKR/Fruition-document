# Document Management API

활성 문서의 파일명은 확장자를 포함해 워크스페이스 전체에서 고유해야 한다. 폴더 위치·문서 종류가 달라도 같은 파일명을 사용할 수 없다. 앞뒤 공백·대소문자·한글 조합 방식만 다른 이름도 중복이다. 업로드·생성·이름 변경·복구 시 충돌하면 `409 DUPLICATE_NAME`을 반환한다. `보고서.pdf`와 `보고서.md`는 별개 이름이다. 휴지통 문서는 이름을 점유하지 않는다. 복제·채팅 내보내기의 자동 이름은 워크스페이스 전체에서 빈 이름을 골라 번호를 붙이고, 동시 요청이 같은 이름을 선택하면 DB 제약으로 하나를 거절한다.

[서비스 문서](../../README.md) / [document-svc](../README.md) / [Documents](README.md)

문서 목록·생성·업로드·조회와 기본 관리 API다.

- API 수: 8

## API 목차

| API | 목적 |
|---|---|
| [`GET /api/workspaces/{workspace_id}/documents`](#summary-get-api-workspaces-workspace-id-documents) | 활성 문서의 호환용 평면 목록을 반환하며 파일명 검색을 지원합니다. |
| [`POST /api/workspaces/{workspace_id}/documents`](#summary-post-api-workspaces-workspace-id-documents) | PDF 또는 Markdown 파일을 업로드합니다. Markdown은 편집 상태와 처리 큐를 생성하고, PDF는 읽기 전용 원본으로만 저장합니다. |
| [`POST /api/workspaces/{workspace_id}/documents/markdown`](#summary-post-api-workspaces-workspace-id-documents-markdown) | 표시 이름과 전체 Markdown 본문으로 즉시 편집 가능한 문서를 생성합니다. |
| [`GET /api/workspaces/{workspace_id}/documents/{document_id}`](#summary-get-api-workspaces-workspace-id-documents-document-id) | 특정 문서의 상세 정보를 반환합니다. 연결된 Wiki 페이지 목록이 포함됩니다. |
| [`POST /api/workspaces/{workspace_id}/documents/{document_id}/duplicate`](#summary-post-api-workspaces-workspace-id-documents-document-id-duplicate) | 문서 소유자가 최신 Markdown 편집본을 같은 부모의 마지막 위치에 새 문서로 복제합니다. |
| [`PATCH /api/workspaces/{workspace_id}/documents/{document_id}/position`](#summary-patch-api-workspaces-workspace-id-documents-document-id-position) | 문서를 대상 폴더와 정렬 위치로 이동합니다. base version과 Idempotency-Key로 동시 변경을 검증합니다. |
| [`PATCH /api/workspaces/{workspace_id}/documents/{document_id}/rename`](#summary-patch-api-workspaces-workspace-id-documents-document-id-rename) | Notion의 page title처럼 표시 이름만 변경하며 본문과 Wiki 제목은 유지합니다. |
| [`POST /internal/workspaces/{workspace_id}/initial-note`](#summary-post-internal-workspaces-workspace-id-initial-note) | 새 워크스페이스에 기본 Markdown 문서를 생성합니다. |

## 한눈에 보기

<a id="summary-get-api-workspaces-workspace-id-documents"></a>
### `GET /api/workspaces/{workspace_id}/documents`

| 항목 | 내용 |
|---|---|
| 목적 | 활성 문서의 호환용 평면 목록을 반환하며 파일명 검색을 지원합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Query** — `query`(선택): `string` |
| 출력 | `200` 목록 조회 성공 — `DocumentListResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>필터링: `query`<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`500` 서버 내부 오류 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-documents"></a>
### `GET /api/workspaces/{workspace_id}/documents` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents`

#### 2. 목적

활성 문서의 호환용 평면 목록을 반환하며 파일명 검색을 지원합니다.

처리가 시작된 문서는 `processing_started_at`을 반환한다. 프론트는 이 서버 시각을 기준으로 Ingest 경과 시간을 표시한다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| query | `query` | `string` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 목록 조회 성공
- Content-Type: `*/*` (`DocumentListResponse`)

```json
{
  "documents": [
    {
      "area": "string",
      "byte_size": 482913,
      "current_version": 1,
      "display_name": "설계문서",
      "document_role": "EDITABLE",
      "editable": false,
      "error_message": "string",
      "extracted_text_uri": "string",
      "file_type": "pdf",
      "filename": "설계문서.pdf",
      "processing_started_at": "2026-08-13T04:25:24.371948Z"
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `500` | 서버 내부 오류 | `ErrorResponse` |

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
- 필터링: `query`

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents?query=<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "documents": [
    {
      "area": "string",
      "byte_size": 482913,
      "current_version": 1,
      "display_name": "설계문서",
      "document_role": "EDITABLE",
      "editable": false,
      "error_message": "string",
      "extracted_text_uri": "string",
      "file_type": "pdf",
      "filename": "설계문서.pdf",
      "processing_started_at": "2026-08-13T04:25:24.371948Z"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: list`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-documents)

</details>

<a id="summary-post-api-workspaces-workspace-id-documents"></a>
### `POST /api/workspaces/{workspace_id}/documents`

| 항목 | 내용 |
|---|---|
| 목적 | PDF 또는 Markdown 파일을 업로드합니다. Markdown은 편집 상태와 처리 큐를 생성하고, PDF는 읽기 전용 원본으로만 저장합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Header** — `Idempotency-Key`: `string`<br>**Query** — `folder_id`(선택): `string`<br>**Body** — `file` |
| 출력 | `201` 업로드 성공 — `DocumentUploadResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 파일 없음 또는 잘못된 요청 — `ErrorResponse`<br>`404` 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` Idempotency-Key 충돌 — `ErrorResponse`<br>`415` 지원하지 않는 파일 형식 — `ErrorResponse`<br>`500` 서버 내부 오류 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-documents"></a>
### `POST /api/workspaces/{workspace_id}/documents` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents`

#### 2. 목적

PDF 또는 Markdown 파일을 업로드합니다. Markdown은 편집 상태와 처리 큐를 생성하고, PDF는 읽기 전용 원본으로만 저장합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |
| query | `folder_id` | `string` | 아니요 | - |

- Content-Type: `multipart/form-data`

```json
{
  "file": "<binary>"
}
```

#### 5. Response body

- HTTP `201`: 업로드 성공
- Content-Type: `*/*` (`DocumentUploadResponse`)

```json
{
  "byte_size": 482913,
  "current_version": 1,
  "document_role": "EDITABLE",
  "editable": false,
  "filename": "설계문서.pdf",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "mime_type": "application/pdf",
  "source_uri": "string",
  "status": "uploaded",
  "uploaded_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 파일 없음 또는 잘못된 요청 | `ErrorResponse` |
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Idempotency-Key 충돌 | `ErrorResponse` |
| `415` | 지원하지 않는 파일 형식 | `ErrorResponse` |
| `500` | 서버 내부 오류 | `ErrorResponse` |

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
- 필터링: 지원하지 않음 (요청 옵션: `folder_id`)

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents?folder_id=55555555-5555-5555-5555-555555555555" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -F 'file=@<file>'
```

```json
{
  "byte_size": 482913,
  "current_version": 1,
  "document_role": "EDITABLE",
  "editable": false,
  "filename": "설계문서.pdf",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "mime_type": "application/pdf",
  "source_uri": "string",
  "status": "uploaded",
  "uploaded_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: upload`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-documents)

</details>

<a id="summary-post-api-workspaces-workspace-id-documents-markdown"></a>
### `POST /api/workspaces/{workspace_id}/documents/markdown`

| 항목 | 내용 |
|---|---|
| 목적 | 표시 이름과 전체 Markdown 본문으로 즉시 편집 가능한 문서를 생성합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Header** — `Idempotency-Key`: `string`<br>**Body** — `MarkdownDocumentCreateRequest` |
| 출력 | `201` 생성 성공 또는 멱등 재요청 — `DocumentUploadResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 본문 또는 Idempotency-Key — `ErrorResponse`<br>`404` 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` Idempotency-Key 충돌 — `ErrorResponse`<br>`413` Markdown 5MB 초과 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-documents-markdown"></a>
### `POST /api/workspaces/{workspace_id}/documents/markdown` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/markdown`

#### 2. 목적

표시 이름과 전체 Markdown 본문으로 즉시 편집 가능한 문서를 생성합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`MarkdownDocumentCreateRequest`)

```json
{
  "display_name": "회의록",
  "folder_id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "markdown": "# 회의록\n\n- 첫 번째 안건"
}
```

#### 5. Response body

- HTTP `201`: 생성 성공 또는 멱등 재요청
- Content-Type: `*/*` (`DocumentUploadResponse`)

```json
{
  "byte_size": 482913,
  "current_version": 1,
  "document_role": "EDITABLE",
  "editable": false,
  "filename": "설계문서.pdf",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "mime_type": "application/pdf",
  "source_uri": "string",
  "status": "uploaded",
  "uploaded_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 본문 또는 Idempotency-Key | `ErrorResponse` |
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Idempotency-Key 충돌 | `ErrorResponse` |
| `413` | Markdown 5MB 초과 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/markdown" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"display_name":"회의록","folder_id":"8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2","markdown":"# 회의록\n\n- 첫 번째 안건"}'
```

```json
{
  "byte_size": 482913,
  "current_version": 1,
  "document_role": "EDITABLE",
  "editable": false,
  "filename": "설계문서.pdf",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "mime_type": "application/pdf",
  "source_uri": "string",
  "status": "uploaded",
  "uploaded_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: createMarkdown`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-documents-markdown)

</details>

<a id="summary-get-api-workspaces-workspace-id-documents-document-id"></a>
### `GET /api/workspaces/{workspace_id}/documents/{document_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 특정 문서의 상세 정보를 반환합니다. 연결된 Wiki 페이지 목록이 포함됩니다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string` |
| 출력 | `200` 상세 조회 성공 — `DocumentDetailResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 문서 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`500` 서버 내부 오류 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-documents-document-id"></a>
### `GET /api/workspaces/{workspace_id}/documents/{document_id}` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents/{document_id}`

#### 2. 목적

특정 문서의 상세 정보를 반환합니다. 연결된 Wiki 페이지 목록이 포함됩니다.

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

- HTTP `200`: 상세 조회 성공
- Content-Type: `*/*` (`DocumentDetailResponse`)

```json
{
  "byte_size": 482913,
  "current_version": 3,
  "display_name": "설계문서",
  "document_role": "EDITABLE",
  "edit_lock": {
    "expires_at": "2026-08-13T04:25:24.371948Z",
    "holder_display_name": "표시 이름",
    "holder_user_id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
  },
  "edit_revision": 12,
  "editable": true,
  "error_message": "string",
  "extracted_text_uri": "string",
  "file_type": "pdf"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `500` | 서버 내부 오류 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "byte_size": 482913,
  "current_version": 3,
  "display_name": "설계문서",
  "document_role": "EDITABLE",
  "edit_lock": {
    "expires_at": "2026-08-13T04:25:24.371948Z",
    "holder_display_name": "표시 이름",
    "holder_user_id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
  },
  "edit_revision": 12,
  "editable": true,
  "error_message": "string",
  "extracted_text_uri": "string",
  "file_type": "pdf"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: getById`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-documents-document-id)

</details>

<a id="summary-post-api-workspaces-workspace-id-documents-document-id-duplicate"></a>
### `POST /api/workspaces/{workspace_id}/documents/{document_id}/duplicate`

| 항목 | 내용 |
|---|---|
| 목적 | 문서 소유자가 최신 Markdown 편집본을 같은 부모의 마지막 위치에 새 문서로 복제합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string`<br>**Header** — `Idempotency-Key`: `string` |
| 출력 | `201` 복제 성공 또는 멱등 재요청 — `DocumentDuplicateResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 Idempotency-Key — `ErrorResponse`<br>`403` 문서 소유자가 아니거나 편집 문서가 아님 — `ErrorResponse`<br>`404` 문서 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` Idempotency-Key 충돌 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-documents-document-id-duplicate"></a>
### `POST /api/workspaces/{workspace_id}/documents/{document_id}/duplicate` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/{document_id}/duplicate`

#### 2. 목적

문서 소유자가 최신 Markdown 편집본을 같은 부모의 마지막 위치에 새 문서로 복제합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | - |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Body: 없음

#### 5. Response body

- HTTP `201`: 복제 성공 또는 멱등 재요청
- Content-Type: `*/*` (`DocumentDuplicateResponse`)

```json
{
  "byte_size": 482913,
  "current_version": 1,
  "display_name": "설계문서 (사본)",
  "filename": "설계문서 (사본).pdf",
  "folder_id": "55555555-5555-5555-5555-555555555555",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "mime_type": "application/pdf",
  "sort_order": 2048,
  "source_document_id": "doc_8d4f1e6c3b0a97d25e4f831b9f4c7e2a"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 Idempotency-Key | `ErrorResponse` |
| `403` | 문서 소유자가 아니거나 편집 문서가 아님 | `ErrorResponse` |
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Idempotency-Key 충돌 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/duplicate" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>'
```

```json
{
  "byte_size": 482913,
  "current_version": 1,
  "display_name": "설계문서 (사본)",
  "filename": "설계문서 (사본).pdf",
  "folder_id": "55555555-5555-5555-5555-555555555555",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "mime_type": "application/pdf",
  "sort_order": 2048,
  "source_document_id": "doc_8d4f1e6c3b0a97d25e4f831b9f4c7e2a"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: duplicate`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-documents-document-id-duplicate)

</details>

<a id="summary-patch-api-workspaces-workspace-id-documents-document-id-position"></a>
### `PATCH /api/workspaces/{workspace_id}/documents/{document_id}/position`

| 항목 | 내용 |
|---|---|
| 목적 | 문서를 대상 폴더와 정렬 위치로 이동합니다. base version과 Idempotency-Key로 동시 변경을 검증합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string`<br>**Header** — `Idempotency-Key`: `string`<br>**Body** — `DocumentPositionRequest` |
| 출력 | `200` 이동 성공 또는 멱등 재요청 — `DocumentPositionResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 위치 또는 version, 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) — `ErrorResponse`<br>`404` 문서, 대상 폴더 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` version 충돌, IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용) — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-patch-api-workspaces-workspace-id-documents-document-id-position"></a>
### `PATCH /api/workspaces/{workspace_id}/documents/{document_id}/position` 상세

#### 1. Method + Path

`PATCH /api/workspaces/{workspace_id}/documents/{document_id}/position`

#### 2. 목적

문서를 대상 폴더와 정렬 위치로 이동합니다. base version과 Idempotency-Key로 동시 변경을 검증합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | 이동할 문서 ID |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`DocumentPositionRequest`)

```json
{
  "base_version": 1,
  "folder_id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "position": 0
}
```

#### 5. Response body

- HTTP `200`: 이동 성공 또는 멱등 재요청
- Content-Type: `*/*` (`DocumentPositionResponse`)

```json
{
  "current_version": 2,
  "folder_id": "55555555-5555-5555-5555-555555555555",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "sort_order": 1024
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 위치 또는 version, 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) | `ErrorResponse` |
| `404` | 문서, 대상 폴더 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | version 충돌, IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용) | `ErrorResponse` |

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
curl -X PATCH "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/position" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":1,"folder_id":"8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2","position":0}'
```

```json
{
  "current_version": 2,
  "folder_id": "55555555-5555-5555-5555-555555555555",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "sort_order": 1024
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentPositionController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: move_1`)

[↑ 요약으로 돌아가기](#summary-patch-api-workspaces-workspace-id-documents-document-id-position)

</details>

<a id="summary-patch-api-workspaces-workspace-id-documents-document-id-rename"></a>
### `PATCH /api/workspaces/{workspace_id}/documents/{document_id}/rename`

| 항목 | 내용 |
|---|---|
| 목적 | Notion의 page title처럼 표시 이름만 변경하며 본문과 Wiki 제목은 유지합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string`<br>**Body** — `DocumentRenameRequest` |
| 출력 | `200` 이름 변경 성공 — `DocumentRenameResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 유효하지 않은 파일명 — `ErrorResponse`<br>`403` 문서 소유자가 아님 — `ErrorResponse`<br>`404` 문서 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` 문서 version 충돌 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-patch-api-workspaces-workspace-id-documents-document-id-rename"></a>
### `PATCH /api/workspaces/{workspace_id}/documents/{document_id}/rename` 상세

#### 1. Method + Path

`PATCH /api/workspaces/{workspace_id}/documents/{document_id}/rename`

#### 2. 목적

Notion의 page title처럼 표시 이름만 변경하며 본문과 Wiki 제목은 유지합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | 문서 ID |

- Content-Type: `application/json` (`DocumentRenameRequest`)

```json
{
  "base_version": 1,
  "display_name": "이름 바꾼 회의록"
}
```

#### 5. Response body

- HTTP `200`: 이름 변경 성공
- Content-Type: `*/*` (`DocumentRenameResponse`)

```json
{
  "changed": true,
  "current_version": 2,
  "display_name": "이름 바꾼 회의록",
  "filename": "회의록.md",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 유효하지 않은 파일명 | `ErrorResponse` |
| `403` | 문서 소유자가 아님 | `ErrorResponse` |
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
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
curl -X PATCH "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/rename" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":1,"display_name":"이름 바꾼 회의록"}'
```

```json
{
  "changed": true,
  "current_version": 2,
  "display_name": "이름 바꾼 회의록",
  "filename": "회의록.md",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: rename_2`)

[↑ 요약으로 돌아가기](#summary-patch-api-workspaces-workspace-id-documents-document-id-rename)

</details>

<a id="summary-post-internal-workspaces-workspace-id-initial-note"></a>
### `POST /internal/workspaces/{workspace_id}/initial-note`

| 항목 | 내용 |
|---|---|
| 목적 | 새 워크스페이스에 기본 Markdown 문서를 생성합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Header** — `X-Internal-Token`(필수, 인증 계층 검증): `string`<br>**Body** — `InitialNoteRequest` |
| 출력 | `204` 생성 완료 — 본문 없음 |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `401` 내부 인증 토큰 누락 또는 불일치 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-internal-workspaces-workspace-id-initial-note"></a>
### `POST /internal/workspaces/{workspace_id}/initial-note` 상세

#### 1. Method + Path

`POST /internal/workspaces/{workspace_id}/initial-note`

#### 2. 목적

새 워크스페이스에 기본 Markdown 문서를 생성합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `string` | 예 (인증 계층 검증) | - |

- Content-Type: `application/json` (`InitialNoteRequest`)

```json
{
  "user_id": "string"
}
```

#### 5. Response body

- HTTP `204`: 생성 완료
- Body: 없음

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
curl -X POST "$DOCUMENT/internal/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/initial-note" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"user_id":"<value>"}'
```

응답 본문 없음.

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/InternalDocumentController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: createInitialNote`)

[↑ 요약으로 돌아가기](#summary-post-internal-workspaces-workspace-id-initial-note)

</details>
