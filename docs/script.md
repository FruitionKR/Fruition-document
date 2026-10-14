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

녹음 파일 전사는 `SPEECH_TRANSCRIPTION_ENDPOINT`(기본 `http://localhost:8000/speech/transcriptions`, 대기 `MEETING_TRANSCRIPTION_TIMEOUT_SECONDS` 960초, `deadline_seconds`는 보내지 않는다)를 쓴다. 작업자 주기는 `app.meeting.transcription-poll-interval-ms`(기본 2000)다. 회의록 초안은 Kafka 명령(`ai.maintenance.command`)으로 AI에 보내고 결과를 `ai.task.event`로 받는다. 요청은 `202`로 끝나고(결과는 조회로 기다린다), 결과가 없는 `generating`은 `MEETING_NOTES_GENERATION_TIMEOUT_MINUTES`(기본 30분) 뒤 실패로 정리한다.

```bash
./gradlew test --tests 'fruition.core.meeting.*'
```

테스트는 같은 앱 안에 가짜 AI WebSocket을 띄워 역순·중복·충돌 완료, 재연결 순서, 종료 개수 대조, ticket·Origin 거절을 확인한다. 실제 AI·모델 연결은 포함하지 않는다.

## 채팅 음성 입력 연결

`SPEECH_TRANSCRIPTION_ENDPOINT`에 AI 파일 전사 주소를 설정한다(기본 `http://localhost:8000/speech/transcriptions`). `INTERNAL_CALLBACK_TOKEN`을 AI와 동일하게 주입한다. 채팅은 AI에 `deadline_seconds=100`(ALB 유휴 120초 안)을 보내고, 응답 대기는 `SPEECH_TRANSCRIPTION_TIMEOUT_SECONDS`(기본 150초, 100초보다 길게)다.

답변 음성 듣기는 `SPEECH_SYNTHESIS_ENDPOINT`(기본 `http://localhost:8000/speech/synthesis`)의 NDJSON 스트림을 중계한다. 청크 사이 대기 제한은 `SPEECH_SYNTHESIS_READ_TIMEOUT_SECONDS`(기본 30초)이고 전체 시간 제한은 없다. 크레딧 예약 상한은 `BILLING_ESTIMATE_SPEECH_SYNTHESIS`(기본 500000 milli-KRW)다. 중계는 서블릿 스레드 하나를 합성이 끝날 때까지 점유하므로 로드밸런서 idle timeout은 청크 간격보다 길어야 한다.

```bash
./gradlew test --tests 'fruition.core.speech.*'
```

테스트는 JDK 내장 HTTP 서버로 가짜 AI를 띄워 요청 전달·형식·크기·권한·오류 변환을 확인한다.

## 모델 사용량 API 연결

`MODEL_USAGE_ENDPOINT`에 AI `/usage/models`의 내부 주소를 설정한다(기본 `http://localhost:8000/usage/models`). `INTERNAL_CALLBACK_TOKEN`을 AI와 동일하게 주입한다. AI 원장 migration과 새 AI API 배포 후 document 서비스를 새 코드로 적용한다. 금액 환산이나 사용량 UI는 포함하지 않는다.

호출 단위 청구(#78)는 `MODEL_USAGE_CALLS_ENDPOINT`에 AI `/internal/model-usage/calls`의 내부 주소를 설정한다(기본 `http://localhost:8000/internal/model-usage/calls`). 수집 주기 `USAGE_CHARGE_COLLECT_INTERVAL_MS`(기본 5초), 대사 주기 `USAGE_CHARGE_RECONCILE_INTERVAL_MS`(기본 하루), `unpriced`·`needs_review` 재계산 주기 `USAGE_CHARGE_RECOMPUTE_INTERVAL_MS`(기본 1시간). 환율은 `KOREAEXIM_AUTH_KEY`(한국수출입은행 환율 OpenAPI 인증키)가 있으면 평일 11:30(KST)과 기동 시 자동 갱신하고, 비어 있으면 `[환율 갱신 실패]` 경고만 남기고 V72 초기 환율을 쓴다. V72 정책·환율 시드는 마이그레이션 시각부터 적용되므로 그 전 호출은 재계산해도 `unpriced`로 남고 크레딧에서 소급 차감되지 않는다. 카탈로그 모델 공식 단가는 V70 마이그레이션이 넣고, 바뀐 단가·환율·정책은 운영 SQL로 새 행을 넣는다(`scripts/sql/insert-usage-price-versions.sql`). 단가·폐기 일정 주간 점검은 `python3 scripts/ai_price_drift.py --dry-run`으로 로컬에서도 돌려 볼 수 있다(이슈를 만들지 않음). AI API가 배포되기 전에는 수집이 실패 로그만 남기고 재시도한다.

선불 크레딧(#79·#102)은 `BILLING_ENFORCE`(기본 false)가 true일 때만 가용 잔액이 kind별 최저 비용(`app.billing.minimum.<kind>`, 없으면 예상 상한의 10%)보다 작은 요청을 402로 거절하고 `min(상한, 가용 잔액)`을 예약한다. false면 상한 전체를 예약·정산만 기록한다. 실사용자 환경에서만 true로 켠다(켜기 전에 테스트 계정에 `scripts/sql/credit-grant-adjust.sql`로 `grant`를 지급한다). kind별 예상 상한은 `BILLING_ESTIMATE_<KIND>`(milli-KRW), 기본 `BILLING_DEFAULT_ESTIMATE_KRW_MILLI`. ingest 뒤 품질 평가(post_ingest) 동안 ingest 예약을 남기는 상한 시간은 `BILLING_POST_INGEST_HOLD_SECONDS`(기본 900). 수동 지급·조정은 `scripts/sql/credit-grant-adjust.sql`, 잔액 점검은 `scripts/sql/credit-balance-check.sql`.

PG 결제 충전·환불(#80)은 PG를 정하기 전까지 `BILLING_PAYMENTS_ENABLED=false`(기본)로 둔다. 켤 때 `PG_CONFIRM_ENDPOINT`·`PG_PAYMENT_ENDPOINT`·`PG_CANCEL_ENDPOINT`·`PG_SECRET_KEY`·`PG_WEBHOOK_SECRET`을 주입하고 PG 관리 화면에 webhook 주소 `/internal/payments/webhook`을 등록한다. 상품표는 `app.billing.product.<code>.amount-krw`·`.credit-krw-milli`다(ADR-0026).
