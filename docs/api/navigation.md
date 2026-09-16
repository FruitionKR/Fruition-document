# Navigation API

활성 폴더명은 상위 폴더와 무관하게 워크스페이스 전체에서 고유해야 한다. 앞뒤 공백·대소문자·한글 조합 방식만 다른 이름도 중복이다. 생성·이름 변경·복구 시 충돌하면 `409 DUPLICATE_NAME`을 반환한다. 폴더 복구에 포함된 문서의 파일명이 충돌해도 전체 복구를 취소한다. 삭제한 항목은 이름을 점유하지 않는다.

[서비스 문서](../README.md) / [document-svc](README.md)

폴더 관리와 문서 트리 탐색 API다.

- API 수: 10

## API 목차

| API | 목적 |
|---|---|
| [`POST /api/workspaces/{workspace_id}/folders`](#summary-post-api-workspaces-workspace-id-folders) | 워크스페이스의 최상위 또는 지정한 상위 폴더 아래에 새 폴더를 생성합니다. |
| [`PATCH /api/workspaces/{workspace_id}/folders/{folder_id}`](#summary-patch-api-workspaces-workspace-id-folders-folder-id) | 폴더 이름을 변경하고 base version으로 동시 변경을 검증합니다. |
| [`DELETE /api/workspaces/{workspace_id}/folders/{folder_id}`](#summary-delete-api-workspaces-workspace-id-folders-folder-id) | 폴더와 하위 항목을 휴지통 상태로 전환하며 base version으로 동시 변경을 검증합니다. |
| [`GET /api/workspaces/{workspace_id}/folders/{folder_id}/children`](#summary-get-api-workspaces-workspace-id-folders-folder-id-children) | 폴더 바로 아래의 하위 폴더와 문서를 정렬 순서로 반환합니다. |
| [`PATCH /api/workspaces/{workspace_id}/folders/{folder_id}/position`](#summary-patch-api-workspaces-workspace-id-folders-folder-id-position) | 폴더를 대상 상위 폴더와 정렬 위치로 이동합니다. 자기 자신이나 하위 폴더로는 이동할 수 없습니다. |
| [`POST /api/workspaces/{workspace_id}/folders/{folder_id}/restore`](#summary-post-api-workspaces-workspace-id-folders-folder-id-restore) | 삭제된 폴더와 하위 항목을 복구하고 유효한 탐색 위치에 배치합니다. |
| [`GET /api/workspaces/{workspace_id}/document-tree`](#summary-get-api-workspaces-workspace-id-document-tree) | 모든 폴더를 펼친 상태의 활성 폴더·문서 계층을 한 번에 반환합니다. |
| [`GET /api/workspaces/{workspace_id}/navigation`](#summary-get-api-workspaces-workspace-id-navigation) | 워크스페이스 최상위의 폴더와 문서를 정렬 순서로 반환합니다. |
| [`GET /api/workspaces/{workspace_id}/navigation/breadcrumb`](#summary-get-api-workspaces-workspace-id-navigation-breadcrumb) | 폴더 또는 문서까지 이어지는 상위 폴더 경로를 최상위부터 반환합니다. |
| [`GET /api/workspaces/{workspace_id}/navigation/search`](#summary-get-api-workspaces-workspace-id-navigation-search) | 워크스페이스의 폴더 이름과 문서 파일명을 검색해 계층 경로를 반환합니다. |

## 한눈에 보기

<a id="summary-post-api-workspaces-workspace-id-folders"></a>
### `POST /api/workspaces/{workspace_id}/folders`

| 항목 | 내용 |
|---|---|
| 목적 | 워크스페이스의 최상위 또는 지정한 상위 폴더 아래에 새 폴더를 생성합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Header** — `Idempotency-Key`: `string`<br>**Body** — `FolderCreateRequest` |
| 출력 | `201` 생성 성공 또는 멱등 재요청 — `FolderResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 이름 또는 위치, 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) — `ErrorResponse`<br>`404` 워크스페이스 또는 상위 폴더를 찾을 수 없음 — `ErrorResponse`<br>`409` IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용) — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-folders"></a>
### `POST /api/workspaces/{workspace_id}/folders` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/folders`

#### 2. 목적

워크스페이스의 최상위 또는 지정한 상위 폴더 아래에 새 폴더를 생성합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`FolderCreateRequest`)

```json
{
  "name": "설계",
  "parent_folder_id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2"
}
```

#### 5. Response body

- HTTP `201`: 생성 성공 또는 멱등 재요청
- Content-Type: `*/*` (`FolderResponse`)

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "current_version": 1,
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "name": "설계",
  "parent_folder_id": "55555555-5555-5555-5555-555555555555",
  "sort_order": 1024,
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 이름 또는 위치, 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) | `ErrorResponse` |
| `404` | 워크스페이스 또는 상위 폴더를 찾을 수 없음 | `ErrorResponse` |
| `409` | IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용) | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/folders" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"name":"설계","parent_folder_id":"8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2"}'
```

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "current_version": 1,
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "name": "설계",
  "parent_folder_id": "55555555-5555-5555-5555-555555555555",
  "sort_order": 1024,
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/FolderController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: create`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-folders)

</details>

<a id="summary-patch-api-workspaces-workspace-id-folders-folder-id"></a>
### `PATCH /api/workspaces/{workspace_id}/folders/{folder_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 폴더 이름을 변경하고 base version으로 동시 변경을 검증합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `folder_id`: `string`<br>**Header** — `Idempotency-Key`: `string`<br>**Body** — `FolderRenameRequest` |
| 출력 | `200` 변경 성공 또는 멱등 재요청 — `FolderResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 이름 또는 version, 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) — `ErrorResponse`<br>`404` 폴더 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` version 충돌, IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용) — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-patch-api-workspaces-workspace-id-folders-folder-id"></a>
### `PATCH /api/workspaces/{workspace_id}/folders/{folder_id}` 상세

#### 1. Method + Path

`PATCH /api/workspaces/{workspace_id}/folders/{folder_id}`

#### 2. 목적

폴더 이름을 변경하고 base version으로 동시 변경을 검증합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `folder_id` | `string` | 예 | 이름을 변경할 폴더 ID |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`FolderRenameRequest`)

```json
{
  "base_version": 1,
  "name": "설계 문서"
}
```

#### 5. Response body

- HTTP `200`: 변경 성공 또는 멱등 재요청
- Content-Type: `*/*` (`FolderResponse`)

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "current_version": 1,
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "name": "설계",
  "parent_folder_id": "55555555-5555-5555-5555-555555555555",
  "sort_order": 1024,
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 이름 또는 version, 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) | `ErrorResponse` |
| `404` | 폴더 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
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
curl -X PATCH "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/folders/55555555-5555-5555-5555-555555555555" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":1,"name":"설계 문서"}'
```

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "current_version": 1,
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "name": "설계",
  "parent_folder_id": "55555555-5555-5555-5555-555555555555",
  "sort_order": 1024,
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/FolderController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: rename_1`)

[↑ 요약으로 돌아가기](#summary-patch-api-workspaces-workspace-id-folders-folder-id)

</details>

<a id="summary-delete-api-workspaces-workspace-id-folders-folder-id"></a>
### `DELETE /api/workspaces/{workspace_id}/folders/{folder_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 폴더와 하위 항목을 휴지통 상태로 전환하며 base version으로 동시 변경을 검증합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `folder_id`: `string`<br>**Header** — `Idempotency-Key`: `string`<br>**Body** — `DocumentLifecycleRequest` |
| 출력 | `200` 삭제 성공 또는 멱등 재요청 — `FolderLifecycleResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 version 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) — `ErrorResponse`<br>`403` 내용이 있는 폴더를 삭제할 권한이 없음 — `ErrorResponse`<br>`404` 폴더 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` version 충돌, IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용) — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-delete-api-workspaces-workspace-id-folders-folder-id"></a>
### `DELETE /api/workspaces/{workspace_id}/folders/{folder_id}` 상세

#### 1. Method + Path

`DELETE /api/workspaces/{workspace_id}/folders/{folder_id}`

#### 2. 목적

폴더와 하위 항목을 휴지통 상태로 전환하며 base version으로 동시 변경을 검증합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `folder_id` | `string` | 예 | 삭제할 폴더 ID |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`DocumentLifecycleRequest`)

```json
{
  "base_version": 1
}
```

#### 5. Response body

- HTTP `200`: 삭제 성공 또는 멱등 재요청
- Content-Type: `*/*` (`FolderLifecycleResponse`)

```json
{
  "current_version": 2,
  "delete_operation_id": "55555555-5555-5555-5555-555555555555",
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 version 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) | `ErrorResponse` |
| `403` | 내용이 있는 폴더를 삭제할 권한이 없음 | `ErrorResponse` |
| `404` | 폴더 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
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
curl -X DELETE "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/folders/55555555-5555-5555-5555-555555555555" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":1}'
```

```json
{
  "current_version": 2,
  "delete_operation_id": "55555555-5555-5555-5555-555555555555",
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/FolderController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: delete`)

