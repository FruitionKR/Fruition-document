# Document 구조

Document는 문서·폴더·채팅·Wiki 공개 API와 AI 요청 중계를 담당하는 Spring Boot 서비스입니다. 사용자 API 포트는 8080입니다.

- `src/main/java/fruition/core/`: 업무 API·권한 확인·문서 저장·AI 작업 관리
- `src/main/java/fruition/shared/`: 이 저장소가 소유하는 기술 코드
- `src/main/resources/db/migration/`: core_db Flyway migration
- `api-specs/openapi.yaml`: HTTP 계약
- `scripts/sql/`: 문서 DB 전용 점검 SQL

워크스페이스 권한은 Access의 내부 API·Redis projection으로 확인합니다. Redis 값(`authz:role:v2:{workspace_id}:{user_id}`, TTL 300초)은 `{"role":"MEMBER","permissions":["wiki.ingest"]}` JSON이고, 옛 키(`authz:role:{workspace_id}:{user_id}`, 평문 역할)는 구버전 pod 전용이라 읽지도 쓰지도 않습니다. 읽을 수 없거나 알 수 없는 역할이면 miss로 보고 내부 API 결과로 덮어씁니다. 위키 편입·정리, 팀 Skill 관리, 남의 위키 작업 복구, 남의 PDF 변환은 이 권한 항목으로 판정하고 없으면 `403 WORKSPACE_PERMISSION_REQUIRED`입니다. 업로드·변환 뒤 자동 편입은 워크스페이스 설정과 요청자의 `wiki.ingest`가 모두 있을 때만 합니다. 결정 근거: [ADR-0030](adr/0030-wiki-permission-enforcement.md). AI 작업은 HTTP 또는 Kafka로 AI에 전달합니다. PDF 변환은 쪽 범위(batch, 기본 10쪽) command를 Kafka `ai.convert.command`(env `CONVERT_COMMAND_TOPIC`, key는 batch `run_id`)로 발행하고, converter worker가 `ai.task.event`(`kind=convert_batch`)로 돌려준 결과를 쪽 순서로 병합합니다. 한 문서에 batch를 최대 3개(`CONVERT_MAX_IN_FLIGHT`)까지 동시에 열고, 결과 Markdown은 object storage(`tmp/convert/`)에서 읽습니다. 계약은 [AI ADR 0034](https://github.com/FruitionKR/Fruition-ai/blob/main/docs/adr/0034-async-pdf-conversion.md)를 따릅니다. 실행 상태·문서 변경·outbox는 core_db에서 관리하고 AI의 Wiki 현재 상태·embedding·checkpoint는 AI가 소유합니다.

서비스 간 메시지·복구·권한 경계는 [공통 아키텍처](https://github.com/FruitionKR/Fruition-flatform/blob/main/docs/architecture.md), 공개 endpoint는 [API 문서](api/README.md)를 따릅니다.

## 회의 실시간 받아쓰기

회의 받아쓰기는 브라우저 ↔ document-svc ↔ AI 실시간 전사 WebSocket을 연결마다 1:1로 중계한다. document-svc는 일회용 ticket(Redis, 60초)과 Origin으로 접속을 인증하고, 회의당 연결 1개를 Redis 잠금으로 보장한다. AI 이벤트 중 `committed`에서 발화 순서(position)를 정하고 `completed`의 확정 문장을 core_db에 저장한 뒤에만 브라우저에 전달한다. 녹음 종료·일시정지는 연결의 `finish`로만 받고, AI의 `finished` 구간 수와 저장 수가 같을 때 연결을 정상 종료로 기록한다. AI는 오디오·전사를 저장하지 않는다. 결정 근거: [ADR-0023](adr/0023-meeting-transcripts-and-recordings.md), [platform ADR-0022](https://github.com/FruitionKR/Fruition-flatform/blob/main/docs/adr/0022-realtime-speech-transcription.md).

## 녹음 원본과 파일 전사

녹음 원본은 S3/MinIO에 회의 삭제 전까지 보관하고 5분 presigned 주소로 재생한다. 브라우저는 녹음을 S3에 multipart로 직접 올리고(서명 ticket·presigned 조각 URL, 문서 직접 업로드와 같은 절차, 500 MiB) 서버는 확정만 한다. 녹음 파일 회의는 확정 트랜잭션에서 크레딧을 예약하고 `meeting_transcription` command를 outbox로 Kafka `ai.maintenance.command`(key=`run_id`)에 발행한다. AI가 오디오를 S3에서 읽어 시간 단위로 나눠 전사하고 `ai.task.event`(`kind=meeting_transcription`)로 돌려주면, 회의의 `transcription_run_id`와 같은 run일 때만 약 1,000자 단위 구간으로 저장한다(회의록 AI 1,000구간 한도). 전사 취소는 회의를 닫고 AI run 취소를 요청한다. 회의 삭제는 행을 잠근 채 원본을 먼저 지우고 DB 행을 지운다.

## 회의록 초안과 저장

회의가 끝나면 확정 전사를 outbox 명령(`ai.maintenance.command`, `kind=meeting_notes`)으로 AI에 보내 초안을 만들고 core_db에 버전별로 보관한다. 크레딧 예약·run 등록·명령 저장은 한 트랜잭션이고, 결과는 `ai.task.event`로 받아 `ai_run_id`로 찾은 자기 버전 행에만 쓴다. 30분 넘게 결과가 없는 `generating`은 조회 시 실패로 정리한다. 문서 본문은 AI 응답의 markdown 대신 항목 배열로 다시 만들어 근거 ID를 넣지 않는다. 저장은 기존 Markdown 문서 생성·본문 저장 함수를 그대로 쓰며, 대상·본문을 먼저 기록해 같은 요청의 재시도가 같은 저장을 반복한다. 결정 근거: [ADR-0023](adr/0023-meeting-transcripts-and-recordings.md).

## 채팅 음성 입력

채팅 음성 입력은 오디오 bytes를 AI 파일 전사 API에 HTTP로 중계하고 텍스트만 돌려준다. 음성과 텍스트는 저장하지 않으며 결과를 질의·Agent에 자동 제출하지 않는다. 형식·크기·멤버십은 AI 호출 전에 검사한다.

## 모델 사용량 책임

AI는 ai_db의 호출별 사용량 원장을 소유하고 내부 API로 모델·입력·출력·캐시·추론 토큰과 미확인 호출 수를 전달한다. 백엔드는 workspace 멤버 및 로그인 사용자 범위를 강제한다. 단가 관리·금액 환산·크레딧·세금 정책은 백엔드 책임이며 이 사용량 API는 청구 금액을 반환하지 않는다.
