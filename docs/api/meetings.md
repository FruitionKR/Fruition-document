# Meetings API

[서비스 문서](../README.md) / [document-svc](README.md)

회의 실시간 받아쓰기 API다. 회의는 **만든 사람만** 조회·변경할 수 있으며, 다른 사용자(workspace OWNER 포함)에게는 `404`로 존재도 숨긴다.
실시간 전사는 document-svc가 AI 실시간 전사 WebSocket에 연결마다 1:1로 중계하고, 확정 문장을 저장한 뒤에만 브라우저에 전달한다.
모델은 AI가 고정한다. 결정 근거는 [ADR-0023](../adr/0023-meeting-transcripts-and-recordings.md)이다.

- API 수: 10 + WebSocket 1

## API 목차

| API | 목적 |
|---|---|
| [`POST /api/workspaces/{workspace_id}/meetings`](#summary-post-api-workspaces-workspace-id-meetings) | 받아쓰기 회의를 만듭니다. document_id를 주면 그 문서를 회의록 저장 대상으로 기억합니다. |
| [`GET /api/workspaces/{workspace_id}/meetings/{meeting_id}`](#summary-get-api-workspaces-workspace-id-meetings-meeting-id) | 회의 상태, 받아쓰기 연결 기록, 발화 순서대로 정렬된 전사 구간을 반환합니다. |
| [`POST /api/workspaces/{workspace_id}/meetings/{meeting_id}/live-tickets`](#summary-post-api-workspaces-workspace-id-meetings-meeting-id-live-tickets) | 실시간 받아쓰기 WebSocket 접속에 쓰는 60초짜리 일회용 ticket을 발급합니다. |
| [`DELETE .../meetings/{meeting_id}`](#meeting-delete) | 회의·전사·회의록 초안·녹음 원본을 삭제합니다. |
| [`PUT .../meetings/{meeting_id}/recording`](#meeting-recording) | 녹음 원본을 올립니다. 녹음 파일 회의는 전사를 시작합니다. |
| [`GET .../meetings/{meeting_id}/recording-url`](#meeting-recording-url) | 녹음 원본을 재생할 5분짜리 주소를 반환합니다. |
| [`POST .../meetings/{meeting_id}/notes`](#notes-generate) | 확정 전사로 회의록 초안을 만들어 새 버전으로 보관합니다. 생성은 비동기입니다(`202`, 조회로 대기). |
| [`GET .../meetings/{meeting_id}/notes`](#notes-latest) | 최신 회의록 초안을 반환합니다. |
| [`POST .../meetings/{meeting_id}/notes/{version}/append-preview`](#notes-append-preview) | 기존 문서 끝에 회의록을 붙인 전체 결과와 base_revision을 반환합니다. |
| [`POST .../meetings/{meeting_id}/notes/{version}/apply`](#notes-apply) | 회의록을 새 문서로 만들거나 기존 문서 끝에 추가합니다. |
| [`WS /api/meetings/{meeting_id}/live?ticket=`](#ws-api-meetings-meeting-id-live) | 실시간 받아쓰기. 오디오를 보내고 전사 이벤트를 받습니다. |

## 호출 관계(배선)

경로 앞부분 `...`은 `/api/workspaces/{workspace_id}`다. 이 도메인은 **공개 API 10개와 WebSocket 1개 전부 호출자가 없다**.
프론트엔드(`/Users/mireutale/coding/git/Fruition-frontend`)의 85개 `apiFetch` 호출 지점과 `fetch`·`new WebSocket` 호출 지점을
전수 확인했으며 `meetings`·`speech`로 가는 경로가 하나도 없다. ai-svc·access-svc도 이 서비스의 `/api/**`를 호출하지 않는다.

| API | 호출자 | 하위 호출 | 배선 |
|---|---|---|---|
| `POST .../meetings` | 없음 | 권한 확인 외 없음 | **미배선** |
| `GET .../meetings/{meeting_id}` | 없음 | 권한 확인 외 없음 | **미배선** |
| `POST .../meetings/{meeting_id}/live-tickets` | 없음 | 티켓만 발급. 티켓을 쓰는 WebSocket 중계(`MeetingLiveHandler`)가 ai-svc `${SPEECH_LIVE_ENDPOINT}`에 연결한다 | **미배선** |
| `DELETE .../meetings/{meeting_id}` | 없음 | 객체 저장소(MinIO/S3) 녹음 원본 삭제 | **미배선** |
| `PUT .../meetings/{meeting_id}/recording` | 없음 | 객체 저장소 쓰기 → `MeetingTranscriptionWorker`가 비동기로 ai-svc `POST ${SPEECH_TRANSCRIPTION_ENDPOINT}` 호출 | **미배선** |
| `GET .../meetings/{meeting_id}/recording-url` | 없음 | 객체 저장소 presign | **미배선** |
| `POST .../meetings/{meeting_id}/notes` | 없음 | ai-svc `POST ${MEETING_NOTES_ENDPOINT}` (`MeetingNotesClient`) | **미배선** |
| `GET .../meetings/{meeting_id}/notes` | 없음 | 권한 확인 외 없음(DB 조회로 보이나 서비스 계층을 한 줄씩 확인하지는 않았다) | **미배선** |
| `POST .../meetings/{meeting_id}/notes/{version}/append-preview` | 없음 | ai-svc `POST ${MEETING_NOTES_ENDPOINT}` (`MeetingNotesClient`) | **미배선** |
| `POST .../meetings/{meeting_id}/notes/{version}/apply` | 없음 | 문서 본문 쓰기(DB·객체 저장소). ai-svc 호출 여부는 미확인 | **미배선** |
| `WS /api/meetings/{meeting_id}/live?ticket=` | 없음 | ai-svc WebSocket `${SPEECH_LIVE_ENDPOINT}` 1:1 중계(`MeetingLiveHandler`, `X-Internal-Token`) | **미배선** |

전체 배선 요약은 [API 목차의 배선 표](README.md#호출-관계배선-요약)를 본다.

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
- 호출자: 없음 — 프론트엔드 `apiFetch` 호출 지점에 이 경로가 없고, ai-svc·access-svc도 이 서비스의 `/api/**`를 호출하지 않는다. 프론트엔드에 회의 화면 호출 지점이 전혀 없다
- 하위 호출: 권한 확인(access-svc `GET /internal/authz/workspaces/{id}/users/{id}`, Redis 캐시 miss에만 발생) 외 없음
- 배선 상태: **미배선 — 호출자 없음**

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
- 호출자: 없음 — 프론트엔드 `apiFetch` 호출 지점에 이 경로가 없고, ai-svc·access-svc도 이 서비스의 `/api/**`를 호출하지 않는다
- 하위 호출: 권한 확인(access-svc `GET /internal/authz/workspaces/{id}/users/{id}`, Redis 캐시 miss에만 발생) 외 없음
- 배선 상태: **미배선 — 호출자 없음**

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
- 호출자: 없음 — 프론트엔드 `apiFetch` 호출 지점에 이 경로가 없고, ai-svc·access-svc도 이 서비스의 `/api/**`를 호출하지 않는다. 프론트엔드에 `new WebSocket` 호출 지점도 없다
- 하위 호출: 티켓만 발급한다. 티켓을 쓰는 WebSocket 중계(`MeetingLiveHandler`)가 ai-svc `${SPEECH_LIVE_ENDPOINT}`에 연결한다
- 배선 상태: **미배선 — 호출자 없음**

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-meetings-meeting-id-live-tickets)

</details>

## 녹음 원본과 회의 삭제

경로 앞부분 `...`은 `/api/workspaces/{workspace_id}`다. 세 API 모두 workspace 멤버십과 회의를 만든 사람임을 검증한다(아니면 `404`). 원본은 회의가 삭제될 때까지 보관한다. 조회 응답(`MeetingResponse`)에 `has_recording`, `error`(녹음 파일 전사 실패 사유)가 있다.

<a id="meeting-recording"></a>
### `PUT .../meetings/{meeting_id}/recording` — 녹음 원본 업로드

| 항목 | 내용 |
|---|---|
| 입력 | multipart `file`. `audio/wav`·`audio/mpeg`·`audio/mp4`·`audio/webm`(파라미터·`audio/x-wav`·`audio/x-m4a` 허용), 1 byte–24 MiB |
| 녹음 파일 회의(`source=upload`) | `awaiting_upload`·`failed`에서만. 저장 후 `202`, `status=transcribing`. 다시 올리면 새 원본으로 바꾸고 이전 원본은 저장소에서 지운다. 겹친 업로드 중 하나만 성공하고 실패한 쪽의 객체는 남지 않는다 |
| 실시간 회의(`source=live`) | `open`이고 받아쓰기 연결이 없을 때 한 번만. 저장만 하고 `200`(브라우저가 받아쓰기와 함께 녹음한 원본) |
| 오류 | `409` 허용되지 않는 상태·원본이 이미 있음(`MEETING_RECORDING_NOT_ALLOWED`)·받아쓰기 중(`MEETING_LIVE_IN_USE`), `413`, `415`, `422` 빈 파일, `503` 저장소 실패 |

녹음 파일 전사는 작업자가 2초마다 `transcribing` 회의 한 건을 선점해(여러 Pod 동시 처리 없음, 5분 넘은 선점은 다시 잡음) AI 파일 전사를 부르고, 결과를 문장 부호·줄바꿈으로 나눈 뒤 약 1,000자 단위로 묶은 구간(`s1_seg_0001`…)으로 저장하고 `open`으로 바꾼다. 회의록 AI의 1,000구간 한도에 걸리지 않게 하려는 것이다. 전사가 100,000자를 넘으면 `failed`(녹음을 나눠 올리라는 사유)로 끝난다. 연결 기록 하나(`finished`)가 생겨 `transcript_complete=true`가 된다. 인식된 말이 없거나 실패하면 `failed`와 `error`를 남기며 자동 재시도는 없다. 클라이언트는 조회 API로 상태를 확인한다. 로컬 실측(실제 AI): 27초 m4a가 약 11초에 4문장으로 전사됐다.

<a id="meeting-recording-url"></a>
### `GET .../meetings/{meeting_id}/recording-url` — 재생 주소

```json
{ "url": "https://...presigned...", "expires_at": "2026-09-28T04:40:24Z" }
```

5분 유효 presigned GET 주소다(`response-content-type`은 올린 형식). `<audio src>`로 재생한다. 원본이 없으면 `404`(`MEETING_RECORDING_NOT_FOUND`).

<a id="meeting-delete"></a>
### `DELETE .../meetings/{meeting_id}` — 회의 삭제

- 응답 `204`. 회의·연결·전사 구간·회의록 초안과 녹음 원본을 지운다. 이미 저장한 회의록 문서는 일반 문서라 남는다.
- 원본을 먼저 지우고 DB를 지운다. 원본 삭제가 실패하면 `503`이고 아무것도 지우지 않는다(원본만 남는 상황을 막는다). 저장소 호출 동안 DB 잠금을 잡지 않으며, 읽어 둔 원본 키와 같을 때만 행을 지운다. 그사이 재업로드로 키가 바뀌었으면 `409`(`MEETING_RECORDING_CHANGED`)이고 새 원본은 남는다. 다시 요청하면 지워진다.
- 전사 중이면 `409`(`MEETING_TRANSCRIBING`). 전사가 끝난(`open` 또는 `failed`) 뒤 다시 요청한다.
- 받아쓰기 연결 중이면 `409`(`MEETING_LIVE_IN_USE`). 녹음 취소는 WebSocket을 닫은 뒤 이 API를 호출한다(잠금 해제가 늦으면 잠시 뒤 다시 시도).

## 회의록 초안과 저장

경로 앞부분 `...`은 `/api/workspaces/{workspace_id}`다. 네 API 모두 `Authorization: Bearer <access_token>`, workspace 멤버십, 회의를 만든 사람임을 검증한다(아니면 `404`). 초안은 AI 회의록 API(모델 고정)로 만들고 document가 버전별로 보관한다.

<a id="notes-generate"></a>
### `POST .../meetings/{meeting_id}/notes` — 초안 생성

| 항목 | 내용 |
|---|---|
| 입력 | **Header** — `Idempotency-Key`(필수). 네트워크 재시도는 같은 키(같은 버전 반환, AI 재호출 없음), 다시 만들기는 새 키<br>**Query** — `allow_partial`(기본 `false`) |
| 처리 | `completed` 구간을 `position` 순으로 약 1,000자까지 묶어 AI에 보낸다(묶음 ID는 묶인 첫 구간 ID, 근거 ID는 원래 구간 ID들로 되돌려 저장). 새 버전 행을 `generating`으로 만든 뒤 **요청 스레드·트랜잭션 밖에서** AI를 호출하고, 결과는 그 버전 행에만 쓴다 |
| 출력 | `202` — `MeetingNotesResponse`(`status=generating`). 같은 키 재시도로 이미 끝난 버전을 돌려줄 때만 `200` |
| 오류 | `409` 받아쓰기 중(`MEETING_LIVE_IN_USE`), 누락·비정상 종료 전사를 `allow_partial` 없이 요청(`MEETING_TRANSCRIPT_INCOMPLETE`)<br>`422` 확정 전사 없음(`MEETING_TRANSCRIPT_EMPTY`) |

생성은 비동기다. `202`를 받은 뒤 조회(`GET`)로 `status`가 `ready`나 `failed`가 될 때까지 기다린다. 생성 실패는 HTTP 오류가 아니라 그 버전의 `failed`와 `error_code`(`MEETING_NOTES_FAILED`, `MEETING_NOTES_INVALID`, `MEETING_NOTES_INPUT_REJECTED`, `MEETING_NOTES_UNAVAILABLE`, 동시 생성 한도 초과는 `MEETING_NOTES_BUSY`)로 알리고, 이전 `ready` 초안은 그대로 쓸 수 있다.

<a id="notes-latest"></a>
### `GET .../meetings/{meeting_id}/notes` — 최신 초안

```json
{
  "meeting_id": "mtg_1b9f...",
  "version": 1,
  "status": "ready",
  "partial": false,
  "display_name": "출시 회의",
  "markdown": "# 출시 회의\n\n## 요약\n- 출시는 다음 주 금요일로 확정되었습니다.\n\n## 결정 사항\n- 출시는 다음 주 금요일로 확정.\n\n## 할 일\n- 민수님을 배포 전 점검 담당자로 지정.\n\n## 미결 사항\n- 확인된 내용 없음",
  "summary": [{ "text": "출시는 다음 주 금요일로 확정되었습니다.", "source_segment_ids": ["s1_item_a1"] }],
  "decisions": [{ "text": "출시는 다음 주 금요일로 확정.", "source_segment_ids": ["s1_item_a1"] }],
  "action_items": [{ "text": "민수님을 배포 전 점검 담당자로 지정.", "source_segment_ids": ["s1_item_a2"] }],
  "open_questions": [],
  "applied": null,
  "error_code": null,
  "last_ready_version": null
}
```

- `status`: `generating` · `ready` · `failed` · `applied`. `partial`은 `allow_partial`로 만든 초안이다.
- `markdown`은 document가 네 배열로 다시 만든 본문이다(`# 이름` → `## 요약` · `## 결정 사항` · `## 할 일` · `## 미결 사항`, 빈 항목은 `- 확인된 내용 없음`). AI 응답의 `markdown`은 항목마다 근거 ID를 붙이므로 쓰지 않는다. 근거는 `source_segment_ids`로만 준다.
- 최신 버전이 `failed`면 `last_ready_version`에 마지막 성공 버전을 준다. 5분 넘게 `generating`인 버전은 `failed`(`MEETING_NOTES_TIMEOUT`)로 바뀐다.
- 저장 후 `applied`: `{"mode":"append","document_id":"doc_...","applied_at":"..."}`.
- 초안이 없으면 `404`(`MEETING_NOTES_NOT_FOUND`).

<a id="notes-append-preview"></a>
### `POST .../meetings/{meeting_id}/notes/{version}/append-preview` — 기존 문서 끝 추가 미리보기

| 항목 | 내용 |
|---|---|
| 입력 | **Body**(선택) — `document_id`(생략 시 회의를 시작한 문서), `markdown`(사용자가 수정한 회의록, 생략 시 초안 본문). 아무것도 바꾸지 않아 멱등 키를 받지 않는다 |
| 출력 | `200` — `{"document_id": "...", "base_revision": 17, "markdown": "(기존 본문)\n\n# 출시 회의\n..."}` |
| 규칙 | 현재 편집 본문 끝 공백을 지우고 빈 줄 하나 뒤에 회의록을 붙인다. `apply`는 같은 입력이면 같은 본문을 만든다 |
| 오류 | `400` 대상 문서 없음, `403` 편집 불가(`DOCUMENT_WRITE_FORBIDDEN`), `404` 문서 없음, `409` 최신 초안이 아님(`MEETING_NOTES_OUTDATED`)·이미 저장함 |

<a id="notes-apply"></a>
### `POST .../meetings/{meeting_id}/notes/{version}/apply` — 저장

| 필드 | 필수 | 설명 |
|---|---|---|
| `mode` | 예 | `create`(새 문서) 또는 `append`(기존 문서 끝) |
| `display_name` | 아니오 | `create`의 문서 이름. 생략 시 초안 이름 |
| `markdown` | 아니오 | 사용자가 수정한 회의록 본문. 생략 시 초안 본문 |
| `folder_id` | 아니오 | `create`의 위치. 생략 시 루트 |
| `document_id` | 아니오 | `append` 대상. 생략 시 회의를 시작한 문서 |
| `base_revision` | `append`에서 예 | `append-preview`에서 받은 값 |

- 헤더 `Idempotency-Key` 필수. 같은 키 재시도는 처음 기록한 대상·본문으로 같은 저장을 반복해 같은 결과를 돌려준다(문서 중복 생성·회의록 중복 추가 없음). 문서 저장은 됐는데 회의 상태 기록이 실패한 경우도 같은 재시도로 복구된다.
- `create`는 기존 Markdown 문서 생성, `append`는 기존 본문 저장(`base_revision`, `revision_write_id`)을 그대로 쓴다. 문서 권한·편집 잠금·revision·이름 검증을 우회하지 않으며 Agent 적용 표·`source`를 쓰지 않는다.
- 사용자가 본 버전이 마지막 성공 초안이 아니면 `409 MEETING_NOTES_OUTDATED`. 한 버전은 한 번 저장하며 다른 키로 다시 저장하면 `409 MEETING_NOTES_ALREADY_APPLIED`.
- `append`에서 미리보기 이후 문서가 바뀌었으면 `409 DOCUMENT_REVISION_CHANGED`(저장하지 않음). revision 충돌·권한·잠금·본문 검증·이름 중복처럼 문서가 바뀌지 않은 거절은 저장 선점을 풀어 새 키로 다시 시도할 수 있다.
- 응답 `200`: `MeetingNotesResponse`(`status: "applied"`, `applied` 채움).

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
| `transcript_limit` | 회의 전사 한도(10,000구간·100,000자) 초과. 이미 한도에 닿은 회의는 연결 직후 거절하고 연결 기록을 남기지 않는다. 저장된 전사로 회의록은 만들 수 있다 | `1008` |
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
