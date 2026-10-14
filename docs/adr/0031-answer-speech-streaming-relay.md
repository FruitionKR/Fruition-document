# ADR-0031: 답변 음성 듣기 스트리밍 중계와 저장

- 상태: 채택
- 관련: [#113](https://github.com/FruitionKR/Fruition-document/issues/113)

## 맥락

채팅 답변을 음성으로 들려준다. 음성 합성은 답변 길이에 비례해 오래 걸리고 사용자는 도중에 멈출 수 있다. 처음 소리가 나오기까지의 시간이 중요하고, 멈춘 뒤에는 공급사 비용이 더 늘지 않아야 한다. 같은 답변을 다시 들을 때마다 합성하면 비용이 반복된다.

## 결정

- **끝에서 끝까지 스트리밍**: AI → document → 브라우저가 같은 NDJSON 이벤트(`audio`/`done`/`error`)를 쓴다. document는 줄을 해석하지 않고 그대로 중계하고(오디오 모으기 위해 읽기만 한다) 줄마다 flush한다. 스트림이 시작되기 전의 오류만 HTTP 상태다.
- **멈춤 = 연결 끊기**: 별도 취소 API가 없다. 브라우저가 끊으면 document의 쓰기가 실패하고, document가 AI 연결을 닫으면 AI가 공급사 응답을 취소하고(최대 5초 기다려 usage를 받아) 호출을 `cancelled` 상태와 사용량으로 원장에 남긴다. document는 `cancelled`를 `succeeded`와 같이 단가대로 청구하므로 생성된 만큼 청구된다. 원장 행이 아직 `started`면 수집이 실패해 기존 수집 대기열 재시도가 다시 가져온다.
- **서블릿 스레드에서 직접 쓴다**: `StreamingResponseBody`로 비동기 처리하면 MVC 비동기 제한 시간(기본 30초)에 걸린다. 읽기 제한은 전체가 아니라 청크 사이 간격이다.
- **저장은 끝까지 간 합성만**: AI의 `done`을 받은 합성만 WAV로 `assets/{workspace}/speech/{message}.wav`에 저장하고(`chat_message_speech`), 같은 엔드포인트가 같은 형식으로 다시 들려준다. 재생은 AI를 부르지 않고 과금하지 않는다. 30일 뒤 지운다.
- **단가**: `gpt-realtime-2.1-mini` 텍스트 입력 $0.60 / 출력(전부 오디오) $20.00 / 1M 토큰. 호출의 출력 토큰을 모두 오디오로 본다.

## 대안과 기각 사유

- **전체 합성 뒤 한 번에 응답**: 구현이 단순하지만 첫 소리까지 답변 길이만큼 기다리고, 멈춰도 합성이 끝까지 간다.
- **SSE 또는 WebSocket**: 단방향 스트림이라 NDJSON 한 응답으로 충분하다. WebSocket은 인증·idle timeout 처리가 더 든다.
- **취소 전용 API**: 연결 끊김이 이미 신호이고, 별도 API는 요청 식별·순서 문제를 만든다.
- **저장 객체를 `meetings/`나 새 접두사에 두기**: document 서비스 역할에 이미 `assets/*` 읽기·쓰기·삭제가 있어 권한 변경 없이 쓸 수 있다.
- **취소된 합성도 저장**: 앞부분만 있는 음성을 완성본처럼 재생하게 된다.

## 결과

- 처음 소리까지의 시간이 AI 첫 청크에 가깝다. 같은 답변의 재생은 비용이 없다.
- 합성하는 동안 서블릿 스레드 하나와 임시 파일(최대 256 MiB, 넘으면 저장만 포기)을 쓴다. DB 연결은 스트림 내내 쥐지 않도록 `hibernate.connection.handling_mode=DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION`을 전역으로 설정했다(open-in-view는 유지).
- 같은 메시지를 동시에 두 번 요청하면 둘 다 합성·과금하고 나중 저장이 덮어쓴다.
- 배포: document에 `SPEECH_SYNTHESIS_ENDPOINT`를 주입해야 한다. 로드밸런서 idle timeout은 청크 간격보다 길어야 한다.
- 버킷 버전 관리 때문에 객체 삭제와 30일 만료는 비현재 버전을 남긴다. Fruition-flatform PR #77이 버킷 전체에 비현재 버전 30일 만료를 추가해 이를 덮는다.
