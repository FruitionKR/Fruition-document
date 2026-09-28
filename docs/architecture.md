# Document 구조

Document는 문서·폴더·채팅·Wiki 공개 API와 AI 요청 중계를 담당하는 Spring Boot 서비스입니다. 사용자 API 포트는 8080입니다.

- `src/main/java/fruition/core/`: 업무 API·권한 확인·문서 저장·AI 작업 관리
- `src/main/java/fruition/shared/`: 이 저장소가 소유하는 기술 코드
- `src/main/resources/db/migration/`: core_db Flyway migration
- `api-specs/openapi.yaml`: HTTP 계약
- `scripts/sql/`: 문서 DB 전용 점검 SQL

워크스페이스 권한은 Access의 내부 API·Redis projection으로 확인합니다. AI 작업은 HTTP 또는 Kafka로 AI에 전달하며 PDF 변환은 converter를 호출합니다. 실행 상태·문서 변경·outbox는 core_db에서 관리하고 AI의 Wiki 현재 상태·embedding·checkpoint는 AI가 소유합니다.

서비스 간 메시지·복구·권한 경계는 [공통 아키텍처](https://github.com/FruitionKR/Fruition-flatform/blob/main/docs/architecture.md), 공개 endpoint는 [API 문서](api/README.md)를 따릅니다.

## 회의 실시간 받아쓰기

회의 받아쓰기는 브라우저 ↔ document-svc ↔ AI 실시간 전사 WebSocket을 연결마다 1:1로 중계한다. document-svc는 일회용 ticket(Redis, 60초)과 Origin으로 접속을 인증하고, 회의당 연결 1개를 Redis 잠금으로 보장한다. AI 이벤트 중 `committed`에서 발화 순서(position)를 정하고 `completed`의 확정 문장을 core_db에 저장한 뒤에만 브라우저에 전달한다. 녹음 종료·일시정지는 연결의 `finish`로만 받고, AI의 `finished` 구간 수와 저장 수가 같을 때 연결을 정상 종료로 기록한다. AI는 오디오·전사를 저장하지 않는다. 결정 근거: [ADR-0023](adr/0023-meeting-transcripts-and-recordings.md), [platform ADR-0022](https://github.com/FruitionKR/Fruition-flatform/blob/main/docs/adr/0022-realtime-speech-transcription.md).

## 녹음 원본과 파일 전사

녹음 원본은 S3/MinIO에 회의 삭제 전까지 보관하고 5분 presigned 주소로 재생한다. 녹음 파일 회의는 `status='transcribing'`을 대기열로 쓰는 작업자가 `FOR UPDATE SKIP LOCKED`로 한 건씩 선점해 AI 파일 전사를 부르고 문장 단위 구간으로 저장한다. 회의 삭제는 원본을 먼저 지우고 DB 행을 지운다.

## 회의록 초안과 저장

회의가 끝나면 확정 전사를 AI 회의록 API에 보내 초안을 만들고 core_db에 버전별로 보관한다. AI 호출은 트랜잭션 밖에서 하며 결과는 자기 버전 행에만 쓴다. 문서 본문은 AI 응답의 markdown 대신 항목 배열로 다시 만들어 근거 ID를 넣지 않는다. 저장은 기존 Markdown 문서 생성·본문 저장 함수를 그대로 쓰며, 대상·본문을 먼저 기록해 같은 요청의 재시도가 같은 저장을 반복한다. 결정 근거: [ADR-0023](adr/0023-meeting-transcripts-and-recordings.md).

## 모델 사용량 책임

AI는 ai_db의 호출별 사용량 원장을 소유하고 내부 API로 모델·입력·출력·캐시·추론 토큰과 미확인 호출 수를 전달한다. 백엔드는 workspace 멤버 및 로그인 사용자 범위를 강제한다. 단가 관리·금액 환산·크레딧·세금 정책은 백엔드 책임이며 이 사용량 API는 청구 금액을 반환하지 않는다.
