# Infrastructure 변경 기록

## 2026-10-24

- 빌드 JDK·운영 JRE·CI를 Temurin 21.0.13+9로 고정해 10월 보안 수정과 갱신된 신뢰 저장소를 적용합니다. Docker Hub 태그 게시 지연으로 공식 Linux amd64/arm64 배포 파일을 고정 SHA-256으로 검증해 설치합니다.
- 기존 Java 설치를 제거한 뒤 교체하며, API·DB 스키마 변경은 없습니다. 검증 결과는 PR과 배포 기록에 남깁니다.

## 2026-10-22

- 통합 테스트가 실행 중인 dev Kafka consumer group에 참여하고 로컬 AI를 호출하던 기본 연결을 test 전용 프로필로 격리했다. Gradle test는 Kafka listener·topic 자동 생성을 끄고 외부 서비스 기본 주소를 loopback의 비서비스 포트로 지정하며 실제 통신 테스트는 자체 fixture 주소로 덮어쓴다.
- 제품 설정·스케줄러를 변경하지 않고 기본 listener 중지와 HTTP fixture override를 11개 대상 테스트로 검증했다. 테스트용 PostHog relay는 실제 프로젝트 토큰을 상속하지 않는다.

- JDBC runtime 및 독립 migration 연결에 `PGSSLROOTCERT`를 전달하여 운영 `PGSSLMODE=verify-full`과 RDS CA 번들을 사용할 수 있게 했다.
- 배포 시 Document에는 JWT 공개키만 전달한다. Access 개인키를 전달하지 않으며, Access 발급 전환 전에 Document의 공개키 검증을 먼저 배포해야 한다.
- CA 번들 마운트와 `verify-full` 설정은 운영 인프라 변경에서 적용한다. 이 서비스 변경만으로 기존 DB 연결 모드는 바뀌지 않는다.
