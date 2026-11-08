# 반복 로그 정책

운영 기본 INFO에서 정상 반복 조회·진행 이벤트는 DEBUG로 제외한다. 조회 실패(HTTP 4xx/5xx), 2초 이상 느린 조회, 필터 밖으로 전파된 예외는 기록한다. 전체 GET 요청을 제외하지 않고 HttpRequestLoggingFilter의 명시적 경로 목록만 적용한다. SSE 비동기 완료 시간은 별도로 다뤄야 하며 필터 시간은 작업 완료 시간이 아니다.

RepeatedFailureLogger는 최초 오류에 원래 레벨·스택을 유지하고, 같은 키·예외 타입·레벨의 반복은 60초 이상 지난 다음 실패 시 요약한다. totalCount는 최초부터의 누적 실패 수, suppressedCount는 직전 출력 이후 합쳐진 반복 수다. 성공이 확인되면 복구 로그와 미출력 횟수를 남긴다. 별도 주기 스레드는 없으므로 요청이 없으면 요약도 발생하지 않는다.

집계는 프로세스별이며 인스턴스 간 공유하지 않는다. helper 인스턴스마다 최대 1024키, 1시간 비활성 항목은 다음 실패 호출 때 제거한다. 용량 초과 시 오래된 키를 제거하며 그 대상의 다음 오류는 다시 최초 오류로 보인다. 예외 타입·레벨 변경도 새 상세 오류로 기록한다. 미출력 항목의 소멸은 복구로 간주하지 않는다.

Document는 결과 소비, 명령·편집 outbox, 채팅→위키 후처리, 해시 계산, 권한 캐시·내부 API 장애, 파이프라인 상태 조회 오류, 분석 relay DB 장애에 적용한다. Access는 데이터 파기, 이메일 변경 알림 재시도, 분석 relay DB 장애에 적용한다. 메일 최종 포기 오류는 유지한다. 단일 S3 재시도·최종 업무 실패·공통 API 예외는 별도로 유지한다.

Kafka 무제한 재시도와 ACK 정책은 유지한다. Spring 오류 핸들러의 중복 예외 출력은 DEBUG로 내리고 결과 소비자가 최초 오류·요약을 책임진다. 잘못된 JSON도 별도 오류로 남기되 원문 payload는 출력하지 않는다. 파싱 실패는 성공한 다른 레코드가 복구로 오인하지 않도록 복구 로그를 내지 않는다.

DEBUG로 내리는 것은 수집기 필터가 아니다. 운영에 DEBUG를 켜면 해당 로그가 다시 출력되므로 PostHog/CloudWatch 수집 레벨도 함께 확인한다. 운영 배포 전에는 dev 반영만으로 실행 중 서버의 로그가 바뀌지 않는다.

## 업무 로그 작성 규칙

사용자가 "작업이 실패했다"고 제보하면 사용자·요청·작업 ID로 기능·단계·원인을 찾을 수 있게 남긴다. HTTP 200은 업무 완료가 아니다.

형식: `[한국어 기능·결과] operation=<영문> stage=<영문> errorCode=<영문> <식별자> 원인=<한국어>` (성공은 `설명=`)

- `operation`: 기능. 예) `document_upload`, `wiki_ingest`, `skill_run`, `agent_apply`, `operation_restore`, `ai_task_cancel`.
- `stage`: 단계. `requested`·`accepted`(접수), `forwarded`(전달), `completed`(최종 완료)를 구분한다. `accepted`와 `forwarded`는 완료가 아니다.
- `errorCode`: `CommittedLog.REASONS`에 등록한 코드만 쓴다. AI가 보낸 문자열은 `CommittedLog.stableCode`로 거르고 원래 값은 `upstreamCode`에 둔다. 모르면 `UNCLASSIFIED`로 남기고 추정하지 않는다.
- `rollback`: `user_requested`는 사용자의 작업 되돌리기, `task_cancel`은 실행 취소의 변경 복구다. DB 트랜잭션 롤백은 `trigger=transaction_rollback`으로 따로 쓴다.
- 용어: `AI 편집`은 Agent 편집안을 문서에 반영한 작업(`document_edit`), `위키 편입`은 문서를 위키로 만드는 작업(`ingest`), `위키 점검`은 위키를 다듬는 작업(`lint`)이다. `AI 실행`은 종류를 가리지 않는 실행 단위로 `kind`로 구분한다. 되돌리기 로그는 제목과 `targetType`에 대상 종류를 남긴다.
- `partial=true`: 일부만 저장됐다. `retryable`: 다시 시도할 수 있는지 또는 시스템이 다시 시도하는지.

