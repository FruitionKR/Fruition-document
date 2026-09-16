# Fruition Document

[한국어](#한국어) · [English](#english)

## 한국어

문서·채팅·Wiki·Skill gateway·query을 담당하는 독립 Spring Boot 서비스입니다. 이 폴더 전체가 GitHub 저장소 루트가 됩니다.

Java 21과 Docker(Testcontainers)가 필요합니다.

```bash
./gradlew test bootJar
./gradlew bootRun
docker build -t fruition-document-svc:local .
```

런타임 설정은 환경변수로 주입하거나 이 폴더의 `.env`에 둡니다. 다른 위치의 파일을 사용할 때는 `SERVICE_ENV_FILE=/절대/경로/.env ./gradlew bootRun`으로 지정합니다. 배포 컨테이너에는 환경변수로 주입합니다. 실제 설정 키와 기본값은 `src/main/resources/application*.properties`를 참고하세요.

DB `core_db`와 `src/main/resources/db/migration/`을 이 서비스가 소유합니다. 다른 서비스는 네트워크 API로 호출하며 다른 저장소의 소스는 빌드에 필요하지 않습니다. `src/main/java/fruition/shared/`는 이 저장소가 직접 관리하는 코드입니다.

API 계약은 `api-specs/openapi.yaml`입니다. 의도한 API 변경 후 `./gradlew test -DupdateOpenApiSnapshot=true`로 갱신합니다. GitHub CI는 테스트와 JAR 빌드를 실행합니다.

설계·API·데이터·실행 문서는 [docs 안내](docs/README.md)에서 관리합니다.

## English

An independent Spring Boot service for documents, chat, Wiki, the Skill gateway, and queries. This directory is the GitHub repository root.

Java 21 and Docker for Testcontainers are required.

```bash
./gradlew test bootJar
./gradlew bootRun
docker build -t fruition-document-svc:local .
```

Provide runtime settings through environment variables or a local `.env` file in this directory. To use a file elsewhere, run `SERVICE_ENV_FILE=/absolute/path/.env ./gradlew bootRun`. Inject environment variables into deployment containers. See `src/main/resources/application*.properties` for configuration keys and defaults.

This service owns `core_db` and `src/main/resources/db/migration/`. Other services communicate with it through network APIs; their source code is not required to build this repository. The code in `src/main/java/fruition/shared/` is maintained in this repository.

The API contract is `api-specs/openapi.yaml`. After an intentional API change, update it with `./gradlew test -DupdateOpenApiSnapshot=true`. GitHub CI runs tests and builds the JAR.

See the [documentation index](docs/README.md) for architecture, API, data, and execution documentation.

## 저작권 및 라이선스 / Copyright and License

**한국어**

저작권 (c) 2026 Fruition 팀. 모든 권리 보유.

Fruition 팀이 저작권을 보유하는 코드·문서·자산의 무단 사용을 금지합니다. 상업적·비상업적 목적의 사용·복제·수정·배포·재라이선스·판매에는 Fruition 팀의 사전 서면 허가가 필요합니다. 제3자 구성요소에는 각 라이선스가 적용됩니다. 적용 범위와 예외는 [LICENSE](LICENSE)를 참고하세요.

**English**

Copyright (c) 2026 Team Fruition. All rights reserved.

Unauthorized use of code, documentation, and assets copyrighted by Team Fruition is prohibited. Use, copying, modification, distribution, sublicensing, or sale for commercial or non-commercial purposes requires prior written permission from Team Fruition. Third-party components remain subject to their own licenses. See [LICENSE](LICENSE) for the scope and exceptions.