[↑ 요약으로 돌아가기](#summary-delete-api-workspaces-workspace-id-folders-folder-id)

</details>

<a id="summary-get-api-workspaces-workspace-id-folders-folder-id-children"></a>
### `GET /api/workspaces/{workspace_id}/folders/{folder_id}/children`

| 항목 | 내용 |
|---|---|
| 목적 | 폴더 바로 아래의 하위 폴더와 문서를 정렬 순서로 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `folder_id`: `string` |
| 출력 | `200` 조회 성공 — `FolderChildrenResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 폴더 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-folders-folder-id-children"></a>
### `GET /api/workspaces/{workspace_id}/folders/{folder_id}/children` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/folders/{folder_id}/children`

#### 2. 목적

폴더 바로 아래의 하위 폴더와 문서를 정렬 순서로 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `folder_id` | `string` | 예 | 조회할 폴더 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`FolderChildrenResponse`)

```json
{
  "items": [
    {
      "current_version": 1,
      "has_children": false,
      "id": "string",
      "name": "회의록",
      "sort_order": 1024,
      "type": "document"
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 폴더 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/folders/55555555-5555-5555-5555-555555555555/children" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "items": [
    {
      "current_version": 1,
      "has_children": false,
      "id": "string",
      "name": "회의록",
      "sort_order": 1024,
      "type": "document"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/FolderController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: children`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-folders-folder-id-children)

