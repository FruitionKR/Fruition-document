# Document 빌드·실행

이 문서의 명령은 이 서비스 저장소 루트에서 실행합니다. Java 21이 필요하며 통합 테스트는 Docker의 임시 Testcontainers를 사용합니다.

```bash
./gradlew test bootJar
./gradlew bootRun
docker build -t fruition-document-svc:local .
```

연결 정보는 환경변수 또는 서비스 루트 `.env`에 둡니다. 외부 파일은 `SERVICE_ENV_FILE=/절대/경로/.env ./gradlew bootRun`으로 지정합니다. 서비스가 요구하는 설정은 `src/main/resources/application*.properties`에서 관리합니다. DB·Redis·다른 API 등의 실행 환경은 별도로 준비해야 합니다.

API 계약을 의도적으로 바꾸면 `./gradlew test -DupdateOpenApiSnapshot=true`로 `api-specs/openapi.yaml`을 갱신합니다. 배포용 migration은 같은 JAR의 `--migrate-only` 명령을 사용하며 migration 전용 계정이 필요합니다. 공용 인프라 생성과 배포 순서는 platform이 관리합니다.

## 회의 실시간 받아쓰기 연결

`SPEECH_LIVE_ENDPOINT`에 AI 실시간 전사 WebSocket 주소를 설정한다(기본 `ws://localhost:8000/speech/transcriptions/live`). 연결할 때 `INTERNAL_CALLBACK_TOKEN`을 `X-Internal-Token`으로 보낸다. 브라우저 접속 Origin은 `CORS_ALLOWED_ORIGINS`로 검사한다. 배포 경로의 WebSocket 허용·idle timeout은 platform이 관리한다([ADR-0022](https://github.com/FruitionKR/Fruition-flatform/blob/main/docs/adr/0022-realtime-speech-transcription.md)).

녹음 파일 전사는 `SPEECH_TRANSCRIPTION_ENDPOINT`(기본 `http://localhost:8000/speech/transcriptions`, 대기 `SPEECH_TRANSCRIPTION_TIMEOUT_SECONDS` 150초)를 쓴다. 작업자 주기는 `app.meeting.transcription-poll-interval-ms`(기본 2000)다. 회의록 초안은 `MEETING_NOTES_ENDPOINT`(기본 `http://localhost:8000/meeting-notes/preview`)로 AI를 호출하며 `MEETING_NOTES_TIMEOUT_SECONDS`(기본 150초)까지 기다린다.

```bash
./gradlew test --tests 'fruition.core.meeting.*'
```

테스트는 같은 앱 안에 가짜 AI WebSocket을 띄워 역순·중복·충돌 완료, 재연결 순서, 종료 개수 대조, ticket·Origin 거절을 확인한다. 실제 AI·모델 연결은 포함하지 않는다.

## 모델 사용량 API 연결

`MODEL_USAGE_ENDPOINT`에 AI `/usage/models`의 내부 주소를 설정한다(기본 `http://localhost:8000/usage/models`). `INTERNAL_CALLBACK_TOKEN`을 AI와 동일하게 주입한다. AI 원장 migration과 새 AI API 배포 후 document 서비스를 새 코드로 적용한다. 금액 환산이나 사용량 UI는 포함하지 않는다.
