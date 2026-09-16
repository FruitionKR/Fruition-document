# Document 빌드·실행

이 문서의 명령은 이 서비스 저장소 루트에서 실행합니다. Java 21이 필요하며 통합 테스트는 Docker의 임시 Testcontainers를 사용합니다.

```bash
./gradlew test bootJar
./gradlew bootRun
docker build -t fruition-document-svc:local .
```

연결 정보는 환경변수 또는 서비스 루트 `.env`에 둡니다. 외부 파일은 `SERVICE_ENV_FILE=/절대/경로/.env ./gradlew bootRun`으로 지정합니다. 서비스가 요구하는 설정은 `src/main/resources/application*.properties`에서 관리합니다. DB·Redis·다른 API 등의 실행 환경은 별도로 준비해야 합니다.

API 계약을 의도적으로 바꾸면 `./gradlew test -DupdateOpenApiSnapshot=true`로 `api-specs/openapi.yaml`을 갱신합니다. 배포용 migration은 같은 JAR의 `--migrate-only` 명령을 사용하며 migration 전용 계정이 필요합니다. 공용 인프라 생성과 배포 순서는 platform이 관리합니다.
