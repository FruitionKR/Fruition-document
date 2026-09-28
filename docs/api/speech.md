# Speech API

[서비스 문서](../README.md) / [document-svc](README.md)

채팅 음성 입력 API다. 짧은 음성을 AI 파일 전사(`POST /speech/transcriptions`)에 중계해 텍스트를 돌려준다.
음성과 텍스트를 저장하지 않으며, 결과를 질의·Agent에 자동으로 제출하지 않는다. 모델은 AI가 고정한다.

- API 수: 1

## API 목차

| API | 목적 |
|---|---|
| [`POST /api/workspaces/{workspace_id}/speech/transcriptions`](#summary-post-api-workspaces-workspace-id-speech-transcriptions) | 짧은 음성을 텍스트로 바꿔 반환합니다. 결과를 질의·Agent에 자동으로 제출하지 않으며, 음성과 텍스트를 저장하지 않습니다. |

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

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-speech-transcriptions)

</details>
