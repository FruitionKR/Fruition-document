# Wiki API

[서비스 문서](../README.md) / [document-svc](README.md)

Wiki 그래프·페이지·기여·유지보수 Gateway API와 사람의 페이지 본문 수정 API다. 조회는 ai-svc 내부 HTTP,
lint·복구는 Kafka `ai.maintenance.command`로 전달한다.

- API 수: 8

## API 목차

| API | 목적 |
|---|---|
| [`GET /api/workspaces/{workspace_id}/wiki/graph`](#summary-get-api-workspaces-workspace-id-wiki-graph) | 모든 Wiki 노드(pages)와 엣지(links)를 반환합니다. 중앙 그래프 렌더링과 답변 후 하이라이트에 사용됩니다. |
| [`GET /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}`](#summary-get-api-workspaces-workspace-id-wiki-pages-wiki-page-id) | 특정 Wiki 페이지의 상세 정보를 반환합니다. source_documents와 related_pages를 포함합니다. |
| [`PUT /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}/content`](#summary-put-api-workspaces-workspace-id-wiki-pages-wiki-page-id-content) | 사람이 고친 본문을 저장합니다. `base_revision` 충돌은 409, 같은 `revision_write_id` 재시도는 처음 결과를 돌려줍니다. |
| [`GET /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}/diff`](#summary-get-api-workspaces-workspace-id-wiki-pages-wiki-page-id-diff) | 두 revision 사이의 diff를 반환합니다. 저장된 본문을 읽어 요청 시점에 계산하며, 사용자가 펼칠 때만 호출됩니다. |
| [`POST /api/workspaces/{workspace_id}/wiki/maintenance/lint`](#summary-post-api-workspaces-workspace-id-wiki-maintenance-lint) | 워크스페이스 Wiki 정합성 검사 실행을 비동기 대기열에 등록합니다. |
| [`GET /api/workspaces/{workspace_id}/wiki/maintenance/runs/{run_id}`](#summary-get-api-workspaces-workspace-id-wiki-maintenance-runs-run-id) | 실행 중이거나 완료된 Wiki 정합성 검사 결과를 반환합니다. |
| [`GET /api/workspaces/{workspace_id}/wiki/maintenance/status`](#summary-get-api-workspaces-workspace-id-wiki-maintenance-status) | 워크스페이스 Wiki 유지보수 작업의 현재 상태를 반환합니다. |
| [`POST /internal/wiki/contributions`](#summary-post-internal-wiki-contributions) | 요청한 Wiki 페이지의 기여 이력을 조회합니다. |

## 한눈에 보기

<a id="summary-get-api-workspaces-workspace-id-wiki-graph"></a>
### `GET /api/workspaces/{workspace_id}/wiki/graph`

| 항목 | 내용 |
|---|---|
| 목적 | 모든 Wiki 노드(pages)와 엣지(links)를 반환합니다. 중앙 그래프 렌더링과 답변 후 하이라이트에 사용됩니다. 응답의 ETag를 `If-None-Match`로 보내면, 그래프가 같을 때 본문 없이 304를 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Header** — `If-None-Match`(선택): 이전 응답의 `ETag` |
| 출력 | `200` 그래프 조회 성공 — `WikiGraphResponse`, `ETag` 헤더<br>`304` 그래프가 같음 — 본문 없음 |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `500` 서버 내부 오류 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-wiki-graph"></a>
### `GET /api/workspaces/{workspace_id}/wiki/graph` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/wiki/graph`

#### 2. 목적

모든 Wiki 노드(pages)와 엣지(links)를 반환합니다. 중앙 그래프 렌더링과 답변 후 하이라이트에 사용됩니다.

**변경 감지(ETag/304)**: 응답에는 그래프 본문의 해시로 만든 `ETag` 헤더가 붙는다. 폴링은 이 값을 `If-None-Match`로 보내고, 304를 받으면 갖고 있던 그래프를 그대로 쓴다.
그래프 원본은 AI 서비스에 있어 서버는 매 요청 AI 서비스에서 그래프를 받아 비교한다. 304는 전송량과 화면 재렌더링을 줄이며, AI 서비스 호출은 줄이지 않는다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 그래프 조회 성공
- Content-Type: `*/*` (`WikiGraphResponse`)

```json
{
  "edges": [
    {
      "confidence": 0.87,
      "from_page_id": "string",
      "label": "string",
      "link_type": "related",
      "to_page_id": "string"
    }
  ],
  "nodes": [
    {
      "id": "string",
      "page_type": "Concept",
      "slug": "search-indexing",
      "source_document": {
        "filename": "설계문서.pdf",
        "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
      },
      "status": "published",
      "summary": "string",
      "title": "검색 인덱싱"
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki/graph" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "edges": [
    {
      "confidence": 0.87,
      "from_page_id": "string",
      "label": "string",
      "link_type": "related",
      "to_page_id": "string"
    }
  ],
  "nodes": [
    {
      "id": "string",
      "page_type": "Concept",
      "slug": "search-indexing",
      "source_document": {
        "filename": "설계문서.pdf",
        "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
      },
      "status": "published",
      "summary": "string",
      "title": "검색 인덱싱"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/wiki/controller/WikiController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: getGraph`)
- 호출자: 프론트엔드 — `src/entities/wiki/api/wiki.ts:21`(`fetchWikiGraph`)
- 하위 호출: ai-svc `GET ${WIKI_STATE_ENDPOINT}/graph?workspace_id` (`PipelineWikiStateRequester`)
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-wiki-graph)

</details>

<a id="summary-get-api-workspaces-workspace-id-wiki-pages-wiki-page-id"></a>
### `GET /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 특정 Wiki 페이지의 상세 정보를 반환합니다. source_documents와 related_pages를 포함합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `wiki_page_id`: `string` |
| 출력 | `200` 페이지 조회 성공 — `WikiPageDetailResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 페이지를 찾을 수 없음 — `ErrorResponse`<br>`500` 서버 내부 오류 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-wiki-pages-wiki-page-id"></a>
### `GET /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}`

#### 2. 목적

특정 Wiki 페이지의 상세 정보를 반환합니다. source_documents와 related_pages를 포함합니다.

- `revision`: 이 페이지의 `wiki_page_versions` 최대 revision. 버전 이력이 아직 없으면 키가 빠진다. `updated_at`은 AI 서비스가 준 페이지 변경 시각이다.
- `markdown`: AI 서비스가 본문을 비워 보내면 최신 버전 본문을, 버전도 없으면 `markdown_uri`(또는 최신 버전의 `markdown_key`) 객체를 읽어 채운다. 그래도 없으면 키가 빠진다. 클라이언트는 `markdown_uri`를 직접 읽지 않아도 된다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `wiki_page_id` | `string` | 예 | Wiki 페이지 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 페이지 조회 성공
- Content-Type: `*/*` (`WikiPageDetailResponse`)

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "id": "string",
  "markdown": "string",
  "markdown_uri": "string",
  "page_type": "Concept",
  "related_pages": [
    {
      "confidence": 0.87,
      "id": "string",
      "label": "string",
      "link_type": "related",
      "page_type": "Concept",
      "slug": "inverted-index",
      "title": "역색인"
    }
  ],
  "revision": 3,
  "slug": "search-indexing",
  "source_documents": [
    {
      "confidence": 0.87,
      "filename": "설계문서.pdf",
      "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "relation_type": "string",
      "source_uri": "string"
    }
  ],
  "status": "published",
  "summary": "string",
  "title": "검색 인덱싱",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 페이지를 찾을 수 없음 | `ErrorResponse` |
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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki/pages/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "id": "string",
  "markdown": "string",
  "markdown_uri": "string",
  "page_type": "Concept",
  "related_pages": [
    {
      "confidence": 0.87,
      "id": "string",
      "label": "string",
      "link_type": "related",
      "page_type": "Concept",
      "slug": "inverted-index",
      "title": "역색인"
    }
  ],
  "revision": 3,
  "slug": "search-indexing",
  "source_documents": [
    {
      "confidence": 0.87,
      "filename": "설계문서.pdf",
      "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "relation_type": "string",
      "source_uri": "string"
    }
  ],
  "status": "published",
  "summary": "string",
  "title": "검색 인덱싱",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/wiki/controller/WikiController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: getPage`)
- 호출자: 프론트엔드 — `src/entities/wiki/api/wiki.ts:112`(`fetchWikiPage`)
- 하위 호출: ai-svc `GET ${WIKI_STATE_ENDPOINT}/pages/{pageId}?workspace_id` (`PipelineWikiStateRequester`), `wiki_page_versions` 최신 행, 본문이 비면 object storage(`WikiObjectReader`)
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-wiki-pages-wiki-page-id)

</details>

<a id="summary-put-api-workspaces-workspace-id-wiki-pages-wiki-page-id-content"></a>
### `PUT /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}/content`

| 항목 | 내용 |
|---|---|
| 목적 | 사람이 고친 Wiki 페이지(source·concept) 본문을 저장합니다(#75). `base_revision`이 현재 revision과 같을 때만 반영하고 revision을 하나 올립니다. |
| 입력 | **Path** — `workspace_id`: `string`, `wiki_page_id`: `string`<br>**Body** — `WikiPageContentSaveRequest`(`markdown`, `base_revision`, `revision_write_id`) |
| 출력 | `200` 저장 성공·같은 본문 no-op·같은 write ID 재시도 — `WikiPageContentSaveResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>OWNER 또는 `wiki.ingest` 권한을 받은 멤버만 고칠 수 있다. |
| 주요 오류 | `400` 잘못된 Markdown·`base_revision`(`INVALID_MARKDOWN_CONTENT`)·`revision_write_id`(`INVALID_IDEMPOTENCY_KEY`)<br>`403` `wiki.ingest` 권한 없음(`WORKSPACE_PERMISSION_REQUIRED`)<br>`404` 워크스페이스 멤버가 아님, 페이지 없음·다른 워크스페이스·비활성(`WIKI_PAGE_NOT_FOUND`)<br>`409` revision 충돌(`WIKI_PAGE_VERSION_CONFLICT`, `error.current_revision`에 현재 revision), 같은 write ID를 다른 요청에 사용(`IDEMPOTENCY_CONFLICT`)<br>`413` Markdown 5MB 초과(`MARKDOWN_CONTENT_TOO_LARGE`)<br>`503` AI 서비스 저장 실패(`WIKI_PAGE_PIPELINE_UNAVAILABLE`) |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-put-api-workspaces-workspace-id-wiki-pages-wiki-page-id-content"></a>
### `PUT /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}/content` 상세

#### 1. Method + Path

`PUT /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}/content`

#### 2. 목적

사람이 고친 본문을 ai의 수동 기여로 저장하고(ai ADR 0031) document가 revision·기여·작업 로그를 기록한다. 규칙은 문서 본문 저장(`PUT .../documents/{id}/content`)과 같다.

- **revision**: `wiki_page_versions`의 최대 revision이 현재 revision이다(상세 조회의 `revision`). 버전 이력이 없는 페이지는 0이다. 페이지 단위 advisory lock(ingest·lint·복구 결과 반영과 같은 잠금)을 쥔 채 비교하고 ai를 호출하므로 그 사이에 다른 revision이 끼지 않는다. 다르면 `409 WIKI_PAGE_VERSION_CONFLICT`이며 ai를 부르지 않는다.
- **같은 본문**: 최신 버전 본문과 같으면 ai를 부르지 않고 현재 revision을 그대로 돌려준다.
- **멱등**: 작업 ID를 `workspace_id`·`wiki_page_id`·`revision_write_id`에서 정한다. 같은 write ID 재시도는 그 작업이 만든 버전을 돌려주고(`base_revision`·본문이 처음과 같아야 한다, 다르면 `409 IDEMPOTENCY_CONFLICT`), ai를 다시 부르지 않는다. ai 저장 뒤 응답을 못 받았거나(`503`) document 기록이 실패한 경우도 같은 write ID로 다시 보내면 ai가 같은 수동 기여를 돌려주고 document가 기록을 채운다.
- **기록**(한 트랜잭션): `ai_operation_logs`에 `wiki_edit` 작업(`succeeded`), `wiki_page_contributions`에 수동 기여(`source_document_id` NULL, `object_key`=ai의 `contribution_key`), `wiki_page_versions`에 새 revision(`markdown_key`=ai의 `markdown_key`, `created_by`=요청자), `ai_operation_changes`에 `wiki_page` `updated` 한 건.
- **복구와의 관계**: 수동 기여는 활성 기여라 ingest·lint 되돌리기의 `keep_contributions`에 남는다. `wiki_edit` 작업 자체를 되돌리는 것은 아직 지원하지 않는다.
- 편집 잠금은 두지 않는다. 클라이언트는 revision 충돌로 판단한다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `wiki_page_id` | `string` | 예 | Wiki 페이지 ID |
| body | `markdown` | `string` | 예 | 전체 본문, UTF-8 5MB 이하 |
| body | `base_revision` | `integer` | 예 | 클라이언트가 조회한 현재 revision. 이력이 없는 페이지는 0 |
| body | `revision_write_id` | `string` | 예 | 같은 저장 재시도에 재사용하는 write ID(1~255자) |

- Content-Type: `application/json` (`WikiPageContentSaveRequest`)

```json
{
  "markdown": "# 캐시\n사람이 고친 본문",
  "base_revision": 3,
  "revision_write_id": "7c0e4f1a-..."
}
```

#### 5. Response body

- HTTP `200`: 저장 성공
- Content-Type: `application/json` (`WikiPageContentSaveResponse`)

```json
{
  "page_id": "string",
  "revision": 4,
  "updated_at": "2026-10-12T04:25:24.371948Z",
  "markdown": "# 캐시\n사람이 고친 본문"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 Markdown, `base_revision` 또는 `revision_write_id` | `ErrorResponse` |
| `403` | `wiki.ingest` 권한 없음(`WORKSPACE_PERMISSION_REQUIRED`) | `ErrorResponse` |
| `404` | 워크스페이스 멤버가 아니거나 페이지를 찾을 수 없음(`WIKI_PAGE_NOT_FOUND`) | `ErrorResponse` |
| `409` | revision 충돌(`WIKI_PAGE_VERSION_CONFLICT`) 또는 write ID 재사용 충돌(`IDEMPOTENCY_CONFLICT`) | `ErrorResponse` |
| `413` | Markdown 5MB 초과 | `ErrorResponse` |
| `503` | AI 서비스 저장 실패. 같은 `revision_write_id`로 다시 시도 | `ErrorResponse` |

```json
{
  "error": {
    "code": "WIKI_PAGE_VERSION_CONFLICT",
    "message": "Wiki 페이지가 이미 변경되었습니다. 최신 본문을 다시 불러와 주세요.",
    "current_revision": 5
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 멤버가 아니면 `404`.
- OWNER 또는 `wiki.ingest` 권한(FruitionKR/Fruition-access#32의 권한 항목 재사용)을 받은 멤버만 고칠 수 있다. 없으면 `403 WORKSPACE_PERMISSION_REQUIRED`.
- 위키는 워크스페이스 공유 자산이라 페이지 작성자는 확인하지 않는다.

#### 9. 예시 요청/응답

```bash
curl -X PUT "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki/pages/<value>/content" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"markdown":"# 캐시","base_revision":3,"revision_write_id":"<uuid>"}'
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/wiki/controller/WikiController.java`
- 서비스: `src/main/java/fruition/core/wiki/service/WikiPageEditService.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: saveWikiPageContent`)
- 호출자: 없음 — 맥 데스크톱 앱이 연동할 예정
- 하위 호출: ai-svc `POST ${WIKI_STATE_ENDPOINT}/pages/lookup`(표시 이름), `PUT ${WIKI_STATE_ENDPOINT}/pages/{pageId}/manual-edit`(`X-Internal-Token`, FruitionKR/Fruition-ai#64)
- 배선 상태: **미배선 — 호출자 없음**. ai의 manual-edit API(Fruition-ai#64)가 배포돼야 동작한다

[↑ 요약으로 돌아가기](#summary-put-api-workspaces-workspace-id-wiki-pages-wiki-page-id-content)

</details>

<a id="summary-get-api-workspaces-workspace-id-wiki-pages-wiki-page-id-diff"></a>
### `GET /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}/diff`

| 항목 | 내용 |
|---|---|
| 목적 | 두 revision 사이의 diff를 반환합니다. 저장된 본문을 읽어 요청 시점에 계산하며, 사용자가 펼칠 때만 호출됩니다. |
| 입력 | **Path** — `workspace_id`: `string`, `wiki_page_id`: `string`<br>**Query** — `from`: `integer`, `to`: `integer` |
| 출력 | `200` 조회 성공 — `WikiPageDiffResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>필터링: `from`, `to`<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 페이지 또는 버전을 찾을 수 없음 — `ErrorResponse`<br>`422` 두 본문의 차이가 너무 커서 비교할 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-wiki-pages-wiki-page-id-diff"></a>
### `GET /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}/diff` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}/diff`

#### 2. 목적

두 revision 사이의 diff를 반환합니다. 저장된 본문을 읽어 요청 시점에 계산하며, 사용자가 펼칠 때만 호출됩니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `wiki_page_id` | `string` | 예 | Wiki 페이지 ID |
| query | `from` | `integer` | 예 | 비교 기준 revision |
| query | `to` | `integer` | 예 | 비교 대상 revision |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`WikiPageDiffResponse`)

```json
{
  "additions": 12,
  "deletions": 4,
  "from_revision": 2,
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
  "page_id": "string",
  "to_revision": 3
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 페이지 또는 버전을 찾을 수 없음 | `ErrorResponse` |
| `422` | 두 본문의 차이가 너무 커서 비교할 수 없음 | `ErrorResponse` |

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
- 필터링: `from`, `to`

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki/pages/<value>/diff?from=1&to=1" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "additions": 12,
  "deletions": 4,
  "from_revision": 2,
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
  "page_id": "string",
  "to_revision": 3
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/wiki/controller/WikiController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: diff`)
- 호출자: 없음 — 프론트엔드 `apiFetch` 호출 지점에 이 경로가 없고, ai-svc·access-svc도 이 서비스의 `/api/**`를 호출하지 않는다
- 하위 호출: 권한 확인(access-svc `GET /internal/authz/workspaces/{id}/users/{id}`, Redis 캐시 miss에만 발생) 외 없음
- 배선 상태: **미배선 — 호출자 없음**

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-wiki-pages-wiki-page-id-diff)

</details>

<a id="summary-post-api-workspaces-workspace-id-wiki-maintenance-lint"></a>
### `POST /api/workspaces/{workspace_id}/wiki/maintenance/lint`

| 항목 | 내용 |
|---|---|
| 목적 | 워크스페이스 Wiki 정합성 검사 실행을 비동기 대기열에 등록합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Body** — `WikiLintRequest` |
| 출력 | `202` Wiki 정합성 검사 실행이 대기열에 등록됨 — `WikiLintResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 검사 옵션 — `ErrorResponse`<br>`403` `wiki.lint` 권한 없음(`WORKSPACE_PERMISSION_REQUIRED`) — `ErrorResponse`<br>`404` 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`503` llmPipeline 사용 불가 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-wiki-maintenance-lint"></a>
### `POST /api/workspaces/{workspace_id}/wiki/maintenance/lint` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/wiki/maintenance/lint`

#### 2. 목적

워크스페이스 Wiki 정합성 검사 실행을 비동기 대기열에 등록합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Content-Type: `application/json` (`WikiLintRequest`)

```json
{
  "dry_run": true,
  "materialize_promotions": false
}
```

#### 5. Response body

- HTTP `202`: Wiki 정합성 검사 실행이 대기열에 등록됨
- Content-Type: `*/*` (`WikiLintResponse`)

```json
{
  "operation_id": "string",
  "run_id": "string",
  "status": "queued"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 검사 옵션 | `ErrorResponse` |
| `403` | `wiki.lint` 권한 없음(`WORKSPACE_PERMISSION_REQUIRED`, OWNER는 항상 가능). `dry_run`도 같다 | `ErrorResponse` |
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
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
- 모든 검사 요청(`dry_run` 포함)에 `wiki.lint` 권한(OWNER는 항상 가능)이 필요하다. 실행 결과·상태 조회는 멤버면 가능하다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki/maintenance/lint" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"dry_run":true,"materialize_promotions":false}'
```

```json
{
  "operation_id": "string",
  "run_id": "string",
  "status": "queued"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/wikimaintenance/controller/WikiMaintenanceController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: lint`)
- 호출자: 프론트엔드 — `src/features/document-notifications/api/wikiLint.ts:25`(`requestWikiLint`)
- 하위 호출: HTTP 호출 없음. Kafka `ai.maintenance.command`로 전달한다. 모델 선택에 access-svc `WorkspaceAiModelClient`를 쓴다
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-wiki-maintenance-lint)

</details>

<a id="summary-get-api-workspaces-workspace-id-wiki-maintenance-runs-run-id"></a>
### `GET /api/workspaces/{workspace_id}/wiki/maintenance/runs/{run_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 실행 중이거나 완료된 Wiki 정합성 검사 결과를 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `run_id`: `string` |
| 출력 | `200` 결과 조회 성공 — `JsonNode` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 검사 실행 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-wiki-maintenance-runs-run-id"></a>
### `GET /api/workspaces/{workspace_id}/wiki/maintenance/runs/{run_id}` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/wiki/maintenance/runs/{run_id}`

#### 2. 목적

실행 중이거나 완료된 Wiki 정합성 검사 결과를 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `run_id` | `string` | 예 | 조회할 검사 실행 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 결과 조회 성공
- Content-Type: `*/*` (`JsonNode`)

```json
{
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 검사 실행 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki/maintenance/runs/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/wikimaintenance/controller/WikiMaintenanceController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: run`)
- 호출자: 프론트엔드 — `src/features/document-notifications/api/wikiLint.ts:39`(폴링)
- 하위 호출: ai-svc `GET ${PROCESSING_ENDPOINT}/{runId}` (`PipelineRunStatusRequester`)
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-wiki-maintenance-runs-run-id)

</details>

<a id="summary-get-api-workspaces-workspace-id-wiki-maintenance-status"></a>
### `GET /api/workspaces/{workspace_id}/wiki/maintenance/status`

| 항목 | 내용 |
|---|---|
| 목적 | 워크스페이스 Wiki 유지보수 작업의 현재 상태를 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string` |
| 출력 | `200` 상태 조회 성공 — `WikiMaintenanceStatusResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-wiki-maintenance-status"></a>
### `GET /api/workspaces/{workspace_id}/wiki/maintenance/status` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/wiki/maintenance/status`

#### 2. 목적

워크스페이스 Wiki 유지보수 작업의 현재 상태를 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 상태 조회 성공
- Content-Type: `*/*` (`WikiMaintenanceStatusResponse`)

```json
{
  "last_lint_at": "2026-08-13T04:25:24.371948Z",
  "last_wiki_change_at": "2026-08-13T04:25:24.371948Z",
  "needs_lint": true
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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki/maintenance/status" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "last_lint_at": "2026-08-13T04:25:24.371948Z",
  "last_wiki_change_at": "2026-08-13T04:25:24.371948Z",
  "needs_lint": true
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/wikimaintenance/controller/WikiMaintenanceController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: status`)
- 호출자: 프론트엔드 — `src/features/document-notifications/api/wikiLint.ts:17`(`fetchWikiMaintenanceStatus`)
- 하위 호출: ai-svc `GET ${WIKI_STATE_ENDPOINT}/workspaces/{wsId}/last-updated` (`PipelineWikiStateRequester`) — 서비스 계층을 한 줄씩 확인하지는 않았다
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-wiki-maintenance-status)

</details>

<a id="summary-post-internal-wiki-contributions"></a>
### `POST /internal/wiki/contributions`

| 항목 | 내용 |
|---|---|
| 목적 | 요청한 Wiki 페이지의 기여 이력을 조회합니다. |
| 입력 | **Header** — `X-Internal-Token`(필수, 인증 계층 검증): `string`<br>**Body** — `ContributionRequest` |
| 출력 | `200` 성공 — `object` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `401` 내부 인증 토큰 누락 또는 불일치 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-internal-wiki-contributions"></a>
### `POST /internal/wiki/contributions` 상세

#### 1. Method + Path

`POST /internal/wiki/contributions`

#### 2. 목적

요청한 Wiki 페이지의 기여 이력을 조회합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `string` | 예 (인증 계층 검증) | - |

- Content-Type: `application/json` (`ContributionRequest`)

```json
{
  "page_ids": [
    "string"
  ],
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
curl -X POST "$DOCUMENT/internal/wiki/contributions" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"page_ids":["<value>"],"workspace_id":"<value>"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/wiki/controller/InternalWikiContributionController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: find`)
- 호출자: ai-svc — `pipeline/app/modules/wiki_ingestion/infrastructure/backend_document_reader.py:52`(`read_contributions`), 호출 지점 `postgres_wiki_ingestion_repository.py:1482`, `app/workers/task_worker.py:449`
- 하위 호출: 없음. core DB만 읽는다
- 배선 상태: 배선됨

[↑ 요약으로 돌아가기](#summary-post-internal-wiki-contributions)

</details>