</details>

<a id="summary-patch-api-workspaces-workspace-id-folders-folder-id-position"></a>
### `PATCH /api/workspaces/{workspace_id}/folders/{folder_id}/position`

| 항목 | 내용 |
|---|---|
| 목적 | 폴더를 대상 상위 폴더와 정렬 위치로 이동합니다. 자기 자신이나 하위 폴더로는 이동할 수 없습니다. |
| 입력 | **Path** — `workspace_id`: `string`, `folder_id`: `string`<br>**Header** — `Idempotency-Key`: `string`<br>**Body** — `FolderPositionRequest` |
| 출력 | `200` 이동 성공 또는 멱등 재요청 — `FolderResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 요청 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) — `ErrorResponse`<br>`404` 폴더, 대상 폴더 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` 순환 이동, version 충돌, IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용) — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-patch-api-workspaces-workspace-id-folders-folder-id-position"></a>
### `PATCH /api/workspaces/{workspace_id}/folders/{folder_id}/position` 상세

#### 1. Method + Path

`PATCH /api/workspaces/{workspace_id}/folders/{folder_id}/position`

#### 2. 목적

폴더를 대상 상위 폴더와 정렬 위치로 이동합니다. 자기 자신이나 하위 폴더로는 이동할 수 없습니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `folder_id` | `string` | 예 | 이동할 폴더 ID |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`FolderPositionRequest`)

```json
{
  "base_version": 1,
  "parent_folder_id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "position": 0
}
```

#### 5. Response body

- HTTP `200`: 이동 성공 또는 멱등 재요청
- Content-Type: `*/*` (`FolderResponse`)

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "current_version": 1,
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "name": "설계",
  "parent_folder_id": "55555555-5555-5555-5555-555555555555",
  "sort_order": 1024,
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 요청 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) | `ErrorResponse` |
| `404` | 폴더, 대상 폴더 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | 순환 이동, version 충돌, IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용) | `ErrorResponse` |

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
curl -X PATCH "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/folders/55555555-5555-5555-5555-555555555555/position" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":1,"parent_folder_id":"8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2","position":0}'
```

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "current_version": 1,
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "name": "설계",
  "parent_folder_id": "55555555-5555-5555-5555-555555555555",
  "sort_order": 1024,
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/FolderController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: move`)

