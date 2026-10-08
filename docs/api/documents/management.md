# Document Management API

활성 파일·폴더의 이름은 같은 부모 폴더 안에서 고유해야 한다(최상위는 워크스페이스 루트). 문서 종류가 달라도, 파일과 폴더 사이에서도 같은 이름을 사용할 수 없다. 앞뒤 공백·대소문자·한글 조합 방식만 다른 이름도 중복이다. 생성·이름 변경·이동·복구 시 충돌하면 `409 DUPLICATE_NAME`을 반환한다. 파일 업로드(직접 업로드 포함)는 거절하지 않고, 이름이 겹칠 때만 확장자 앞에 `(2)`부터 빈 번호를 붙인다(`보고서.pdf` → `보고서 (2).pdf`). 실제 저장된 이름은 응답 `filename`으로 확인한다. `보고서.pdf`와 `보고서.md`는 별개 이름이다. 휴지통 문서는 이름을 점유하지 않는다. 복제·채팅 내보내기의 자동 이름도 같은 폴더에서 빈 이름을 골라 번호를 붙인다. 동시 요청이 같은 이름을 선택하면 DB 제약으로 하나를 거절한다.

스킬 참고 문서(`origin=skill_reference`)는 이 이름 공간 밖에 있다. 참고 문서끼리만 워크스페이스 단위로 이름을 비교하며, 업로드는 같은 방식으로 번호를 붙이고 생성·이름 변경·복구 충돌은 `409 DUPLICATE_NAME`이다. 참고 문서는 Markdown만 받고 폴더에 둘 수 없다. 문서 트리·폴더 하위 목록·이름 검색·기본 목록·위키 편입에서 빠지고, `GET /documents?origin=skill_reference`로만 조회한다. 이동(`position`)은 `404`, 편입(`ingest`)은 `400`으로 거절한다. 상세 조회·본문 편집·이름 변경·삭제·복구는 일반 문서와 같고, 휴지통도 같이 쓴다. 스킬 author 요청의 `reference_document_ids`에는 일반 문서와 참고 문서를 모두 넣을 수 있다.

[서비스 문서](../../README.md) / [document-svc](../README.md) / [Documents](README.md)

문서 목록·생성·업로드·조회와 기본 관리 API다.

- API 수: 12

## API 목차

