# Query API

[서비스 문서](../README.md) / [document-svc](README.md)

동기·비동기 질의 모두 Kafka `ai.query.command`로 전달하며 같은 취소·복구 경로를 사용한다.
동기는 결과를 기다려 기존 응답 body를 반환한다. 선택 query parameter `run_id`로 실행 ID를
미리 지정하면 대기 중에도 [취소 API](tasks.md)를 호출할 수 있다. 성공 응답의
`X-AI-Run-Id` 헤더에도 실행 ID를 담는다. ID 재사용과 취소 완료된 질의는 `409`이며,
대기 시간 초과 시 취소를 요청한 뒤 `503`을 반환한다. SSE의 `query.cancelled`는 복구 완료 후의 종결 이벤트다.

- API 수: 4

## API 목차

| API | 목적 |
|---|---|
| [`POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/query`](#summary-post-api-workspaces-workspace-id-chat-sessions-session-id-query) | 질문을 받아 Wiki 페이지를 검색하고 LLM으로 답변을 생성합니다. 응답에는 답변, 관련 Wiki 페이지, 원본 출처, 그래프 하이라이트 경로가 포함됩니다. |
| [`POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/query/runs`](#summary-post-api-workspaces-workspace-id-chat-sessions-session-id-query-runs) | 질의를 비동기 run으로 시작합니다. 진행 상황은 GET /api/query/runs/{request_id}/events(SSE)로 구독합니다. |
| [`GET /api/query/runs/{requestId}`](#summary-get-api-query-runs-requestid) | 비동기 질의의 현재 상태와 완료 결과 또는 오류 정보를 반환합니다. |
| [`GET /api/query/runs/{requestId}/events`](#summary-get-api-query-runs-requestid-events) | 비동기 질의의 진행 상황과 최종 결과를 Server-Sent Events로 전달합니다. |

## 한눈에 보기

<a id="summary-post-api-workspaces-workspace-id-chat-sessions-session-id-query"></a>
### `POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/query`

| 항목 | 내용 |
|---|---|
| 목적 | 질문을 받아 Wiki 페이지를 검색하고 LLM으로 답변을 생성합니다. 응답에는 답변, 관련 Wiki 페이지, 원본 출처, 그래프 하이라이트 경로가 포함됩니다. |
| 입력 | **Path** — `workspace_id`: `string`, `session_id`: `string`<br>**Body** — `QueryRequest` |
| 출력 | `200` 질의 성공 — `QueryResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 요청 (질문이 비어 있는 경우) — `ErrorResponse`<br>`404` 세션 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`500` 서버 내부 오류 — `ErrorResponse`<br>`502` 파이프라인 요청 거부 — `ErrorResponse`<br>`503` 파이프라인 타임아웃 또는 사용 불가 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-chat-sessions-session-id-query"></a>
### `POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/query` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/query`

#### 2. 목적

질문을 받아 Wiki 페이지를 검색하고 LLM으로 답변을 생성합니다. 응답에는 답변, 관련 Wiki 페이지, 원본 출처, 그래프 하이라이트 경로가 포함됩니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `session_id` | `string` | 예 | 채팅 세션 ID |

- Content-Type: `application/json` (`QueryRequest`)

```json
{
  "allow_web_search": false,
  "model": "gpt-5-nano",
  "provider": "openai",
  "question": "검색 인덱싱은 어떻게 동작하나요?"
}
```

#### 5. Response body

- HTTP `200`: 질의 성공
- Content-Type: `*/*` (`QueryResponse`)

```json
{
  "assistant_message": {
    "content": "string",
    "created_at": "2026-08-13T04:25:24.371948Z",
    "id": "string",
    "role": "assistant",
    "status": "completed"
  },
  "error_code": "web_search_unavailable",
  "evidence_snippets": [
    {
      "rank": 0,
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
  "graph_context": {
    "edges": [
      {
        "from_page_id": "string",
        "link_type": "related",
        "role": "string",
        "score": 0.72,
        "to_page_id": "string"
      }
    ],
    "nodes": [
      {
        "depth": 1,
        "id": "string",
        "page_type": "Concept",
        "relevance_score": 0.87,
        "role": "string",
        "slug": "search-indexing",
        "title": "검색 인덱싱"
      }
    ]
  },
  "related_pages": [
    {
      "depth": 1,
      "id": "string",
      "page_type": "Concept",
      "relevance_score": 0.87,
      "role": "string",
      "slug": "search-indexing",
      "title": "검색 인덱싱"
    }
  ],
  "result_count": 5,
  "traversal_paths": [
    {
      "edges": [
        {
          "from_page_id": "string",
          "link_type": "related",
          "role": "string",
          "score": 0.72,
          "to_page_id": "string"
        }
      ],
      "nodes": [
        "string"
      ],
      "path_id": "string",
      "role": "string",
      "score": 0.72,
      "stop_reason": "string",
      "used_for_answer": true
    }
  ],
  "user_message": {
    "content": "string",
    "created_at": "2026-08-13T04:25:24.371948Z",
    "id": "string",
    "role": "assistant",
    "status": "completed"
  },
  "web_search_executed": false,
  "web_search_requested": false
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 요청 (질문이 비어 있는 경우) | `ErrorResponse` |
| `404` | 세션 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `500` | 서버 내부 오류 | `ErrorResponse` |
| `502` | 파이프라인 요청 거부 | `ErrorResponse` |
| `503` | 파이프라인 타임아웃 또는 사용 불가 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions/<value>/query" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"allow_web_search":false,"model":"gpt-5-nano","provider":"openai","question":"검색 인덱싱은 어떻게 동작하나요?"}'
```

```json
{
  "assistant_message": {
    "content": "string",
    "created_at": "2026-08-13T04:25:24.371948Z",
    "id": "string",
    "role": "assistant",
    "status": "completed"
  },
  "error_code": "web_search_unavailable",
  "evidence_snippets": [
    {
      "rank": 0,
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
  "graph_context": {
    "edges": [
      {
        "from_page_id": "string",
        "link_type": "related",
        "role": "string",
        "score": 0.72,
        "to_page_id": "string"
      }
    ],
    "nodes": [
      {
        "depth": 1,
        "id": "string",
        "page_type": "Concept",
        "relevance_score": 0.87,
        "role": "string",
        "slug": "search-indexing",
        "title": "검색 인덱싱"
      }
    ]
  },
  "related_pages": [
    {
      "depth": 1,
      "id": "string",
      "page_type": "Concept",
      "relevance_score": 0.87,
      "role": "string",
      "slug": "search-indexing",
      "title": "검색 인덱싱"
    }
  ],
  "result_count": 5,
  "traversal_paths": [
    {
      "edges": [
        {
          "from_page_id": "string",
          "link_type": "related",
          "role": "string",
          "score": 0.72,
          "to_page_id": "string"
        }
      ],
      "nodes": [
        "string"
      ],
      "path_id": "string",
      "role": "string",
      "score": 0.72,
      "stop_reason": "string",
      "used_for_answer": true
    }
  ],
  "user_message": {
    "content": "string",
    "created_at": "2026-08-13T04:25:24.371948Z",
    "id": "string",
    "role": "assistant",
    "status": "completed"
  },
  "web_search_executed": false,
  "web_search_requested": false
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/query/controller/QueryController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: query`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-chat-sessions-session-id-query)

</details>

<a id="summary-post-api-workspaces-workspace-id-chat-sessions-session-id-query-runs"></a>
### `POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/query/runs`

| 항목 | 내용 |
|---|---|
| 목적 | 질의를 비동기 run으로 시작합니다. 진행 상황은 GET /api/query/runs/{request_id}/events(SSE)로 구독합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `session_id`: `string`<br>**Body** — `QueryRequest` |
| 출력 | `202` run 시작됨 — `QueryRunCreateResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 요청 (질문이 비어 있는 경우) — `ErrorResponse`<br>`404` 세션 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-chat-sessions-session-id-query-runs"></a>
### `POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/query/runs` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/query/runs`

#### 2. 목적

질의를 비동기 run으로 시작합니다. 진행 상황은 GET /api/query/runs/{request_id}/events(SSE)로 구독합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `session_id` | `string` | 예 | 채팅 세션 ID |

- Content-Type: `application/json` (`QueryRequest`)

```json
{
  "allow_web_search": false,
  "model": "gpt-5-nano",
  "provider": "openai",
  "question": "검색 인덱싱은 어떻게 동작하나요?"
}
```

#### 5. Response body

- HTTP `202`: run 시작됨
- Content-Type: `*/*` (`QueryRunCreateResponse`)

```json
{
  "request_id": "query_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "status": "pending"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 요청 (질문이 비어 있는 경우) | `ErrorResponse` |
| `404` | 세션 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions/<value>/query/runs" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"allow_web_search":false,"model":"gpt-5-nano","provider":"openai","question":"검색 인덱싱은 어떻게 동작하나요?"}'
```

```json
{
  "request_id": "query_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "status": "pending"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/query/controller/QueryController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: createRun`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-chat-sessions-session-id-query-runs)

</details>

<a id="summary-get-api-query-runs-requestid"></a>
### `GET /api/query/runs/{requestId}`

| 항목 | 내용 |
|---|---|
| 목적 | 비동기 질의의 현재 상태와 완료 결과 또는 오류 정보를 반환합니다. |
| 입력 | **Path** — `requestId`: `string` |
| 출력 | `200` 상태 조회 성공 — `QueryRunStatusResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다. |
| 주요 오류 | `404` 질의 실행 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-query-runs-requestid"></a>
### `GET /api/query/runs/{requestId}` 상세

#### 1. Method + Path

`GET /api/query/runs/{requestId}`

#### 2. 목적

비동기 질의의 현재 상태와 완료 결과 또는 오류 정보를 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `requestId` | `string` | 예 | 비동기 질의 요청 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 상태 조회 성공
- Content-Type: `*/*` (`QueryRunStatusResponse`)

```json
{
  "error": "string",
  "model": "gpt-5-nano",
  "provider": "openai",
  "request_id": "query_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "result": {
    "assistant_message": {
      "content": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "id": "string",
      "role": "assistant",
      "status": "completed"
    },
    "error_code": "web_search_unavailable",
    "evidence_snippets": [
      {
        "rank": 0,
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
    "graph_context": {
      "edges": [
        {
          "from_page_id": "string",
          "link_type": "related",
          "role": "string",
          "score": 0.72,
          "to_page_id": "string"
        }
      ],
      "nodes": [
        {
          "depth": 1,
          "id": "string",
          "page_type": "Concept",
          "relevance_score": 0.87,
          "role": "string",
          "slug": "search-indexing",
          "title": "검색 인덱싱"
        }
      ]
    },
    "related_pages": [
      {
        "depth": 1,
        "id": "string",
        "page_type": "Concept",
        "relevance_score": 0.87,
        "role": "string",
        "slug": "search-indexing",
        "title": "검색 인덱싱"
      }
    ],
    "result_count": 5,
    "traversal_paths": [
      {
        "edges": [
          {
            "from_page_id": "string",
            "link_type": "related",
            "role": "string",
            "score": 0.72,
            "to_page_id": "string"
          }
        ],
        "nodes": [
          "string"
        ],
        "path_id": "string",
        "role": "string",
        "score": 0.72,
        "stop_reason": "string",
        "used_for_answer": true
      }
    ],
    "user_message": {
      "content": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "id": "string",
      "role": "assistant",
      "status": "completed"
    },
    "web_search_executed": false,
    "web_search_requested": false
  },
  "status": "completed",
  "web_search_enabled": false
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 질의 실행 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/query/runs/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "error": "string",
  "model": "gpt-5-nano",
  "provider": "openai",
  "request_id": "query_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "result": {
    "assistant_message": {
      "content": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "id": "string",
      "role": "assistant",
      "status": "completed"
    },
    "error_code": "web_search_unavailable",
    "evidence_snippets": [
      {
        "rank": 0,
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
    "graph_context": {
      "edges": [
        {
          "from_page_id": "string",
          "link_type": "related",
          "role": "string",
          "score": 0.72,
          "to_page_id": "string"
        }
      ],
      "nodes": [
        {
          "depth": 1,
          "id": "string",
          "page_type": "Concept",
          "relevance_score": 0.87,
          "role": "string",
          "slug": "search-indexing",
          "title": "검색 인덱싱"
        }
      ]
    },
    "related_pages": [
      {
        "depth": 1,
        "id": "string",
        "page_type": "Concept",
        "relevance_score": 0.87,
        "role": "string",
        "slug": "search-indexing",
        "title": "검색 인덱싱"
      }
    ],
    "result_count": 5,
    "traversal_paths": [
      {
        "edges": [
          {
            "from_page_id": "string",
            "link_type": "related",
            "role": "string",
            "score": 0.72,
            "to_page_id": "string"
          }
        ],
        "nodes": [
          "string"
        ],
        "path_id": "string",
        "role": "string",
        "score": 0.72,
        "stop_reason": "string",
        "used_for_answer": true
      }
    ],
    "user_message": {
      "content": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "id": "string",
      "role": "assistant",
      "status": "completed"
    },
    "web_search_executed": false,
    "web_search_requested": false
  },
  "status": "completed",
  "web_search_enabled": false
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/query/controller/QueryRunController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: getRun_1`)

[↑ 요약으로 돌아가기](#summary-get-api-query-runs-requestid)

</details>

<a id="summary-get-api-query-runs-requestid-events"></a>
### `GET /api/query/runs/{requestId}/events`

| 항목 | 내용 |
|---|---|
| 목적 | 비동기 질의의 진행 상황과 최종 결과를 Server-Sent Events로 전달합니다. |
| 입력 | **Path** — `requestId`: `string` |
| 출력 | `200` SSE 구독 시작 — `string` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다. |
| 주요 오류 | `404` 질의 실행 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-query-runs-requestid-events"></a>
### `GET /api/query/runs/{requestId}/events` 상세

#### 1. Method + Path

`GET /api/query/runs/{requestId}/events`

#### 2. 목적

비동기 질의의 진행 상황과 최종 결과를 Server-Sent Events로 전달합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `requestId` | `string` | 예 | 비동기 질의 요청 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: SSE 구독 시작
- Content-Type: `text/event-stream`

```text
string
```

전달하는 이벤트는 네 가지다.

| event | 의미 | payload |
|---|---|---|
| `query.log` | AI worker가 단계마다 발행한 진행 상황을 중계 | `request_id`, `sequence`, `received_at`, `stage`, `message`, `data` |
| `query.completed` | 최종 결과 반영 완료 | `request_id`, `status` |
| `query.failed` | 실패 확정 | `request_id`, `status`, `error` |
| `query.cancelled` | 취소 및 변경 복구 완료 | `request_id`, `status` |

- 구독 시점 이전 이벤트는 Redis buffer에서 최대 200건까지 재생한다. `sequence`가 뒤로 가는 이벤트는 전달하지 않는다.
- `query.log`는 화면 피드백 용도라 유실을 허용한다. 중계가 실패해도 로그만 남기고 최종 결과 처리를 막지 않는다.
- 같은 진행 이벤트가 재전송돼도 `event_id`를 Redis에서 선점해 한 번만 전달한다.

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 질의 실행 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/query/runs/<value>/events" \
  -H 'Authorization: Bearer <access_token>'
```

```text
string
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/query/controller/QueryRunController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: subscribe`)

[↑ 요약으로 돌아가기](#summary-get-api-query-runs-requestid-events)

</details>