[↑ 요약으로 돌아가기](#summary-patch-api-workspaces-workspace-id-folders-folder-id-position)

</details>

<a id="summary-post-api-workspaces-workspace-id-folders-folder-id-restore"></a>
### `POST /api/workspaces/{workspace_id}/folders/{folder_id}/restore`

| 항목 | 내용 |
|---|---|
| 목적 | 삭제된 폴더와 하위 항목을 복구하고 유효한 탐색 위치에 배치합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `folder_id`: `string`<br>**Header** — `Idempotency-Key`: `string`<br>**Body** — `DocumentLifecycleRequest` |
| 출력 | `200` 복구 성공 또는 멱등 재요청 — `FolderLifecycleResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 version 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) — `ErrorResponse`<br>`404` 삭제된 폴더 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` version 충돌, IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용) — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-folders-folder-id-restore"></a>
### `POST /api/workspaces/{workspace_id}/folders/{folder_id}/restore` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/folders/{folder_id}/restore`

#### 2. 목적

삭제된 폴더와 하위 항목을 복구하고 유효한 탐색 위치에 배치합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `folder_id` | `string` | 예 | 복구할 폴더 ID |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`DocumentLifecycleRequest`)

```json
{
  "base_version": 1
}
```

#### 5. Response body

- HTTP `200`: 복구 성공 또는 멱등 재요청
- Content-Type: `*/*` (`FolderLifecycleResponse`)

```json
{
  "current_version": 2,
  "delete_operation_id": "55555555-5555-5555-5555-555555555555",
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 version 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) | `ErrorResponse` |
| `404` | 삭제된 폴더 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/folders/55555555-5555-5555-5555-555555555555/restore" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":1}'
```

```json
{
  "current_version": 2,
  "delete_operation_id": "55555555-5555-5555-5555-555555555555",
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/FolderController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: restore`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-folders-folder-id-restore)

</details>

<a id="summary-get-api-workspaces-workspace-id-document-tree"></a>
### `GET /api/workspaces/{workspace_id}/document-tree`

| 항목 | 내용 |
|---|---|
| 목적 | 모든 폴더를 펼친 상태의 활성 폴더·문서 계층을 한 번에 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string` |
| 출력 | `200` 전체 트리 조회 성공 — `DocumentTreeResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 활성 워크스페이스 또는 멤버십을 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-document-tree"></a>
### `GET /api/workspaces/{workspace_id}/document-tree` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/document-tree`

#### 2. 목적

모든 폴더를 펼친 상태의 활성 폴더·문서 계층을 한 번에 반환합니다.

문서 항목에는 목록 조회(`GET /documents`)가 주는 것과 같은 메타데이터가 `document`에 담겨 온다.
화면이 계층과 문서 상태를 함께 쓰므로 두 번 부르지 않아도 된다. 두 응답은 같은 변환 규칙을 쓰므로
같은 문서가 화면마다 다르게 보이지 않는다.

`name`은 트리에 보여줄 이름이다. 폴더는 폴더 이름, 문서는 확장자를 포함한 파일명이다.
문서가 사람에게 보이는 제목은 `document.display_name`에 있다.

폴더 항목에는 `document` 키가 없다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 전체 트리 조회 성공
- Content-Type: `*/*` (`DocumentTreeResponse`)

```json
{
  "items": [
    {
      "children": [
        {
        }
      ],
      "current_version": 1,
      "has_children": true,
      "id": "string",
      "name": "설계",
      "sort_order": 1024,
      "type": "folder"
    },
    {
      "current_version": 3,
      "document": {
        "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
        "status": "completed",
        "document_role": "EDITABLE",
        "needs_reingest": false
      },
      "has_children": false,
      "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "name": "note.md",
      "sort_order": 1024,
      "type": "document"
    }
  ]
}
```

- `document`는 `DocumentItem` 전체를 담는다. 위 예시는 지면상 일부만 보였다. 전체 필드는
  `GET /api/workspaces/{workspace_id}/documents` 항목과 같다.

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 활성 워크스페이스 또는 멤버십을 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/document-tree" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "items": [
    {
      "children": [
        {
        }
      ],
      "current_version": 1,
      "has_children": true,
      "id": "string",
      "name": "설계",
      "sort_order": 1024,
      "type": "folder"
    },
    {
      "current_version": 3,
      "document": {
        "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
        "status": "completed",
        "document_role": "EDITABLE",
        "needs_reingest": false
      },
      "has_children": false,
      "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "name": "note.md",
      "sort_order": 1024,
      "type": "document"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/DocumentTreeController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: tree`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-document-tree)

</details>

<a id="summary-get-api-workspaces-workspace-id-navigation"></a>
### `GET /api/workspaces/{workspace_id}/navigation`

| 항목 | 내용 |
|---|---|
| 목적 | 워크스페이스 최상위의 폴더와 문서를 정렬 순서로 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string` |
| 출력 | `200` 조회 성공 — `FolderChildrenResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-navigation"></a>
### `GET /api/workspaces/{workspace_id}/navigation` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/navigation`

#### 2. 목적

워크스페이스 최상위의 폴더와 문서를 정렬 순서로 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`FolderChildrenResponse`)

