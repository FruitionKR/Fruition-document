# 0034. 요청자 화면 언어(Accept-Language)로 오류 문구와 AI 응답 언어를 정한다

## 맥락
프론트가 한국어/영어 화면 전환을 지원하고 모든 API 요청에 `Accept-Language: ko|en`을 보낸다. 지금까지 오류 응답 `message`, 저장된 실패 문구(`error_message`), AI 답변은 한국어로만 나왔다. 오류 문구는 수백 곳의 예외가 직접 만든 한국어 문장이고, 비동기 작업의 실패 문구는 요청이 끝난 뒤 Kafka 소비자에서 한국어로 저장된다.

## 결정
1. **언어는 ko·en 두 가지다.** `LocaleConfig`의 `AcceptHeaderLocaleResolver`(지원 ko·en, 기본 ko)가 정한다. 그 밖의 언어·헤더 없음·요청 밖(Kafka 소비자·스케줄러)은 ko다. 요청 밖에서 JVM 기본 로케일을 쓰지 않도록 `ResponseLanguage.current()`가 LocaleContext가 없으면 ko를 돌려준다.
2. **공통 `ErrorResponse`는 code를 그대로 두고 message만 바꾼다.** `ErrorMessageLocalizer`(ResponseBodyAdvice)가 en 요청이면 `messages_en.properties`의 `error.<code>`(없으면 `error.default`)로, 검증 실패 `details[].reason`은 `error.field.invalid`로 바꾼다. ko 요청은 예외가 만든 상세 문구를 그대로 둔다. 예외마다 메시지 키를 두는 대신 code 단위 문구를 쓴다. 화면은 이미 code로 분기하고, 전 예외를 키로 옮기는 변경은 범위에 비해 위험이 크다.
3. **AI 실패 문구는 `AiErrorMessages`가 언어별로 만든다.** 영어는 같은 키의 `AiErrorMessagesEn`이다. 응답 때 계산하는 문구(Agent 턴 `error_message`, 질의 run `error`, 위키 정리 `error`)는 요청 언어로 만든다.
4. **저장되는 실패 문구는 한국어로 저장하고 코드를 함께 남긴다.** 문서(`documents.error_code`, 변환·편입 실패에도 저장), 채팅 말풍선(`chat_messages.error_code`, V110), 회의 전사(`meetings.error_code`, V110). 응답 때 코드가 있으면 `AiErrorMessages.localizeStored`가 저장 문구를 만든 기능(Agent·질의·편입·정리·변환·전사)을 찾아 요청 언어로 다시 만든다. 코드가 없는 이전 행은 저장된 한국어 문구 그대로이며, 화면은 코드가 있으면 코드로 번역할 수 있다.
5. **AI에는 command의 `response_language`("ko"|"en")로 넘긴다.** 질의·Agent·회의록 command에 요청 시점 값을 담는다. command는 outbox에 저장되므로 비동기 작업도 요청 시점 언어로 처리된다. 결과 이벤트의 `request.response_language`로 SSE 실패 문구의 언어를 정한다.
6. **위키 편입은 언어를 넘기지 않는다.** 위키 페이지는 워크스페이스가 함께 쓰는 자산이라 요청자 언어로 바꾸지 않고 원문 언어를 따른다.

## 대안과 기각 사유
- **예외마다 메시지 키를 두기:** 가장 정확하지만 예외 생성 지점 수백 곳을 바꿔야 한다. code 단위 영어 문구로 시작하고, 상세 문구가 필요한 code만 키를 늘린다.
- **저장 문구를 요청 언어로 저장:** 비동기 결과는 요청이 끝난 뒤 저장되고, 같은 행을 다른 언어 사용자가 볼 수 있다. 코드를 저장하고 응답 때 만든다.
- **실패 문구에 기능 이름을 붙인 코드 저장:** 화면이 이미 쓰는 코드 체계를 바꾼다. 저장된 한국어 문구로 기능을 판별하면 코드를 바꾸지 않아도 된다.

## 결과
- 헤더가 없거나 ko면 응답과 command 동작이 이전과 같다(command에 `response_language: "ko"`가 더해진다).
- en 오류 문구는 code 단위라 한국어 상세 문구(예: 어느 필드가 왜 틀렸는지)보다 덜 구체적이다.
- 남은 한국어 고정 문구: 회의 실시간 받아쓰기 WebSocket 오류 메시지, 앱 안 알림 본문, pipeline 응답을 그대로 중계하는 오류 본문, 회의록 초안 API(`MEETING_NOTES_*`)는 코드만 준다.
