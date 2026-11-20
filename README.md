# Fruition Document

**Fruition의 문서와 대화 데이터를 관리하고 AI 작업을 연결하는 서비스입니다.**

사용자가 문서를 올리고, 팀 위키를 확인하고, 질문하거나 회의록을 저장할 때 사용하는 API를 제공합니다. 작업 권한을 확인한 뒤 데이터를 저장하고 AI에 필요한 처리를 요청합니다.

> Fruition의 기획 배경과 주요 기능, 사용 화면은 **[서비스 소개 README](https://github.com/FruitionKR/Fruition-frontend#readme)**에서 확인하세요.

## 담당하는 기능

| 영역 | 역할 |
| --- | --- |
| 문서와 폴더 | 문서 저장·조회, 폴더 관리, 파일 업로드와 변환 작업 관리 |
| 위키와 채팅 | 팀 위키 공개 API, 질문·대화 기록, AI 요청과 결과 전달 |
| 회의 | 실시간 받아쓰기 중계, 녹음 파일 관리, 전사·회의록 초안 저장 |
| Skill | 팀 Skill API와 AI 작업 중계 |
| 작업 상태 | 실행 상태, 문서 변경 기록, 비동기 명령과 결과 관리 |

인증과 워크스페이스 권한은 **Access**에서 확인합니다. 실제 AI 처리와 위키의 현재 상태·임베딩·체크포인트는 **AI**가 담당합니다. Document는 그 결과를 사용자 API와 문서 저장 흐름으로 연결합니다.

## 기술과 데이터

- **Java 21 · Spring Boot** 기반의 독립 서비스입니다.
- **PostgreSQL**의 `core_db`와 [Flyway 마이그레이션](src/main/resources/db/migration)을 소유합니다.
- **S3/MinIO**에 문서와 녹음 파일을 저장하고, **Redis**로 권한 정보와 실행 이벤트 등을 공유합니다.
- **HTTP · WebSocket · Kafka**로 AI 요청과 결과를 전달합니다.
- 사용자 API의 기본 포트는 **8080**입니다.

다른 서비스의 소스 없이 이 저장소만으로 빌드할 수 있습니다. `src/main/java/fruition/shared/`도 이 저장소에서 직접 관리하는 코드입니다.

## 로컬 실행

아래 명령은 저장소 루트에서 실행합니다. **Java 21**을 설치하고, 통합 테스트에 필요한 **Docker**를 실행해 두세요.

서비스를 실제로 사용하려면 PostgreSQL, Redis, S3/MinIO, Kafka와 Access·AI 연결이 필요합니다. 전체 개발 환경은 [플랫폼 실행 안내](https://github.com/FruitionKR/Fruition-flatform#readme)를 따릅니다.

### 1. 환경 설정

환경변수를 주입하거나 저장소 루트에 `.env` 파일을 준비합니다. 설정 키와 기본값은 [application.properties](src/main/resources/application.properties) 및 같은 폴더의 프로필별 설정에서 확인할 수 있습니다.

| 설정 | 용도 |
| --- | --- |
| `JWT_SECRET` | Access가 발급한 JWT 검증. Access와 같은 서명 키 사용 |
| `INTERNAL_CALLBACK_TOKEN` | 서비스 사이 내부 호출 인증 |
| `AGENT_INTERNAL_TOKEN` | Skill gateway의 내부 호출 인증 |
| `POSTGRES_HOST`, `POSTGRES_PORT`, `CORE_DB_NAME` | 문서 DB 연결 |
| `REDIS_HOST`, `REDIS_PORT` | Redis 연결 |
| `S3_ENDPOINT`, `S3_BUCKET` | 파일 저장소 연결 |
| `KAFKA_BOOTSTRAP_SERVERS` | 비동기 AI 작업 메시지 연결 |

DB의 런타임·마이그레이션 계정, 저장소 자격 증명과 AI endpoint도 실행 환경에 맞춰 설정해야 합니다. 전체 설정은 [빌드·실행 문서](docs/script.md)를 참고하세요.

### 2. 서비스 시작

```bash
./gradlew bootRun
```

`bootRun`은 기본적으로 `local` 프로필과 루트의 `.env`를 사용합니다. 다른 설정 파일은 다음처럼 지정할 수 있습니다.

```bash
SERVICE_ENV_FILE=/절대/경로/.env ./gradlew bootRun
```

배포 컨테이너에는 환경변수로 설정을 주입합니다.

## 테스트와 빌드

```bash
./gradlew test bootJar
```

통합 테스트는 Testcontainers를 사용합니다. Gradle 테스트는 `test` 프로필로 실행하며 실제 개발 서비스 대신 격리된 주소와 테스트용 서버·컨테이너를 사용합니다. IDE에서도 Gradle test runner를 사용하세요. GitHub CI도 테스트와 JAR 빌드를 실행합니다.

Docker 이미지만 빌드하려면 다음 명령을 사용합니다.

```bash
docker build -t fruition-document-svc:local .
```

## API와 개발 문서

[OpenAPI 계약](api-specs/openapi.yaml)은 테스트에서 실행 코드와 비교합니다. API를 의도적으로 변경한 경우에만 다음 명령으로 스냅샷을 갱신하세요.

```bash
./gradlew test -DupdateOpenApiSnapshot=true
```

| 문서 | 살펴볼 내용 |
| --- | --- |
| [문서 안내](docs/README.md) | Document 문서 전체 목록 |
| [서비스 구조](docs/architecture.md) | 문서·AI·회의 처리와 데이터 책임 |
| [데이터 모델](docs/data-model.md) | Document가 소유하는 데이터 |
| [빌드·실행](docs/script.md) | 연결 설정, 테스트, 마이그레이션 |
| [API 안내](docs/api/README.md) | HTTP API 사용법 |
| [플랫폼 문서](https://github.com/FruitionKR/Fruition-flatform/blob/main/docs/README.md) | 서비스 간 통신, 통합 실행, 배포 |

## 저작권 및 라이선스 / Copyright and License

**한국어**

저작권 (c) 2026 Fruition 팀. 모든 권리 보유.

Fruition 팀이 저작권을 보유하는 코드·문서·자산의 무단 사용을 금지합니다. 상업적·비상업적 목적의 사용·복제·수정·배포·재라이선스·판매에는 Fruition 팀의 사전 서면 허가가 필요합니다. 제3자 구성요소에는 각 라이선스가 적용됩니다. 적용 범위와 예외는 [LICENSE](LICENSE)를 참고하세요.

**English**

Copyright (c) 2026 Team Fruition. All rights reserved.

Unauthorized use of code, documentation, and assets copyrighted by Team Fruition is prohibited. Use, copying, modification, distribution, sublicensing, or sale for commercial or non-commercial purposes requires prior written permission from Team Fruition. Third-party components remain subject to their own licenses. See [LICENSE](LICENSE) for the scope and exceptions.