```json
{
  "items": [
    {
      "current_version": 1,
      "has_children": false,
      "id": "string",
      "name": "회의록",
      "sort_order": 1024,
      "type": "document"
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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/navigation" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "items": [
    {
      "current_version": 1,
      "has_children": false,
      "id": "string",
      "name": "회의록",
      "sort_order": 1024,
      "type": "document"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/NavigationController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: root`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-navigation)

</details>

<a id="summary-get-api-workspaces-workspace-id-navigation-breadcrumb"></a>
### `GET /api/workspaces/{workspace_id}/navigation/breadcrumb`

| 항목 | 내용 |
|---|---|
| 목적 | 폴더 또는 문서까지 이어지는 상위 폴더 경로를 최상위부터 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Query** — `folder_id`(선택): `string`, `document_id`(선택): `string` |
| 출력 | `200` 경로 조회 성공 — `BreadcrumbResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>필터링: `folder_id`, `document_id`<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` folder_id와 document_id가 모두 없거나 함께 전달됨 — `ErrorResponse`<br>`404` 대상 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-navigation-breadcrumb"></a>
### `GET /api/workspaces/{workspace_id}/navigation/breadcrumb` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/navigation/breadcrumb`

#### 2. 목적

폴더 또는 문서까지 이어지는 상위 폴더 경로를 최상위부터 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| query | `folder_id` | `string` | 아니요 | 경로를 조회할 폴더 ID. document_id와 함께 사용할 수 없습니다. |
| query | `document_id` | `string` | 아니요 | 경로를 조회할 문서 ID. folder_id와 함께 사용할 수 없습니다. |

- Body: 없음

#### 5. Response body

- HTTP `200`: 경로 조회 성공
- Content-Type: `*/*` (`BreadcrumbResponse`)

```json
{
  "path": [
    {
      "id": "string",
      "name": "설계",
      "type": "folder"
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | folder_id와 document_id가 모두 없거나 함께 전달됨 | `ErrorResponse` |
| `404` | 대상 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
- 필터링: `folder_id`, `document_id`

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/navigation/breadcrumb?folder_id=55555555-5555-5555-5555-555555555555&document_id=<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "path": [
    {
      "id": "string",
      "name": "설계",
      "type": "folder"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/NavigationController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: breadcrumb`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-navigation-breadcrumb)

</details>

<a id="summary-get-api-workspaces-workspace-id-navigation-search"></a>
### `GET /api/workspaces/{workspace_id}/navigation/search`

| 항목 | 내용 |
|---|---|
| 목적 | 워크스페이스의 폴더 이름과 문서 파일명을 검색해 계층 경로를 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Query** — `query`: `string` |
| 출력 | `200` 검색 성공 — `HierarchySearchResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>필터링: `query`<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 검색어가 비어 있거나 잘못됨 — `ErrorResponse`<br>`404` 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-navigation-search"></a>
### `GET /api/workspaces/{workspace_id}/navigation/search` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/navigation/search`

#### 2. 목적

워크스페이스의 폴더 이름과 문서 파일명을 검색해 계층 경로를 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| query | `query` | `string` | 예 | 폴더 이름 또는 문서 파일명 검색어 |

- Body: 없음

#### 5. Response body

- HTTP `200`: 검색 성공
- Content-Type: `*/*` (`HierarchySearchResponse`)

```json
{
  "results": [
    {
      "breadcrumb": [
        {
          "id": "string",
          "name": "설계",
          "type": "folder"
        }
      ],
      "id": "string",
      "name": "회의록",
      "type": "document"
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 검색어가 비어 있거나 잘못됨 | `ErrorResponse` |
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
- 필터링: `query`

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/navigation/search?query=<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "results": [
    {
      "breadcrumb": [
        {
          "id": "string",
          "name": "설계",
          "type": "folder"
        }
      ],
      "id": "string",
      "name": "회의록",
      "type": "document"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/document/controller/NavigationController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: search`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-navigation-search)

</details>
