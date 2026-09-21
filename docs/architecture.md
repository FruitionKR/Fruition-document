# Document 구조

Document는 문서·폴더·채팅·Wiki 공개 API와 AI 요청 중계를 담당하는 Spring Boot 서비스입니다. 사용자 API 포트는 8080입니다.

- `src/main/java/fruition/core/`: 업무 API·권한 확인·문서 저장·AI 작업 관리
- `src/main/java/fruition/shared/`: 이 저장소가 소유하는 기술 코드
- `src/main/resources/db/migration/`: core_db Flyway migration
- `api-specs/openapi.yaml`: HTTP 계약
- `scripts/sql/`: 문서 DB 전용 점검 SQL

워크스페이스 권한은 Access의 내부 API·Redis projection으로 확인합니다. AI 작업은 HTTP 또는 Kafka로 AI에 전달하며 PDF 변환은 converter를 호출합니다. 실행 상태·문서 변경·outbox는 core_db에서 관리하고 AI의 Wiki 현재 상태·embedding·checkpoint는 AI가 소유합니다.

서비스 간 메시지·복구·권한 경계는 [공통 아키텍처](https://github.com/FruitionKR/Fruition-flatform/blob/main/docs/architecture.md), 공개 endpoint는 [API 문서](api/README.md)를 따릅니다.

## 모델 사용량 책임

AI는 ai_db의 호출별 사용량 원장을 소유하고 내부 API로 모델·입력·출력·캐시·추론 토큰과 미확인 호출 수를 전달한다. 백엔드는 workspace 멤버 및 로그인 사용자 범위를 강제한다. 단가 관리·금액 환산·크레딧·세금 정책은 백엔드 책임이며 이 사용량 API는 청구 금액을 반환하지 않는다.