| API | 목적 |
|---|---|
| [`GET /api/workspaces/{workspace_id}/documents`](#summary-get-api-workspaces-workspace-id-documents) | 활성 문서의 호환용 평면 목록을 반환하며 파일명 검색을 지원합니다. |
| [`POST /api/workspaces/{workspace_id}/documents`](#summary-post-api-workspaces-workspace-id-documents) | PDF, Markdown 또는 txt 파일을 업로드합니다. 형식은 Content-Type이 아니라 확장자(.pdf, .md, .markdown, .txt)로 판단하고, PDF는 내용이 %PDF-로 시작해야 합니다. Markdown과 txt(.md 이름으로 저장)는 편집 상태와 처리 큐를 생성하고, PDF는 읽기 전용 원본으로만 저장합니다. |
| [`POST /api/workspaces/{workspace_id}/documents/uploads`](#summary-post-api-workspaces-workspace-id-documents-uploads) | 대용량 PDF를 객체 저장소에 직접 올리기 위한 업로드 티켓과 조각 크기를 발급합니다. |
| [`POST /api/workspaces/{workspace_id}/documents/uploads/parts`](#summary-post-api-workspaces-workspace-id-documents-uploads-parts) | 업로드 티켓으로 조각 번호 범위에 대한 15분짜리 presigned PUT 주소를 발급합니다. |
| [`POST /api/workspaces/{workspace_id}/documents/uploads/complete`](#summary-post-api-workspaces-workspace-id-documents-uploads-complete) | 조각 업로드를 조립해 PDF를 검증하고 문서로 확정합니다. |
| [`POST /api/workspaces/{workspace_id}/documents/uploads/abort`](#summary-post-api-workspaces-workspace-id-documents-uploads-abort) | 진행 중인 조각 업로드를 중단하고 임시 객체를 정리합니다. |
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
| 입력 | **Path** — `workspace_id`: `string`<br>**Query** — `query`(선택): `string`, `origin`(선택): `skill_reference` |
| 출력 | `200` 목록 조회 성공 — `DocumentListResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>필터링: `query`, `origin`<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 허용하지 않는 origin — `ErrorResponse`<br>`404` 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`500` 서버 내부 오류 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-documents"></a>
### `GET /api/workspaces/{workspace_id}/documents` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents`

#### 2. 목적

활성 문서의 호환용 평면 목록을 반환하며 파일명 검색을 지원합니다.

처리가 시작된 문서는 `processing_started_at`을 반환한다. 프론트는 이 서버 시각을 기준으로 Ingest 경과 시간을 표시한다.

스킬 참고 문서는 기본 목록과 `query` 검색에서 빠진다. `origin=skill_reference`를 주면 스킬 참고 문서만 최근 업로드 순으로 반환하고 `query`는 무시한다. 그 밖의 `origin` 값은 `400 INVALID_DOCUMENT_ORIGIN`이다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| query | `query` | `string` | 아니요 | - |
| query | `origin` | `string` | 아니요 | `skill_reference`만 허용. 스킬 참고 문서만 최근 업로드 순으로 반환 |

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
| `400` | 허용하지 않는 origin | `ErrorResponse` (`INVALID_DOCUMENT_ORIGIN`) |
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
- 필터링: `query`, `origin`

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
- 호출자: 프론트엔드 — `src/entities/document/api/document.ts:51`(`fetchDocuments`), `src/entities/wiki/api/wiki.ts:12`(`fetchDocumentData`)
- 하위 호출: 권한 확인(access-svc `GET /internal/authz/workspaces/{id}/users/{id}`, Redis 캐시 miss에만 발생) 외 없음
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-documents)

</details>

<a id="summary-post-api-workspaces-workspace-id-documents"></a>
### `POST /api/workspaces/{workspace_id}/documents`

| 항목 | 내용 |
|---|---|
| 목적 | PDF, Markdown 또는 txt 파일을 업로드합니다. 형식은 Content-Type이 아니라 확장자(.pdf, .md, .markdown, .txt)로 판단하고, PDF는 내용이 %PDF-로 시작해야 합니다. Markdown과 txt(.md 이름으로 저장)는 편집 상태와 처리 큐를 생성하고, PDF는 읽기 전용 원본으로만 저장합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Header** — `Idempotency-Key`: `string`<br>**Query** — `folder_id`(선택): `string`, `origin`(선택): `skill_reference`<br>**Body** — `file` |
| 출력 | `201` 업로드 성공 — `DocumentUploadResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 파일 없음, 허용하지 않는 origin, 참고 문서에 폴더 지정 등 잘못된 요청 — `ErrorResponse`<br>`404` 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` Idempotency-Key 충돌 — `ErrorResponse`<br>`415` 지원하지 않는 확장자, 올바르지 않은 PDF 내용, 스킬 참고 문서로 올린 PDF — `ErrorResponse`<br>`500` 서버 내부 오류 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-documents"></a>
### `POST /api/workspaces/{workspace_id}/documents` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents`

#### 2. 목적

PDF, Markdown 또는 txt 파일을 업로드합니다. 형식은 Content-Type이 아니라 확장자(.pdf, .md, .markdown, .txt)로 판단하고, PDF는 내용이 %PDF-로 시작해야 합니다. Markdown과 txt(.md 이름으로 저장)는 편집 상태와 처리 큐를 생성하고, PDF는 읽기 전용 원본으로만 저장합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |
| query | `folder_id` | `string` | 아니요 | - |
| query | `origin` | `string` | 아니요 | `skill_reference`면 스킬 참고 문서로 올린다. Markdown·txt만 받고 `folder_id`와 함께 쓸 수 없다. 그 밖의 값은 `400 INVALID_DOCUMENT_ORIGIN` |

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
| `400` | 파일 없음 또는 잘못된 요청. 허용하지 않는 origin, 참고 문서에 `folder_id` 지정은 `INVALID_DOCUMENT_ORIGIN` | `ErrorResponse` |
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Idempotency-Key 충돌 | `ErrorResponse` |
| `415` | 지원하지 않는 확장자, 올바르지 않은 PDF 내용, 스킬 참고 문서로 올린 PDF | `ErrorResponse` (`UNSUPPORTED_FILE_TYPE`) |
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
- 필터링: 지원하지 않음 (요청 옵션: `folder_id`, `origin`)

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
- 호출자: 프론트엔드 — `src/entities/document/api/document.ts:70`(`uploadDocumentFile`, multipart). 직접 업로드 플래그가 꺼져 있거나 PDF가 아닐 때 이 경로를 쓴다
- 하위 호출: 객체 저장소(MinIO/S3) 쓰기
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-documents)

</details>

<a id="summary-post-api-workspaces-workspace-id-documents-uploads"></a>
### `POST /api/workspaces/{workspace_id}/documents/uploads`

| 항목 | 내용 |
|---|---|
| 목적 | 대용량 PDF를 객체 저장소에 직접 올리기 위한 업로드 티켓과 조각 크기를 발급합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Body** — `StartRequest` |
| 출력 | `200` 티켓 발급 — `StartResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` PDF 파일명이 올바르지 않음 — `ErrorResponse`<br>`413` 크기가 0 이하이거나 5 TiB 초과 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-documents-uploads"></a>
### `POST /api/workspaces/{workspace_id}/documents/uploads` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/uploads`

#### 2. 목적

원본 PDF를 서버를 거치지 않고 객체 저장소에 직접 올리기 위해 multipart 업로드를 시작한다. 응답의 `ticket`은 조각 주소 발급·확정·중단 요청에 그대로 다시 보낸다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| body | `filename` | `string` | 예 | 255자 이하, `.pdf`로 끝나야 하며 `/`·`\`·제어문자를 포함할 수 없다 |
| body | `size` | `integer(int64)` | 예 | 0보다 크고 5 TiB 이하 |
| body | `folder_id` | `string(uuid)` | 아니요 | 확정 시 문서를 넣을 폴더. 생략하면 최상위 |

```json
{
  "filename": "설계문서.pdf",
  "folder_id": "55555555-5555-5555-5555-555555555555",
  "size": 482913
}
```

#### 5. Response body

- HTTP `200`: 티켓 발급
- Content-Type: `*/*` (`StartResponse`)
- `part_size`는 64 MiB와 `ceil(size / 10000)` 중 큰 값이다. `ticket`은 발급 후 1일간 유효하다.

```json
{
  "part_count": 1,
  "part_size": 67108864,
  "ticket": "<upload-ticket>"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 올바른 PDF 파일명이 아님 | `ErrorResponse` (`DOCUMENT_UPLOAD_REJECTED`) |
| `404` | 워크스페이스를 찾을 수 없음(멤버가 아님) | `ErrorResponse` |
| `413` | 크기가 0 이하이거나 5 TiB 초과 | `ErrorResponse` (`DOCUMENT_UPLOAD_REJECTED`) |

```json
{
  "error": {
    "code": "DOCUMENT_UPLOAD_REJECTED",
    "message": "올바른 PDF 파일명이 필요합니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.
- 티켓은 인증 토큰과 다른 서명 키·audience(`document-upload-ticket`)를 쓰므로 Bearer 토큰으로 쓸 수 없고, 반대도 불가능하다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/uploads" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"filename":"설계문서.pdf","size":482913}'
```

```json
{
  "part_count": 1,
  "part_size": 67108864,
  "ticket": "<upload-ticket>"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentDirectUploadController.java`
- 서비스: `src/main/java/fruition/core/document/service/DocumentDirectUploadService.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: start`)
- 호출자: 프론트엔드 — `src/entities/document/api/multipartUpload.ts:9`(`uploadPdfMultipart`). 경로는 `src/entities/document/api/document.ts:64`에서 `workspacePath(workspaceId, "documents", "uploads")`로 조립한다. `getDocumentTransport().directUpload`와 PDF 확장자일 때만 이 경로를 쓰고, 아니면 `POST .../documents`로 간다
- 하위 호출: 객체 저장소(MinIO/S3) multipart 시작. ai-svc·access-svc 호출 없음
- 배선 상태: 배선됨(조건부 — 직접 업로드 플래그가 켜진 경우)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-documents-uploads)

</details>

<a id="summary-post-api-workspaces-workspace-id-documents-uploads-parts"></a>
### `POST /api/workspaces/{workspace_id}/documents/uploads/parts`

| 항목 | 내용 |
|---|---|
| 목적 | 업로드 티켓으로 조각 번호 범위에 대한 15분짜리 presigned PUT 주소를 발급합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Body** — `PartsRequest` |
| 출력 | `200` 조각 주소 — `PartsResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>티켓의 사용자·워크스페이스가 요청과 같아야 하고, 활성 멤버십을 다시 검증한다. |
| 주요 오류 | `400` 티켓 만료·훼손 또는 조각 범위 오류 — `ErrorResponse`<br>`403` 다른 사용자의 업로드 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-documents-uploads-parts"></a>
### `POST /api/workspaces/{workspace_id}/documents/uploads/parts` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/uploads/parts`

#### 2. 목적

클라이언트가 조각을 객체 저장소에 직접 PUT할 수 있도록 presigned 주소를 한 번에 최대 32개까지 발급한다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| body | `ticket` | `string` | 예 | 시작 응답의 티켓 |
| body | `first_part` | `integer(int32)` | 예 | 1 이상 |
| body | `count` | `integer(int32)` | 예 | 1 이상 32 이하. `first_part + count - 1`이 전체 조각 수를 넘을 수 없다 |

```json
{
  "count": 1,
  "first_part": 1,
  "ticket": "<upload-ticket>"
}
```

#### 5. Response body

- HTTP `200`: 조각 주소
- Content-Type: `*/*` (`PartsResponse`)
- 각 주소는 15분간 유효한 PUT presigned URL이며 `uploadId`·`partNumber` query를 포함한다.

```json
{
  "parts": [
    {
      "part_number": 1,
      "url": "https://<storage>/<bucket>/tmp/document-uploads/<id>?uploadId=...&partNumber=1"
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 티켓이 만료되었거나 올바르지 않음, 또는 조각 범위 오류 | `ErrorResponse` (`DOCUMENT_UPLOAD_REJECTED`) |
| `403` | 다른 사용자의 업로드를 사용할 수 없음 | `ErrorResponse` (`DOCUMENT_UPLOAD_REJECTED`) |
| `404` | 워크스페이스를 찾을 수 없음(멤버가 아님) | `ErrorResponse` |

```json
{
  "error": {
    "code": "DOCUMENT_UPLOAD_REJECTED",
    "message": "업로드 조각 범위가 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음 (`first_part`·`count`로 조각 범위를 나눠 요청한다)
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 티켓의 `sub`(사용자)와 `workspace` claim이 요청과 일치해야 한다. 다르면 `403`이다.
- 일치해도 활성 멤버십을 다시 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/uploads/parts" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"ticket":"<upload-ticket>","first_part":1,"count":1}'
```

```json
{
  "parts": [
    {
      "part_number": 1,
      "url": "https://<storage>/<bucket>/tmp/document-uploads/<id>?uploadId=...&partNumber=1"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentDirectUploadController.java`
- 서비스: `src/main/java/fruition/core/document/service/DocumentDirectUploadService.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: parts`)
- 호출자: 프론트엔드 — `src/entities/document/api/multipartUpload.ts:24`(3개 단위 묶음 요청). `${endpoint}/parts` 템플릿으로 조립한다
- 하위 호출: 객체 저장소(MinIO/S3) `GetPresignedObjectUrl`. ai-svc·access-svc 호출 없음
- 배선 상태: 배선됨(조건부 — 직접 업로드 플래그가 켜진 경우)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-documents-uploads-parts)

</details>

<a id="summary-post-api-workspaces-workspace-id-documents-uploads-complete"></a>
### `POST /api/workspaces/{workspace_id}/documents/uploads/complete`

| 항목 | 내용 |
|---|---|
| 목적 | 조각 업로드를 조립해 PDF를 검증하고 문서로 확정합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Header** — `Idempotency-Key`(필수): `string`<br>**Body** — `CompleteRequest` |
| 출력 | `201` 확정 성공 — `DocumentUploadResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>티켓의 사용자·워크스페이스가 요청과 같아야 하고, 활성 멤버십을 다시 검증한다. |
| 주요 오류 | `400` 티켓 오류, 조각 크기·순서 불일치, 파일 정보 불일치 — `ErrorResponse`<br>`403` 다른 사용자의 업로드 — `ErrorResponse`<br>`409` 전송되지 않은 조각이 있음 또는 Idempotency-Key 충돌 — `ErrorResponse`<br>`415` PDF 내용이 올바르지 않음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-documents-uploads-complete"></a>
### `POST /api/workspaces/{workspace_id}/documents/uploads/complete` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/uploads/complete`

#### 2. 목적

조각을 하나의 객체로 조립하고 크기·Content-Type·`%PDF-` 시그니처를 검증한 뒤, 일반 업로드와 같은 멱등성·폴더 권한 경로로 문서를 생성한다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |
| body | `ticket` | `string` | 예 | 시작 응답의 티켓 |

```json
{
  "ticket": "<upload-ticket>"
}
```

#### 5. Response body

- HTTP `201`: 확정 성공
- Content-Type: `*/*` (`DocumentUploadResponse`)
- 응답이 유실되어 재시도해도 이미 조립된 객체를 재사용하므로 같은 티켓·멱등 키로 안전하게 다시 호출할 수 있다.

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
| `400` | 티켓 오류, 조각 크기·순서 불일치, 업로드한 파일 정보 불일치 | `ErrorResponse` (`DOCUMENT_UPLOAD_REJECTED`) |
| `403` | 다른 사용자의 업로드를 사용할 수 없음 | `ErrorResponse` (`DOCUMENT_UPLOAD_REJECTED`) |
| `409` | 전송되지 않은 조각이 있음 | `ErrorResponse` (`DOCUMENT_UPLOAD_REJECTED`) |
| `409` | Idempotency-Key 충돌 | `ErrorResponse` |
| `415` | PDF 파일 내용이 올바르지 않음 | `ErrorResponse` (`DOCUMENT_UPLOAD_REJECTED`) |

```json
{
  "error": {
    "code": "DOCUMENT_UPLOAD_REJECTED",
    "message": "전송되지 않은 조각이 있습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 티켓의 사용자·워크스페이스 claim이 요청과 일치해야 하고, 활성 멤버십을 다시 검증한다.
- 폴더 권한 검증과 파일명 번호 붙이기는 일반 업로드와 같은 경로에서 처리한다.
- 객체 version ID(로컬 비버전 버킷은 ETag)를 고정해 해시 계산과 원본 저장 사이의 덮어쓰기를 차단한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/uploads/complete" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"ticket":"<upload-ticket>"}'
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

- 진입점: `src/main/java/fruition/core/document/controller/DocumentDirectUploadController.java`
- 서비스: `src/main/java/fruition/core/document/service/DocumentDirectUploadService.java` → `DocumentService.upload`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: complete`)
- 호출자: 프론트엔드 — `src/entities/document/api/multipartUpload.ts:67`(최대 3회 재시도, 같은 `Idempotency-Key` 유지)
- 하위 호출: 객체 저장소(MinIO/S3) multipart 조립·`StatObject`·`ComposeObject`. ai-svc·access-svc 호출 없음
- 배선 상태: 배선됨(조건부 — 직접 업로드 플래그가 켜진 경우)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-documents-uploads-complete)

</details>

<a id="summary-post-api-workspaces-workspace-id-documents-uploads-abort"></a>
### `POST /api/workspaces/{workspace_id}/documents/uploads/abort`

| 항목 | 내용 |
|---|---|
| 목적 | 진행 중인 조각 업로드를 중단하고 임시 객체를 정리합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Body** — `CompleteRequest` |
| 출력 | `204` 중단됨 — 본문 없음 |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>티켓의 사용자·워크스페이스가 요청과 같아야 하고, 활성 멤버십을 다시 검증한다. |
| 주요 오류 | `400` 티켓 만료·훼손 — `ErrorResponse`<br>`403` 다른 사용자의 업로드 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-documents-uploads-abort"></a>
### `POST /api/workspaces/{workspace_id}/documents/uploads/abort` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/uploads/abort`

#### 2. 목적

사용자가 업로드를 취소하면 객체 저장소의 multipart 업로드를 중단해 조각을 남기지 않는다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| body | `ticket` | `string` | 예 | 시작 응답의 티켓 |

```json
{
  "ticket": "<upload-ticket>"
}
```

#### 5. Response body

- HTTP `204`: 중단됨
- 본문 없음

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 티켓이 만료되었거나 올바르지 않음 | `ErrorResponse` (`DOCUMENT_UPLOAD_REJECTED`) |
| `403` | 다른 사용자의 업로드를 사용할 수 없음 | `ErrorResponse` (`DOCUMENT_UPLOAD_REJECTED`) |
| `404` | 워크스페이스를 찾을 수 없음(멤버가 아님) | `ErrorResponse` |

```json
{
  "error": {
    "code": "DOCUMENT_UPLOAD_REJECTED",
    "message": "업로드 확인 정보가 만료되었거나 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 티켓의 사용자·워크스페이스 claim이 요청과 일치해야 하고, 활성 멤버십을 다시 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/uploads/abort" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"ticket":"<upload-ticket>"}'
```

```http
HTTP/1.1 204 No Content
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentDirectUploadController.java`
- 서비스: `src/main/java/fruition/core/document/service/DocumentDirectUploadService.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: abort`)
- 호출자: 프론트엔드 — `src/entities/document/api/multipartUpload.ts:82`(업로드 실패 시 catch 경로)
- 하위 호출: 객체 저장소(MinIO/S3) `AbortMultipartUpload`. ai-svc·access-svc 호출 없음
- 배선 상태: 배선됨(조건부 — 직접 업로드 플래그가 켜진 경우)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-documents-uploads-abort)

</details>

<a id="summary-post-api-workspaces-workspace-id-documents-markdown"></a>
### `POST /api/workspaces/{workspace_id}/documents/markdown`

| 항목 | 내용 |
|---|---|
| 목적 | 표시 이름과 전체 Markdown 본문으로 즉시 편집 가능한 문서를 생성합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Header** — `Idempotency-Key`: `string`<br>**Body** — `MarkdownDocumentCreateRequest` |
| 출력 | `201` 생성 성공 또는 멱등 재요청 — `DocumentUploadResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 본문, Idempotency-Key 또는 origin — `ErrorResponse`<br>`404` 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` Idempotency-Key 충돌 또는 같은 이름 — `ErrorResponse`<br>`413` Markdown 5MB 초과 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-documents-markdown"></a>
### `POST /api/workspaces/{workspace_id}/documents/markdown` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/markdown`

#### 2. 목적

표시 이름과 전체 Markdown 본문으로 즉시 편집 가능한 문서를 생성합니다.

`origin: "skill_reference"`를 주면 스킬 참고 문서로 만든다. `folder_id`와 함께 쓸 수 없고, 그 밖의 `origin` 값은 `400 INVALID_DOCUMENT_ORIGIN`이다. 이름은 참고 문서끼리만 비교한다.

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
| `400` | 잘못된 본문 또는 Idempotency-Key. 허용하지 않는 origin, 참고 문서에 `folder_id` 지정은 `INVALID_DOCUMENT_ORIGIN` | `ErrorResponse` |
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Idempotency-Key 충돌 또는 같은 이름(`DUPLICATE_NAME`) | `ErrorResponse` |
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
- 호출자: 없음 — 프론트엔드 `apiFetch` 호출 지점에 이 경로가 없고, ai-svc·access-svc도 이 서비스의 `/api/**`를 호출하지 않는다
- 하위 호출: 객체 저장소(MinIO/S3) 쓰기
- 배선 상태: **미배선 — 호출자 없음**

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
    "holder_user_id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
    "ttl_ms": 45000
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
    "holder_user_id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
    "ttl_ms": 45000
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
- 호출자: 프론트엔드 — `src/features/note-editing/api/note.ts:31`(`fetchNoteDraft`)
- 하위 호출: 권한 확인(access-svc `GET /internal/authz/workspaces/{id}/users/{id}`, Redis 캐시 miss에만 발생) 외 없음
- 배선 상태: 배선됨

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
- 호출자: 없음 — 프론트엔드 `apiFetch` 호출 지점에 이 경로가 없고, ai-svc·access-svc도 이 서비스의 `/api/**`를 호출하지 않는다
- 하위 호출: 객체 저장소(MinIO/S3) 복제
- 배선 상태: **미배선 — 호출자 없음**

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
- 호출자: 프론트엔드 — `src/entities/tree/api/folders.ts:24`(`mutateTreeItem`, suffix `["position"]`) → `:35`(`moveDocument`)
- 하위 호출: 권한 확인(access-svc `GET /internal/authz/workspaces/{id}/users/{id}`, Redis 캐시 miss에만 발생) 외 없음
- 배선 상태: 배선됨

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
- 호출자: 프론트엔드 — `src/entities/document/api/document.ts:171`(`renameDocument`)
- 하위 호출: 권한 확인(access-svc `GET /internal/authz/workspaces/{id}/users/{id}`, Redis 캐시 miss에만 발생) 외 없음
- 배선 상태: 배선됨

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
- 호출자: access-svc — `src/main/java/fruition/access/workspace/service/DocumentInternalClient.java:60`, 호출 지점 `WorkspaceService.java:148`(`runAfterCommit`, best-effort — 실패 시 warn만 남긴다). base URL은 `app.internal.document-base-url` ← `DOCUMENT_INTERNAL_BASE_URL`, 헤더 `X-Internal-Token`
- 하위 호출: 객체 저장소(MinIO/S3) 쓰기
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-post-internal-workspaces-workspace-id-initial-note)

</details>
