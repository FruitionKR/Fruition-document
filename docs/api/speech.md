# Speech API

[서비스 문서](../README.md) / [document-svc](README.md)

채팅 음성 API다.

- 음성 입력: 짧은 음성을 AI 파일 전사(`POST /speech/transcriptions`)에 중계해 텍스트를 돌려준다. 음성과 텍스트를 저장하지 않으며, 결과를 질의·Agent에 자동으로 제출하지 않는다.
- 답변 듣기: 완료된 assistant 답변을 AI 음성 합성(`POST /speech/synthesis`)에 중계해 음성 스트림으로 돌려준다. 끝까지 합성된 음성만 30일 저장하고 같은 엔드포인트로 다시 들려준다([ADR-0031](../adr/0031-answer-speech-streaming-relay.md)).

모델은 AI가 고정한다.

- API 수: 2

## API 목차

| API | 목적 |
|---|---|
| [`POST /api/workspaces/{workspace_id}/speech/transcriptions`](#summary-post-api-workspaces-workspace-id-speech-transcriptions) | 짧은 음성을 텍스트로 바꿔 반환합니다. 결과를 질의·Agent에 자동으로 제출하지 않으며, 음성과 텍스트를 저장하지 않습니다. |
| [`POST /api/workspaces/{workspace_id}/speech/synthesis`](#summary-post-api-workspaces-workspace-id-speech-synthesis) | 완료된 assistant 답변을 음성으로 스트리밍합니다. 끝까지 합성된 음성은 저장해 다시 들려줍니다. |

## 한눈에 보기

<a id="summary-post-api-workspaces-workspace-id-speech-transcriptions"></a>
### `POST /api/workspaces/{workspace_id}/speech/transcriptions`

| 항목 | 내용 |
|---|---|
| 목적 | 짧은 음성을 텍스트로 바꿔 반환합니다. 결과를 질의·Agent에 자동으로 제출하지 않으며, 음성과 텍스트를 저장하지 않습니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Body** — 오디오 bytes(`audio/webm`, `audio/mp4`, `audio/mpeg`, `audio/wav`) |
| 출력 | `200` 전사 성공 — `SpeechTranscriptionResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `413` 24 MiB 초과<br>`415` 지원하지 않는 형식<br>`422` 빈 음성·인식 불가<br>`502` 음성 모델 호출 실패<br>`503` 전사 서비스 사용 불가 — 모두 `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-speech-transcriptions"></a>
### `POST /api/workspaces/{workspace_id}/speech/transcriptions` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/speech/transcriptions`

#### 2. 목적

채팅 입력창의 마이크로 녹음한 질문을 텍스트로 바꿉니다. 클라이언트는 받은 텍스트를 입력창에 채우고, 사용자가 확인한 뒤 직접 전송합니다. 받아쓴 텍스트를 명령으로 자동 실행하지 않습니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `Content-Type` | `string` | 예 | `audio/webm`, `audio/mp4`, `audio/mpeg`, `audio/wav`. `audio/webm;codecs=opus`처럼 파라미터가 붙어도 된다(떼고 AI에 넘긴다) |

- Body: 오디오 bytes 그대로(multipart 아님). 1 byte 이상 24 MiB 이하. 발화 길이는 클라이언트가 60초로 제한한다.

#### 5. Response body

- HTTP `200`: 전사 성공
- Content-Type: `application/json` (`SpeechTranscriptionResponse`)
- 인식된 말이 없으면(무음 등) `text`는 빈 문자열이다. 클라이언트는 "인식된 음성이 없습니다"를 안내하고 입력창을 그대로 둔다.

```json
{
  "text": "검색 인덱싱은 어떻게 동작하나요?"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `403` | AI가 워크스페이스 접근을 거절 | `ErrorResponse` |
| `404` | 워크스페이스를 찾을 수 없음(멤버가 아님) | `ErrorResponse` |
| `413` | 24 MiB 초과 | `ErrorResponse` |
| `415` | 지원하지 않는 오디오 형식 | `ErrorResponse` |
| `422` | 빈 음성 또는 AI가 인식할 수 없는 음성 | `ErrorResponse` |
| `502` | 음성 모델 호출 실패. 제공자 오류 원문은 노출하지 않는다 | `ErrorResponse` |
| `503` | AI 전사 서비스에 연결할 수 없거나 설정·인증 문제 | `ErrorResponse` |

```json
{
  "error": {
    "code": "REQUEST_FAILED",
    "message": "음성을 전사하지 못했습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- path의 `workspace_id`에 대한 활성 멤버십을 검증한다. 형식 검사와 멤버십 검사는 AI 호출 전에 한다.
- AI도 access로 workspace 멤버십을 다시 확인한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/speech/transcriptions" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: audio/webm;codecs=opus' \
  --data-binary @question.webm
```

```json
{
  "text": "검색 인덱싱은 어떻게 동작하나요?"
}
```

- 로컬 실측(2026-09-28, 실제 AI·모델): 2초 안팎 발화 1.3–11.6초, 27초 발화 5.2초, 무음 2.2초(빈 text). document 경유와 AI 직접 호출의 시간 차이는 없었고 편차는 모델 응답 시간이다. 클라이언트는 전사 중 표시를 둔다.

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/core/speech/SpeechController.java`
- AI 호출: `src/main/java/fruition/core/speech/SpeechTranscriptionClient.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: transcribeSpeech`)
- 호출자: 없음 — 프론트엔드 `apiFetch` 호출 지점에 이 경로가 없고, ai-svc·access-svc도 이 서비스의 `/api/**`를 호출하지 않는다. 프론트엔드에 마이크 입력 호출 지점이 없다
- 하위 호출: ai-svc `POST ${SPEECH_TRANSCRIPTION_ENDPOINT}?workspace_id&user_id&deadline_seconds=100` (`SpeechTranscriptionClient`, `X-Internal-Token`)
- 배선 상태: **미배선 — 호출자 없음**

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-speech-transcriptions)

</details>

---

<a id="summary-post-api-workspaces-workspace-id-speech-synthesis"></a>
## `POST /api/workspaces/{workspace_id}/speech/synthesis`

| 항목 | 내용 |
|---|---|
| 목적 | 완료된 assistant 답변을 음성으로 스트리밍합니다. 끝까지 합성된 음성은 저장해 같은 엔드포인트로 다시 들려줍니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Body** — `{"message_id": "<assistant 메시지 ID>"}` |
| 출력 | `200` `application/x-ndjson` 이벤트 스트림, `Cache-Control: no-store` |
| 조건 | 인증 필요, 워크스페이스 활성 멤버, 메시지가 내 채팅 세션의 완료된 assistant 답변 |
| 주요 오류 | `400` message_id 누락·답변이 합성 입력 한도 초과<br>`402` 크레딧 부족<br>`404` 멤버 아님·없는/남의/assistant가 아닌 메시지<br>`409` 완료되지 않았거나 실패한 답변<br>`422` 읽을 글이 없는 답변<br>`503` 합성 서비스 사용 불가 — 모두 `ErrorResponse`, 스트림 시작 전에만 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-speech-synthesis"></a>
### `POST /api/workspaces/{workspace_id}/speech/synthesis` 상세

#### 1. 응답 이벤트 형식

응답 한 개가 줄바꿈(`\n`)으로 구분된 JSON 이벤트를 이어서 보낸다. 브라우저에 가는 형식과 AI가 document에 보내는 형식이 같다.

```
{"type":"audio","delta":"<base64 PCM16 little-endian mono 24kHz>"}
{"type":"audio","delta":"..."}
{"type":"done"}
```

- `audio`가 0개 이상 이어진 뒤 정확히 하나의 종결 이벤트가 온다. 성공은 `{"type":"done"}`, 실패는 `{"type":"error","code":"<code>"}`다.
- `delta`는 한 번에 그대로 이어 붙여 재생할 수 있는 PCM 조각이다. 조각 경계가 샘플(2바이트) 경계라고 가정하지 말고 이전 조각의 남은 바이트를 이어 붙인다.
- `error.code`: `provider_unavailable`, `provider_rate_limited`, `deadline_exceeded`, `internal_error`. 스트림이 시작된 뒤의 실패는 HTTP 상태가 아니라 이 이벤트로만 알린다.
- 스트림이 시작되기 전의 오류(검증·크레딧·AI 연결)는 일반 HTTP 상태와 `ErrorResponse`다.

#### 2. 동작

1. 인증·멤버십을 확인하고, 메시지가 내 세션의 `assistant` 답변인지 본다. 남의 메시지·없는 메시지·assistant가 아닌 메시지는 모두 `404`, `completed`가 아니면 `409`다.
2. 저장된 음성이 있으면 AI를 부르지 않고 저장된 WAV의 PCM을 같은 형식으로 보낸다. 과금하지 않는다.
3. 없으면 답변 Markdown을 읽을 문장으로 정리한다(아래). 글이 남지 않으면 `422`다.
4. 크레딧을 예약하고(`speech_synthesis`) AI `POST /speech/synthesis`를 `X-Request-Id`로 귀속해 부른다. AI가 보내는 줄을 한 줄씩 그대로 중계하고 줄마다 flush한다. 오디오는 임시 파일에 모은다(메모리에 쌓지 않는다. 256 MiB를 넘으면 저장만 포기하고 중계는 계속한다). AI가 보내는 모르는 이벤트 종류는 무시한다.
5. AI의 `done`을 받으면 WAV로 감싸 저장한 뒤 `done`을 보낸다. `error`·연결 끊김·시간 초과로 끝난 합성은 저장하지 않는다. 저장본을 읽다 실패하면 `internal_error`다.
6. 클라이언트가 연결을 끊으면(멈춤) 쓰기 실패로 감지해 AI 연결을 즉시 닫는다. AI는 공급사 응답을 취소하고(최대 5초 기다려 `response.done`의 usage를 받아) 호출을 `cancelled` 상태와 사용량으로 원장에 남긴다. document는 `succeeded`와 같이 `cancelled` 호출을 단가대로 청구한다(사용량이 없으면 `needs_review`). AI가 원장 행을 마감하기 전이면(`started`) 사용량 수집이 실패로 처리돼 수집 대기열의 재시도(백오프)가 다시 가져오므로 다음 날 대사를 기다리지 않는다.

AI 응답은 청크 사이 30초(`SPEECH_SYNTHESIS_READ_TIMEOUT_SECONDS`) 동안 아무것도 오지 않으면 `deadline_exceeded`로 끝낸다. 전체 시간 제한은 없다.

#### 3. 텍스트 정리 규칙

규칙 기반이며 모델을 쓰지 않는다. 코드 블록·인라인 코드·이미지·HTML 태그·인용 번호(`[1]`, `[1, 2]`, `[^1]`)·각주·링크 참조 정의 줄(`[1]: ...`, `[^1]: ...`)과 AI가 붙이는 내부 블록 참조(`[doc:B0001]`, `[B0001, B0002]`)는 통째로 지운다. 인용 번호·블록 참조 앞 공백은 뒤가 문장부호나 줄 끝일 때만 함께 지운다(`씁니다 [2].` → `씁니다.`). 링크는 글자만 남기고 URL(맨 URL 포함, 괄호가 든 주소도)을 지운다. 제목(`#`)·강조(`*`, `_`, `~~`)·목록 기호(`-`, `*`, `+`, `1.`)·인용문(`>`)·표 구분선은 기호만 걷어 내고 표 셀은 쉼표로 잇는다. 공백은 접는다.

#### 4. 입력 한도

글자 수 제한(제품 상한)은 없다. 모델 입력 한도를 넘는 기술적 상한은 AI가 정하고, 넘으면 AI가 `400`을 돌려줘 이 API도 `400`을 돌려준다.

#### 5. 예시

```bash
curl -N -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/speech/synthesis" \
  -H 'Authorization: Bearer <access_token>' -H 'Content-Type: application/json' \
  -d '{"message_id":"msg_2f0b8d3f1c9a4c4e9a3d6f7a1b2c3d4e"}'
```

#### 6. 구현 파일

- 진입점: `src/main/java/fruition/core/speech/SpeechController.java` (`operationId: synthesizeSpeech`)
- 중계·저장 판단: `SpeechSynthesisService.java`, AI 호출 `SpeechSynthesisClient.java`
- 텍스트 정리: `SpeechTextCleaner.java`
- 저장·삭제·30일 정리: `SpeechAudioStore.java`
- 하위 호출: ai-svc `POST ${SPEECH_SYNTHESIS_ENDPOINT}` (`X-Internal-Token`, `X-Request-Id`)
- 배선 상태: **미배선 — 프론트엔드 호출 지점 없음**

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-speech-synthesis)

</details>