성공 로그는 동기 작업이면 완료 한 줄(소요 시간·대상·결과 포함), 비동기 작업이면 접수와 완료 두 줄만 `operation=`을 붙여 전송한다. 중간 단계(요청, 원본 저장, 전달, 도구 호출 응답)는 `operation=` 없이 콘솔에만 남기거나 DEBUG로 둔다. 실패는 단계마다 남긴다.

최종 상태는 `CommittedLog`로 남겨 롤백되면 남지 않게 한다. 멱등 재요청은 `[멱등 재요청 재생]`만 남고 완료 로그를 다시 내지 않는다. 공통 예외 처리(`[예외]`)가 남기는 스택을 기능 로그에서 반복하지 않는다. 기능 로그는 예외 타입과 단계만 더한다.

레벨: DEBUG는 반복 조회·폴링·내부 진행, INFO는 주요 단계 성공·최종 완료·복구, WARN은 예상 가능한 거부·제한·일시 실패, ERROR는 서버 장애·최종 처리 실패·복구 실패다. HTTP 상태만으로 정하지 않는다. 예를 들어 AI가 입력을 거절한 최종 실패는 WARN이다.

브라우저가 S3로 직접 보내는 조각 전송은 서버가 볼 수 없다. 서버 로그는 시작(`started`)과 조립(`assembled`)만 안다.

## PostHog Logs 전송

콘솔 로그를 유지한 채 `PostHogLogExport`가 OTLP로 `{POSTHOG_HOST}/i/v1/logs`에 함께 보낸다. Product Analytics 이벤트를 쓰지 않는다.

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `POSTHOG_LOGS_ENABLED` | `false` | `true`일 때만 전송한다. 분석 이벤트의 `ANALYTICS_ENABLED`와 별개다. |
| `POSTHOG_PROJECT_TOKEN` | 없음 | 기존 분석 이벤트 토큰을 재사용한다. 없거나 형식이 틀리면 경고 후 전송만 끈다. |
| `POSTHOG_HOST` | `https://us.i.posthog.com` | 기존 값을 재사용한다. |
| `ANALYTICS_ENVIRONMENT`, `APP_VERSION` | `local`, `unknown` | `deployment.environment`, `service.version`이 된다. |

전송 대상: `fruition.*`의 WARN·ERROR 전체, `operation=` 필드가 있는 INFO, 외부 라이브러리의 ERROR(문구는 보내지 않고 logger와 예외 타입만). DEBUG, HTTP 요청 시작·완료, `operation=`이 없는 INFO는 보내지 않는다.

허용 필드만 보낸다(`PostHogLogPolicy`). 목록에 없는 `key=value`의 값은 `***`로 바꾼다. 파일명, 저장 경로, 이메일, `message`, `error`는 콘솔에만 남는다. 코드 필드는 값 전체가 코드 형식이어야 하고, 식별자 필드는 첫 단어만 받는다. 토큰·이메일·URL·저장 키 형태는 본문에서 한 번 더 지운다. 예외는 메시지 없이 타입과 코드 위치만 보낸다. 새 필드를 검색하려면 허용 목록에 추가한다. `원인=`, `설명=`은 그대로 나가므로 고정 문구만 쓴다.

장애 격리: 호출 스레드는 큐(최대 2048건)에 넣기만 하고 5초 주기·256건 단위로 보낸다. 요청 타임아웃 5초, 재시도 최대 3회다. 큐가 차면 전송만 버리고 콘솔 로그와 요청에는 영향이 없다. 종료 시 최대 10초 동안 큐를 비운다. `io.opentelemetry`·`okhttp3`·전송기 자신의 로그는 전송하지 않아 재귀가 없다. 인스턴스 초기화 전 로그와 강제 종료 시 큐에 남은 로그는 유실될 수 있다.

연결: `posthogDistinctId`에 `userId`를 넣는다. 프론트 `identify`와 서버 분석 이벤트의 distinct_id가 같은 사용자 ID라 사람 프로필에 연결된다. 프론트가 서버로 PostHog 세션 ID를 보내지 않아 세션 리플레이와는 연결되지 않는다. 로그의 채팅 `sessionId`는 리플레이로 오인되지 않게 `chatSessionId`로 바꿔 보낸다.

비용: 2026-11 공식 요금은 월 10GB 무료, 이후 GB당 $0.25, 보관 기본 14일이다. 건당 약 1KB로 잡으면(스택 포함 ERROR는 더 크다) 무료 구간은 월 약 1,000만 건, 하루 약 33만 건이다. 요청당 업무 로그는 보통 1~3건이다. 초과가 걱정되면 PostHog 결제 한도를 먼저 건다. 이 값은 추정이므로 실제 수집량으로 다시 확인한다.
