# Chat API

[서비스 문서](../README.md) / [document-svc](README.md)

채팅 세션·메시지와 Wiki 내보내기 Gateway API다. Wiki 내보내기는 Backend가 채팅을 검증·직렬화해
문서로 저장한 뒤 Kafka `ai.ingest.command`로 전달하며, 클라이언트는 ai-svc DTO를 보내지 않는다.

- API 수: 7

## API 목차

| API | 목적 |
|---|---|
| [`GET /api/workspaces/{workspace_id}/chat/sessions`](#summary-get-api-workspaces-workspace-id-chat-sessions) | 가장 최근 메시지 순으로 정렬해 반환합니다. |
| [`POST /api/workspaces/{workspace_id}/chat/sessions`](#summary-post-api-workspaces-workspace-id-chat-sessions) | 워크스페이스당 최대 10개까지 생성할 수 있습니다. |
| [`PATCH /api/workspaces/{workspace_id}/chat/sessions/{session_id}`](#summary-patch-api-workspaces-workspace-id-chat-sessions-session-id) | 지정한 채팅 세션의 제목을 변경합니다. 목록 정렬 기준인 last_message_at은 바뀌지 않습니다. |
| [`DELETE /api/workspaces/{workspace_id}/chat/sessions/{session_id}`](#summary-delete-api-workspaces-workspace-id-chat-sessions-session-id) | 워크스페이스에서 지정한 채팅 세션과 해당 세션의 메시지 기록을 삭제합니다. |
| [`GET /api/workspaces/{workspace_id}/chat/sessions/{session_id}/messages`](#summary-get-api-workspaces-workspace-id-chat-sessions-session-id-messages) | 세션 내 채팅 메시지를 생성 순서대로 반환합니다. |
| [`POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki`](#summary-post-api-workspaces-workspace-id-chat-sessions-session-id-wiki) | 세션(full) 또는 선택 문답(partial)을 Markdown 원문 문서로 저장합니다. Ingest는 문서에서 별도로 요청합니다. |
| [`POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki/preview`](#summary-post-api-workspaces-workspace-id-chat-sessions-session-id-wiki-preview) | 세션을 llmPipeline 입력용 Markdown으로 직렬화해 결과만 반환합니다. 저장/파이프라인 호출은 하지 않습니다. |

## 한눈에 보기

<a id="summary-get-api-workspaces-workspace-id-chat-sessions"></a>
### `GET /api/workspaces/{workspace_id}/chat/sessions`

| 항목 | 내용 |
|---|---|
| 목적 | 가장 최근 메시지 순으로 정렬해 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string` |
| 출력 | `200` 조회 성공 — `ChatSessionListResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-chat-sessions"></a>
### `GET /api/workspaces/{workspace_id}/chat/sessions` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/chat/sessions`

#### 2. 목적

가장 최근 메시지 순으로 정렬해 반환합니다.

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
- Content-Type: `*/*` (`ChatSessionListResponse`)

```json
{
  "sessions": [
    {
      "created_at": "2026-08-13T04:25:24.371948Z",
      "id": "session_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "last_message_at": "2026-08-13T04:25:24.371948Z",
      "title": "검색 인덱싱 질문"
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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "sessions": [
    {
      "created_at": "2026-08-13T04:25:24.371948Z",
      "id": "session_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "last_message_at": "2026-08-13T04:25:24.371948Z",
      "title": "검색 인덱싱 질문"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/chat/controller/ChatSessionController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: list_1`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-chat-sessions)

</details>

<a id="summary-post-api-workspaces-workspace-id-chat-sessions"></a>
### `POST /api/workspaces/{workspace_id}/chat/sessions`

| 항목 | 내용 |
|---|---|
| 목적 | 워크스페이스당 최대 10개까지 생성할 수 있습니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Body** — `ChatSessionCreateRequest` |
| 출력 | `201` 생성 성공 — `ChatSessionResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` 세션 개수 제한 초과 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-chat-sessions"></a>
### `POST /api/workspaces/{workspace_id}/chat/sessions` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/chat/sessions`

#### 2. 목적

워크스페이스당 최대 10개까지 생성할 수 있습니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Content-Type: `application/json` (`ChatSessionCreateRequest`)

```json
{
  "title": "검색 인덱싱 질문"
}
```

`title`은 선택이다. 비우거나 생략하면 서버가 `새 채팅`으로 채운다. 세션 제목은 이 세션을 위키화한 문서의
이름이 되므로 비워 두지 않는다 — 비면 문서 이름에 세션 ID가 새어 나간다.

#### 5. Response body

- HTTP `201`: 생성 성공
- Content-Type: `*/*` (`ChatSessionResponse`)

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "id": "session_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "last_message_at": "2026-08-13T04:25:24.371948Z",
  "title": "검색 인덱싱 질문"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | 세션 개수 제한 초과 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"title":"검색 인덱싱 질문"}'
```

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "id": "session_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "last_message_at": "2026-08-13T04:25:24.371948Z",
  "title": "검색 인덱싱 질문"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/chat/controller/ChatSessionController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: create_1`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-chat-sessions)

</details>

<a id="summary-patch-api-workspaces-workspace-id-chat-sessions-session-id"></a>
### `PATCH /api/workspaces/{workspace_id}/chat/sessions/{session_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 지정한 채팅 세션의 제목을 변경합니다. 목록 정렬 기준인 last_message_at은 바뀌지 않습니다. |
| 입력 | **Path** — `workspace_id`: `string`, `session_id`: `string`<br>**Body** — `ChatSessionRenameRequest` |
| 출력 | `200` 변경 성공 — `ChatSessionResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 제목이 비었거나 255자를 초과함 — `ErrorResponse`<br>`404` 세션 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-patch-api-workspaces-workspace-id-chat-sessions-session-id"></a>
### `PATCH /api/workspaces/{workspace_id}/chat/sessions/{session_id}` 상세

#### 1. Method + Path

`PATCH /api/workspaces/{workspace_id}/chat/sessions/{session_id}`

#### 2. 목적

지정한 채팅 세션의 제목을 변경합니다. 목록 정렬 기준인 last_message_at은 바뀌지 않습니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `session_id` | `string` | 예 | 채팅 세션 ID |

- Content-Type: `application/json` (`ChatSessionRenameRequest`)

```json
{
  "title": "검색 인덱싱 질문"
}
```

`title`은 필수다. 생성과 달리 빈 값을 기본 제목으로 채우지 않고 `400`으로 거절한다. 이름 변경은 명시적인
의도이므로 조용히 기본값으로 되돌리지 않는다. 최대 255자다.

#### 5. Response body

- HTTP `200`: 변경 성공
- Content-Type: `*/*` (`ChatSessionResponse`)

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "id": "session_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "last_message_at": "2026-08-13T04:25:24.371948Z",
  "title": "검색 인덱싱 질문"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 제목이 비었거나 255자를 초과함 | `ErrorResponse` |
| `404` | 세션 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X PATCH "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions/session_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  -d '{"title":"검색 인덱싱 질문"}'
```

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "id": "session_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "last_message_at": "2026-08-13T04:25:24.371948Z",
  "title": "검색 인덱싱 질문"
}
```

제목은 이 세션을 위키화한 문서의 이름으로 쓰인다. 이미 내보낸 뒤 제목을 바꿔도 기존 문서 이름은 그대로다.

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/chat/controller/ChatSessionController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: rename_3`)

[↑ 요약으로 돌아가기](#summary-patch-api-workspaces-workspace-id-chat-sessions-session-id)

</details>

<a id="summary-delete-api-workspaces-workspace-id-chat-sessions-session-id"></a>
### `DELETE /api/workspaces/{workspace_id}/chat/sessions/{session_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 워크스페이스에서 지정한 채팅 세션과 해당 세션의 메시지 기록을 삭제합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `session_id`: `string` |
| 출력 | `204` 삭제 성공 |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 세션 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-delete-api-workspaces-workspace-id-chat-sessions-session-id"></a>
### `DELETE /api/workspaces/{workspace_id}/chat/sessions/{session_id}` 상세

#### 1. Method + Path

`DELETE /api/workspaces/{workspace_id}/chat/sessions/{session_id}`

#### 2. 목적

워크스페이스에서 지정한 채팅 세션과 해당 세션의 메시지 기록을 삭제합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `session_id` | `string` | 예 | 채팅 세션 ID |

- Body: 없음

#### 5. Response body

- HTTP `204`: 삭제 성공
- Body: 없음

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 세션 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X DELETE "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/chat/controller/ChatSessionController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: delete_1`)

[↑ 요약으로 돌아가기](#summary-delete-api-workspaces-workspace-id-chat-sessions-session-id)

</details>

<a id="summary-get-api-workspaces-workspace-id-chat-sessions-session-id-messages"></a>
### `GET /api/workspaces/{workspace_id}/chat/sessions/{session_id}/messages`

| 항목 | 내용 |
|---|---|
| 목적 | 세션 내 채팅 메시지를 생성 순서대로 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `session_id`: `string` |
| 출력 | `200` 조회 성공 — `ChatMessagesResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 세션 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-chat-sessions-session-id-messages"></a>
### `GET /api/workspaces/{workspace_id}/chat/sessions/{session_id}/messages` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/chat/sessions/{session_id}/messages`

#### 2. 목적

세션 내 채팅 메시지를 생성 순서대로 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `session_id` | `string` | 예 | 채팅 세션 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`ChatMessagesResponse`)

Agent turn이 만든 메시지는 `run_id`와 `action`이 함께 온다. 질의 메시지는 두 키가 빠진다.
화면은 `action`으로 편집 미리보기와 일반 답변을 나누고, 승인 상태와 미리보기 본문은 `run_id`가
가리키는 run에서 읽는다.

`folder_organize`·`workspace_workflow`는 채팅 답변 안에 작업 계획을 표시한다. 메시지의
`run_id`로 `GET /agent/turn/{run_id}`를 조회한 뒤, 결과의 `run_id`로
`GET /agent/runs/{run_id}`를 조회한다(모두 워크스페이스 API 경로 기준).
계획의 생성 폴더와 문서 이동 위치를 보여주고 `awaiting_approval`에서 승인·거절 버튼을 제공한다.
미리보기는 변경 관련 폴더 구조를 접고 펼칠 수 있는 트리로 표시하며, 상단에 생성·이동 개수를 요약한다.
분류 이유와 기존 위치는 상세 보기에서 확인한다. 파일명은 화면에서만 NFC 정규화한다.
승인은 표시한 계획의 `plan_version`과 `operation_hash`를 전달한다. 계획 생성 완료를
실행 완료로 표시하지 않으며, 승인 후 실제 실행 상태를 조회한다. 채팅을 다시 열어도
메시지의 실행 ID로 미리보기와 승인 상태를 복구한다.

```json
{
  "messages": [
    {
      "action": "markdown_edit",
      "content": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "error_message": "string",
      "id": "string",
      "model": "gpt-5-nano",
      "pair_id": "string",
      "partial_wiki_page_ids": [
        "string"
      ],
      "provider": "openai",
      "references": [
        {
          "id": 1,
          "rank": 0,
          "reference_type": "string",
          "source_block_ids": [
            "string"
          ],
          "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
          "source_refs": [
            {
              "source_block_id": "string",
              "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
            }
          ],
          "text": "string"
        }
      ],
      "related_pages": [
        {
          "depth": 1,
          "page_type": "Concept",
          "rank": 0,
          "relevance_score": 0.87,
          "role": "string",
          "slug": "search-indexing",
          "title": "검색 인덱싱",
          "wiki_page_id": "string"
        }
      ],
      "run_id": "agent_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 세션 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions/<value>/messages" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "messages": [
    {
      "action": "markdown_edit",
      "content": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "error_message": "string",
      "id": "string",
      "model": "gpt-5-nano",
      "pair_id": "string",
      "partial_wiki_page_ids": [
        "string"
      ],
      "provider": "openai",
      "references": [
        {
          "id": 1,
          "rank": 0,
          "reference_type": "string",
          "source_block_ids": [
            "string"
          ],
          "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
          "source_refs": [
            {
              "source_block_id": "string",
              "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
            }
          ],
          "text": "string"
        }
      ],
      "related_pages": [
        {
          "depth": 1,
          "page_type": "Concept",
          "rank": 0,
          "relevance_score": 0.87,
          "role": "string",
          "slug": "search-indexing",
          "title": "검색 인덱싱",
          "wiki_page_id": "string"
        }
      ],
      "run_id": "agent_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/chat/controller/ChatSessionController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: getMessages`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-chat-sessions-session-id-messages)

</details>

<a id="summary-post-api-workspaces-workspace-id-chat-sessions-session-id-wiki"></a>
### `POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki`

| 항목 | 내용 |
|---|---|
| 목적 | 세션(full) 또는 선택 문답(partial)을 Markdown 원문 문서로 저장합니다. Ingest는 문서에서 별도로 요청합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `session_id`: `string`<br>**Body** — `ChatWikiExportRequest` |
| 출력 | `200` 원문 문서 저장 완료 — `ChatWikiExportResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | 공통 오류 계약 적용 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-chat-sessions-session-id-wiki"></a>
### `POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki`

#### 2. 목적

세션(full) 또는 선택 문답(partial)을 Markdown 원문 문서로 저장합니다. Ingest는 문서에서 별도로 요청합니다.

저장되는 Markdown 본문에는 `session_id`·`pair_id`가 들어가지 않아 사용자에게 그대로 보여줄 수 있다. 원본을
특정하는 provenance는 export 시점에 `documents.pipeline_input_blocks`(문답 단위 블록, `block_id =
session_id:pair_id`)로 저장하고, 완료 후처리가 이 값을 읽는다. 일반 문서 Ingest는 block ID를 새로 부여하므로
파이프라인이 돌려준 값은 provenance로 쓰지 않는다.

문서 이름은 채팅에서 왔음을 알리는 `[채팅] ` 접두사로 시작하고, 뒤쪽이 두 단계로 정해진다. 저장할 때는
발췌한 첫 질문을 20자로 줄인 임시 이름을 쓰고, 파이프라인이 끝나면 만들어진 Wiki 페이지 제목으로 확정한다. 페이지 제목이 비었거나 폴백값(`Chat Export`)이면 임시 이름을
그대로 둔다. 같은 이름이 이미 있으면 `(2)`처럼 번호를 붙인다. 세션 ID는 본문에도 이름에도 넣지 않는다.

새 export의 Markdown 첫 제목도 마스킹된 첫 질문을 20자로 줄여 사용한다. 예를 들어 문서 이름은
`[채팅] 검색 인덱싱은 어떻게 동작하나요?`, 본문 제목은 `# 검색 인덱싱은 어떻게 동작하나요?`가 된다.
고정된 `Chat Export` 제목 대신 선택한 문답의 내용을 식별할 수 있으며, 그 아래에는 선택한 질문·답변 원문을 유지한다.

만들어진 `chat_export` 문서는 문서 목록에 보이지만 **읽기 전용**이다(`editable: false`). 본문을 사람이 고치면
문답 경계를 다시 알아낼 수 없어 provenance가 끊기므로, 편집 잠금·본문 저장·버전 복원을 거절한다.
저장 직후 문서 상태는 `uploaded`이며 ingest 작업은 생성하지 않는다. 응답 상태는 새 저장이면 `saved`, 중복이면 `skipped`다.
Ingest에서 문서를 선택하면 기존 문서 ingest API가 저장된 문답과 출처 블록을 사용해 위키에 반영한다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `session_id` | `string` | 예 | 채팅 세션 ID |

- Content-Type: `application/json` (`ChatWikiExportRequest`)

```json
{
  "pair_ids": [
    "string"
  ]
}
```

#### 5. Response body

- HTTP `200`: 채팅 원문 문서 저장 완료 또는 기존 문서 반환
- Content-Type: `*/*` (`ChatWikiExportResponse`)

```json
{
  "exportDocumentId": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "status": "saved"
}
```

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions/<value>/wiki" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"pair_ids":["<value>"]}'
```

```json
{
  "exportDocumentId": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "status": "saved"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/chat/controller/ChatWikiExportController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: exportToWiki`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-chat-sessions-session-id-wiki)

</details>

<a id="summary-post-api-workspaces-workspace-id-chat-sessions-session-id-wiki-preview"></a>
### `POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki/preview`

| 항목 | 내용 |
|---|---|
| 목적 | 세션을 llmPipeline 입력용 Markdown으로 직렬화해 결과만 반환합니다. 저장/파이프라인 호출은 하지 않습니다. |
| 입력 | **Path** — `workspace_id`: `string`, `session_id`: `string` |
| 출력 | `200` 성공 — `string` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | 공통 오류 계약 적용 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-chat-sessions-session-id-wiki-preview"></a>
### `POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki/preview` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki/preview`

#### 2. 목적

세션을 llmPipeline 입력용 Markdown으로 직렬화해 결과만 반환합니다. 저장/파이프라인 호출은 하지 않습니다.
export와 같은 본문이라 `session_id`·`pair_id`는 포함되지 않는다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `session_id` | `string` | 예 | 채팅 세션 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `text/plain;charset=UTF-8`

```text
string
```

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions/<value>/wiki/preview" \
  -H 'Authorization: Bearer <access_token>'
```

```text
string
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/chat/controller/ChatWikiExportController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: previewWikiMarkdown`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-chat-sessions-session-id-wiki-preview)

</details>

메시지 응답의 `progress`에는 실제 실행 단계(`stage`, `message`, `sequence`)가 순서대로 보존됩니다. `run_id`는 Agent와 질의 모두에 기록합니다. 미완료 대화를 다시 열면 메시지를 주기적으로 조회해 저장된 단계와 최종 답변을 복원합니다. 기록이 없는 과거 대화에는 고정된 가상 단계를 만들지 않습니다.
