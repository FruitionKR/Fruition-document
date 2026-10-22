# Infrastructure 변경 기록

## 2026-10-22

- JDBC runtime 및 독립 migration 연결에 `PGSSLROOTCERT`를 전달하여 운영 `PGSSLMODE=verify-full`과 RDS CA 번들을 사용할 수 있게 했다.
- 배포 시 Document에는 JWT 공개키만 전달한다. Access 개인키를 전달하지 않으며, Access 발급 전환 전에 Document의 공개키 검증을 먼저 배포해야 한다.
- CA 번들 마운트와 `verify-full` 설정은 운영 인프라 변경에서 적용한다. 이 서비스 변경만으로 기존 DB 연결 모드는 바뀌지 않는다.
