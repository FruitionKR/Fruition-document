# Meetings API

[서비스 문서](../README.md) / [document-svc](README.md)

회의 실시간 받아쓰기 API다. 회의는 **만든 사람만** 조회·변경할 수 있으며, 다른 사용자(workspace OWNER 포함)에게는 `404`로 존재도 숨긴다.
실시간 전사는 document-svc가 AI 실시간 전사 WebSocket에 연결마다 1:1로 중계하고, 확정 문장을 저장한 뒤에만 브라우저에 전달한다.
모델은 AI가 고정한다. 결정 근거는 [ADR-0023](../adr/0023-meeting-transcripts-and-recordings.md)이다.

- API 수: 3 + WebSocket 1

## API 목차

| API | 목적 |
|---|---|
| [`POST /api/workspaces/{workspace_id}/meetings`](#summary-post-api-workspaces-workspace-id-meetings) | 받아쓰기 회의를 만듭니다. document_id를 주면 그 문서를 회의록 저장 대상으로 기억합니다. |
| [`GET /api/workspaces/{workspace_id}/meetings/{meeting_id}`](#summary-get-api-workspaces-workspace-id-meetings-meeting-id) | 회의 상태, 받아쓰기 연결 기록, 발화 순서대로 정렬된 전사 구간을 반환합니다. |
| [`POST /api/workspaces/{workspace_id}/meetings/{meeting_id}/live-tickets`](#summary-post-api-workspaces-workspace-id-meetings-meeting-id-live-tickets) | 실시간 받아쓰기 WebSocket 접속에 쓰는 60초짜리 일회용 ticket을 발급합니다. |
| [`WS /api/meetings/{meeting_id}/live?ticket=`](#ws-api-meetings-meeting-id-live) | 실시간 받아쓰기. 오디오를 보내고 전사 이벤트를 받습니다. |

## 한눈에 보기

<a id="summary-post-api-workspaces-workspace-id-meetings"></a>
### `POST /api/workspaces/{workspace_id}/meetings`

| 항목 | 내용 |
|---|---|
| 목적 | 받아쓰기 회의를 만듭니다. document_id를 주면 그 문서를 회의록 저장 대상으로 기억하며, 사용자가 편집할 수 있는 자기 Markdown 문서여야 합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Header** — `Idempotency-Key`(필수): `string`<br>**Body** — `MeetingCreateRequest` |
| 출력 | `201` 생성됨 — `MeetingResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 이름·source 또는 Idempotency-Key — `ErrorResponse`<br>`403` 대상 문서를 편집할 수 없음 — `ErrorResponse`<br>`404` 워크스페이스 또는 대상 문서를 찾을 수 없음 — `ErrorResponse`<br>`409` Idempotency-Key 충돌 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-meetings"></a>
### `POST /api/workspaces/{workspace_id}/meetings` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/meetings`

#### 2. 목적

받아쓰기 회의를 만듭니다. 열린 문서에서 시작하면 `document_id`를 보내 회의록을 그 문서 끝에 추가할 대상으로 기억합니다. 문서 없이 시작하면 회의록은 나중에 새 문서로 저장합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `Idempotency-Key` | `string` | 예 | 1–255자. 같은 키·같은 입력이면 첫 응답을 그대로 돌려준다 |
| body | `display_name` | `string` | 아니오 | 1–200자. 생략하면 `회의록` |
| body | `source` | `string` | 예 | `live`(실시간 받아쓰기) 또는 `upload`(녹음 파일) |
| body | `document_id` | `string` | 아니오 | 회의록을 끝에 추가할 문서. 같은 workspace의 사용자 소유 편집 가능 Markdown 문서여야 한다 |

- Content-Type: `application/json` (`MeetingCreateRequest`)

```json
{
  "display_name": "출시 회의",
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "source": "live"
}
```

#### 5. Response body

- HTTP `201`: 생성됨. `live`는 `status: "open"`, `upload`는 `status: "awaiting_upload"`로 시작한다.
- Content-Type: `application/json` (`MeetingResponse`, 조회 API와 같다)

```json
{
  "created_at": "2026-09-28T04:25:24.371948Z",
  "display_name": "출시 회의",
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "live_connected": false,
  "meeting_id": "mtg_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "segments": [],
  "source": "live",
  "status": "open",
  "streams": [],
  "transcript_complete": true
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 이름(`INVALID_MEETING`)·source 또는 Idempotency-Key | `ErrorResponse` |
| `403` | 대상 문서를 편집할 수 없음(`DOCUMENT_WRITE_FORBIDDEN`) | `ErrorResponse` |
| `404` | 워크스페이스 또는 대상 문서를 찾을 수 없음 | `ErrorResponse` |
| `409` | Idempotency-Key 충돌 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_MEETING",
    "message": "source는 live 또는 upload여야 합니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.
- `document_id`는 사용자가 소유한 편집 가능 Markdown 문서만 허용한다(편집 잠금과 같은 규칙).

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/meetings" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: 6f1c2d0e-2b1a-4b8e-9a51-3f0d7c1e2a44' \
  -H 'Content-Type: application/json' \
  --data '{"display_name":"출시 회의","source":"live"}'
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/meeting/MeetingController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: createMeeting`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-meetings)

</details>

<a id="summary-get-api-workspaces-workspace-id-meetings-meeting-id"></a>
### `GET /api/workspaces/{workspace_id}/meetings/{meeting_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 회의 상태, 받아쓰기 연결 기록, 발화 순서대로 정렬된 전사 구간을 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `meeting_id`: `string` |
| 출력 | `200` 조회 성공 — `MeetingResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>회의를 만든 사람만 조회할 수 있다. |
| 주요 오류 | `404` 회의를 찾을 수 없음(다른 사용자의 회의 포함) — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-meetings-meeting-id"></a>
### `GET /api/workspaces/{workspace_id}/meetings/{meeting_id}` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/meetings/{meeting_id}`

#### 2. 목적

새로고침이나 연결 끊김 뒤 화면을 복구합니다. 확정 전 구간(`pending`)과 비정상 종료된 연결을 구분해 보여줄 수 있습니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `meeting_id` | `string` | 예 | 회의 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `application/json` (`MeetingResponse`)
- `live_connected`: 지금 실시간 받아쓰기 연결이 있는지.
- `streams[].end_reason`: `finished`(정상 종료·저장 개수 확인), `interrupted`(끊김), `failed`(오류), 진행 중이면 `null`. 기록 전에 서버가 종료된 연결도 `interrupted`로 보인다.
- `segments`: `position` 순. `id`는 `s{stream_order}_{AI 구간 ID}`이며 회의록 근거 ID로 쓴다. `text`가 `null`이면 `status: "pending"`이고, 연결이 끊긴 뒤에도 남아 있으면 누락이다.
- `transcript_complete`: 연결 중이 아니고, pending 구간이 없고, 모든 연결이 `finished`일 때만 `true`.
- 구간은 최대 1,000개라 페이지네이션하지 않는다.

```json
{
  "created_at": "2026-09-28T04:25:24.371948Z",
  "display_name": "출시 회의",
  "document_id": null,
  "live_connected": false,
  "meeting_id": "mtg_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "segments": [
    { "id": "s1_item_a1", "position": 1, "status": "completed", "text": "출시는 다음 주 금요일로 확정하겠습니다." },
    { "id": "s2_item_b1", "position": 2, "status": "pending", "text": null }
  ],
  "source": "live",
  "status": "open",
  "streams": [
    { "end_reason": "finished", "stream_order": 1 },
    { "end_reason": "interrupted", "stream_order": 2 }
  ],
  "transcript_complete": false
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 회의를 찾을 수 없음(`MEETING_NOT_FOUND`). 다른 사용자의 회의도 같다 | `ErrorResponse` |

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.
- 회의를 만든 사람만 조회할 수 있다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/meetings/mtg_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83" \
  -H 'Authorization: Bearer <access_token>'
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/meeting/MeetingController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: getMeeting`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-meetings-meeting-id)

</details>

<a id="summary-post-api-workspaces-workspace-id-meetings-meeting-id-live-tickets"></a>
### `POST /api/workspaces/{workspace_id}/meetings/{meeting_id}/live-tickets`

| 항목 | 내용 |
|---|---|
| 목적 | 실시간 받아쓰기 WebSocket 접속에 쓰는 60초짜리 일회용 ticket을 발급합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `meeting_id`: `string` |
| 출력 | `200` 발급 성공 — `MeetingLiveTicketResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>회의를 만든 사람만 발급할 수 있다. |
| 주요 오류 | `404` 회의를 찾을 수 없음 — `ErrorResponse`<br>`409` 실시간 회의가 아니거나 이미 다른 곳에서 받아쓰는 중 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-meetings-meeting-id-live-tickets"></a>
### `POST /api/workspaces/{workspace_id}/meetings/{meeting_id}/live-tickets` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/meetings/{meeting_id}/live-tickets`

#### 2. 목적

브라우저 WebSocket은 `Authorization` 헤더를 보낼 수 없어 ticket으로 인증합니다. 재연결·재개할 때마다 새 ticket을 받습니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `meeting_id` | `string` | 예 | 회의 ID |

- Body: 없음. 멱등 키를 받지 않으며 호출마다 새 ticket을 발급한다.

#### 5. Response body

- HTTP `200`: 발급 성공
- ticket은 Redis에 60초 저장되고 WebSocket 접속 시 한 번만 쓰인다.

```json
{
  "expires_at": "2026-09-28T04:26:24.371948Z",
  "ticket": "wst_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 회의를 찾을 수 없음(`MEETING_NOT_FOUND`) | `ErrorResponse` |
| `409` | 실시간 회의가 아니거나 열린 상태가 아님(`MEETING_NOT_OPEN`), 이미 다른 곳에서 받아쓰는 중(`MEETING_LIVE_IN_USE`) | `ErrorResponse` |

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.
- 회의를 만든 사람만 발급할 수 있다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/meetings/mtg_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83/live-tickets" \
  -H 'Authorization: Bearer <access_token>'
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/meeting/MeetingController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: issueMeetingLiveTicket`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-meetings-meeting-id-live-tickets)

</details>

<a id="ws-api-meetings-meeting-id-live"></a>
## `WS /api/meetings/{meeting_id}/live?ticket=`

WebSocket은 OpenAPI로 표현되지 않아 이 문서에만 계약을 둔다.

### 접속

- 주소: `wss://api.<domain>/api/meetings/{meeting_id}/live?ticket=<ticket>`. 브라우저 API 요청과 달리 Vercel rewrite를 거치지 않고 API host에 직접 연결한다(rewrite는 WebSocket upgrade를 중계하지 않는다).
- ticket이 없거나 만료·재사용됐거나 다른 회의의 것이면 handshake를 `401`로 거절한다. 허용되지 않은 `Origin`은 `403`이다.
- 같은 회의에 이미 연결이 있으면 접속 직후 close code `4409`로 닫는다.
- 접속하면 서버가 AI에 연결하고, 준비되면 `ready`를 보낸다(로컬 실측 약 3.5초). **`ready` 전에 보낸 오디오·명령은 입력 오류로 닫는다.** 그동안의 음성은 클라이언트가 모아 두었다가 `ready` 직후 보낸다.

### 클라이언트 → 서버

| 메시지 | 형식 | 설명 |
|---|---|---|
| 오디오 | binary | mono PCM16 little-endian 24 kHz. 짝수 bytes, 최대 48,000 bytes(1초). WebM 조각이 아니다 |
| `{"type":"commit"}` | text | 지금까지 보낸 오디오를 한 구간으로 확정 요청. 0.1–30초 구간마다 보낸다(자동 발화 감지 없음) |
| `{"type":"finish"}` | text | 녹음 종료·일시정지. 남은 구간을 확정·저장한 뒤 `finished`를 보내고 닫는다 |

오디오는 AI 전송이 끝난 뒤 다음 frame을 읽는다. 5초 안에 전송되지 않으면 `backpressure` 오류로 닫는다.

### 서버 → 클라이언트

| type | 필드 | 설명 |
|---|---|---|
| `ready` | `sample_rate`, `stream_order` | 녹음 전송 가능. `stream_order`는 이 회의에서 몇 번째 연결인지 |
| `committed` | `segment_id`, `previous_segment_id`, `position` | 구간 등록. `position`이 발화 순서다 |
| `delta` | `segment_id`, `text` | 확정 전 텍스트 조각(이어 붙여 표시). 화면 표시용이며 저장하지 않는다. **해당 구간의 `committed`보다 먼저 올 수 있다** — position이 없는 구간은 마지막 줄에 임시로 표시한다 |
| `completed` | `segment_id`, `text` | 확정 문장. **저장한 뒤에만** 보낸다. 도착 순서는 발화 순서와 다를 수 있다 |
| `finished` | `segment_count` | 이 연결의 확정 구간이 모두 저장됨. 이어서 close `1000` |
| `error` | `code`, `message` | 이 연결만 실패. 확정 구간은 보존되고 새 ticket으로 이어서 녹음한다 |

| error code | 의미 | close code |
|---|---|---|
| `invalid_audio` | `ready` 전 전송, 알 수 없는 명령 | `1008` |
| `meeting_not_open` | 받아쓰기할 수 없는 회의 | `1008` |
| `transcript_limit` | 회의 전사 한도(1,000구간·100,000자) 초과 | `1008` |
| `segment_conflict` | 같은 구간에 다른 확정 문장이 옴(덮어쓰지 않음) | `1011` |
| `transcript_incomplete` | `finished` 구간 수와 저장 수가 다름 | `1011` |
| `transcript_save_failed` | 전사 저장 실패 | `1011` |
| `backpressure` | 오디오 전송이 5초 넘게 밀림 | `1011` |
| `transcription_failed` | AI 연결 실패·중단 | `1011` |

- 연결당 최대 60분(AI 제한). 클라이언트는 그 전에 `finish` 후 새 ticket으로 다시 연결한다.
- 일시정지는 `finish`로 연결을 정상 종료하고, 재개는 새 ticket으로 연결한다. 재연결한 구간은 기존 구간 뒤에 이어진다.
- `finished` 전에 닫힌 연결은 `interrupted`로 기록된다.

### 구현 파일

- `src/main/java/fruition/core/meeting/MeetingLiveConfig.java`(ticket·Origin 검사, 등록)
- `src/main/java/fruition/core/meeting/MeetingLiveHandler.java`(중계·저장)
