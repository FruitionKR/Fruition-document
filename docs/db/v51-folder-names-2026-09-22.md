# 폴더별 문서·폴더 이름 계약 및 V51 배포

## 구현

`V51__scope_tree_names_to_parent.sql`은 V48의 workspace 전체 문서/폴더 개별 고유 인덱스를 제거하고 `document_tree_names`에 공유 namespace를 만든다. 키는 `(workspace_id, parent_folder_id, normalized_name)`이며 `UNIQUE NULLS NOT DISTINCT`로 루트 null도 단일 부모로 취급한다. 이름은 기존 앞뒤 공백 제거 규칙을 유지하고 NFC → 소문자 → NFC로 비교한다. 전체 파일명을 비교하므로 `Report.md`/`report.MD`는 충돌하고 `Report.pdf`는 허용한다. 다른 폴더의 같은 이름은 허용한다.

documents/folders의 INSERT, 이름/위치/삭제 상태 UPDATE, DELETE 트리거가 동일 트랜잭션에서 namespace를 갱신한다. 따라서 일반 업로드, multipart 완료, Markdown 생성, 이름 변경, 문서/폴더 이동, 휴지통 복구, 복제, 변환 placeholder 및 변환 배치 산출물에 같은 제약이 적용된다. 동시에 서로 다른 테이블에 쓰더라도 unique index가 중재한다. 충돌 시 기존 `409 DUPLICATE_NAME` 코드와 같은 폴더의 파일 또는 폴더가 존재한다는 메시지를 반환한다. 복제/채팅 문서 자동 이름 선택도 대상 폴더의 파일과 폴더만 조회한다. AI 처리 로직은 수정하지 않았다.

## frontend 계약

공통 prefix는 `/api/workspaces/{workspace_id}`다. 기존 endpoint를 그대로 사용한다.

| 요청 | payload / 응답 |
| --- | --- |
| GET `/document-tree` | `{items:[{type,id,name,sort_order,current_version,has_children,children?,document?}]}` 재귀 구조; 문서 name은 확장자 포함 filename |
| POST `/folders` | `{name,parent_folder_id?:UUID}` → 201 FolderResponse |
| PATCH `/folders/{id}` | `{name,base_version}` → FolderResponse |
| PATCH `/folders/{id}/position` | `{parent_folder_id:null 또는 UUID,position?:0-based,base_version}` → FolderResponse |
| PATCH `/documents/{id}/position` | `{folder_id:null 또는 UUID,position?:0-based,base_version}` → `{id,folder_id,sort_order,current_version}` |
| DELETE `/folders/{id}` / POST `/folders/{id}/restore` | `{base_version}` → 기존 lifecycle 응답 |
| PATCH `/documents/{id}/rename` | `{display_name,base_version}`; 기존 확장자 보존, Idempotency-Key는 필요 없음 |
| POST `/documents` | multipart form `file` 및 optional `folder_id`; 기존 지원 유지 |
| POST `/documents/uploads` | JSON `{filename,size,folder_id?:UUID}` → `{ticket,part_size,part_count}`; PDF multipart 시작, 기존 지원 유지 |
| POST `/documents/uploads/complete` | `{ticket}` 및 Idempotency-Key; ticket의 folder를 일반 업로드 저장 경로에 전달 |

폴더 쓰기와 문서 position, 일반 업로드 및 multipart 완료는 `Idempotency-Key`가 필요하다. FolderResponse는 `{id,parent_folder_id,name,sort_order,current_version,created_at,updated_at}`다. 문서 rename 응답은 `{id,filename,display_name,current_version,updated_at,changed}`다. position 생략 시 마지막에 배치한다. 위치 쓰기 후 최신 트리를 다시 읽어 버전/정렬을 갱신한다.

문서 업로드·목록·상세 응답과 트리 내 document 메타데이터에 `folder_id`를 추가했다. 루트는 키를 생략하지 않고 null이다. 기존 클라이언트에는 additive 변경이다. multipart 시작은 기존처럼 폴더를 ticket에 보관하며, 실제 폴더 권한/활성 여부 및 최종 이름 충돌은 완료 시 저장 트랜잭션에서 검사한다. 이름을 시작 시 예약하지 않는다.

## 배포 제약과 사전 조회

- PostgreSQL 15 이상, UTF-8 DB가 필요하다. 테스트는 PostgreSQL 16 및 기존 통합 테스트 이미지에서 수행한다.
- V48/V50을 수정하지 않았다. 새 애플리케이션의 이름 조회는 새 테이블을 사용하므로 V51 적용 후 실행한다.
- migration은 documents/folders에 쓰기 잠금을 잡고 활성 이름을 backfill한다. 데이터 양과 운영 트래픽에 따라 잠금 시간을 계획해야 한다.
- 기존 동일 부모의 파일·폴더 교차 이름 충돌이 있으면 migration 전체가 rollback된다. 사용자 데이터를 자동 rename/delete하지 않는다. 아래 사전 조회의 결과를 별도로 정리해야 한다.
- migration/runtime DB role을 분리한 배포에서는 `core_runtime`(또는 실제 runtime role)에 새 테이블의 SELECT/INSERT/UPDATE/DELETE 권한 및 함수 실행 권한이 있어야 한다. 기존 migration role의 default privileges가 새 객체에 적용되는지 확인한다. 이 테스트 환경은 단일 DB role이므로 분리된 운영 role 권한은 검증하지 않았다.
- 새 정책으로 다른 폴더에 같은 이름이 생긴 뒤에는 V48의 workspace 전체 unique index를 단순 복원할 수 없다. rollback 시 새 데이터를 먼저 검토해야 한다.

```sql
WITH names AS (
  SELECT workspace_id, folder_id AS parent_folder_id,
         normalize(lower(normalize(btrim(filename), NFC)), NFC) AS name,
         'document:' || id AS item
  FROM documents WHERE deleted_at IS NULL
  UNION ALL
  SELECT workspace_id, parent_folder_id,
         normalize(lower(normalize(btrim(name), NFC)), NFC), 'folder:' || id::text
  FROM folders WHERE deleted_at IS NULL
)
SELECT workspace_id, parent_folder_id, name, array_agg(item ORDER BY item) AS items
FROM names GROUP BY workspace_id, parent_folder_id, name HAVING count(*) > 1;
```

## 검증

2026-09-22 전체 Gradle suite **898 tests / 0 failures / 0 errors / 0 skipped**, 134 test suites, BUILD SUCCESSFUL (51초). OpenAPI 스냅샷은 실제 앱에서 재생성했으며 변경은 세 문서 DTO의 folder_id 추가뿐이다. `git diff --check`도 통과했다.

```sh
rtk proxy env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  DOCKER_HOST=unix:///Users/mireutale/.colima/default/docker.sock \
  TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
  ./gradlew test -DupdateOpenApiSnapshot=true --console=plain
```

- `UniqueResourceNamesTest`: 11회 실행, 부모별 허용, NFC/대소문자, 확장자 구분, 파일/폴더 교차 충돌, rename/move/restore/hard delete, 동시 생성 및 동시 이동 중 단일 성공 검증.
- `FolderServiceIntegrationTest`: 30 tests, 일반 업로드 교차 폴더 허용·중복 거절, 응답 folder_id, rename 충돌, 이동 실패 시 위치/정렬 rollback, 복제 자동 이름의 폴더 범위 및 폴더명 회피, 변환 placeholder 충돌 rollback 포함.
- `DocumentMultipartStorageIntegrationTest`: 실제 MinIO 65MiB multipart 왕복 및 완료 재시도에서 지정 folder_id가 저장 서비스에 전달됨을 검증.
- controller 테스트: Hibernate 제약 오류와 업로드 예외로 감싼 오류 모두 409 DUPLICATE_NAME 응답 검증.
- 초기 실행은 JDK 21 자동 탐지 및 Docker socket 설정 부재로 실패했다. 기존 설치 JDK 21/Colima socket을 위 환경변수로 지정해 해결했으며 서비스/프로세스를 임의 종료하지 않았다.

## 수정 파일

- `src/main/resources/db/migration/V51__scope_tree_names_to_parent.sql`
- `src/main/java/fruition/core/document/dto/DocumentUploadResponse.java`
- `src/main/java/fruition/core/document/dto/DocumentListResponse.java`
- `src/main/java/fruition/core/document/dto/DocumentDetailResponse.java`
- `src/main/java/fruition/core/document/repository/DocumentRepository.java`
- `src/main/java/fruition/core/document/service/DocumentService.java`
- `src/main/java/fruition/core/document/service/DocumentItemAssembler.java`
- `src/main/java/fruition/shared/util/DuplicateResourceName.java`
- `api-specs/openapi.yaml`
- `src/test/java/fruition/core/document/repository/UniqueResourceNamesTest.java`
- `src/test/java/fruition/core/document/service/FolderServiceIntegrationTest.java`
- `src/test/java/fruition/core/document/service/DocumentMultipartStorageIntegrationTest.java`
- `src/test/java/fruition/core/document/service/DocumentServiceBlocksTest.java`
- `src/test/java/fruition/core/document/service/DocumentServiceConvertTest.java`
- `src/test/java/fruition/core/document/controller/DocumentControllerTest.java`
- `src/test/java/fruition/core/document/controller/DocumentTreeControllerTest.java`
- `src/test/java/fruition/core/agent/service/AgentTurnServiceTest.java` (DTO 생성 fixture에 root null 인자만 추가)
- 이 보고서.

작업 브랜치 `fix/multipart-complete-xml`의 기존 clean 상태에서 수정했다. commit/push/PR/merge 및 운영 DB 변경은 수행하지 않았다.


## main 통합 전 로컬 실서버 검증

최신 main의 사용량 API를 통합한 기능 브랜치에서 `test bootJar`를 재실행하여 **899 tests / 0 failures / 0 errors / 0 skipped**를 확인했다.

기존 로컬 PostgreSQL 컨테이너는 없어져 버린 초기화 script bind mount 때문에 시작하지 못했다. 기존 볼륨과 데이터는 수정하지 않고 Colima의 별도 PostgreSQL 16 컨테이너 `fruition-v51-postgres-local`을 127.0.0.1:15432에 생성했다. 새 `core_db`에 Flyway V1~V50을 먼저 적용하고 위 사전 조회에서 충돌 0건을 확인한 뒤 V51을 적용했다. `flyway_schema_history`의 version=51, success=true와 분리된 `core_runtime`의 새 namespace 테이블 SELECT/INSERT/UPDATE/DELETE 권한을 확인했다. 기존 데이터가 있는 DB의 충돌 유무는 이번 검증에 포함되지 않는다.

별도 Orca shell terminal에서 document(127.0.0.1:18080, health 18082)와 access(18081, health 18083)를 실행했다. 실제 이메일 인증·가입·로그인·워크스페이스 생성 API로 로컬 계정을 준비했고 두 health 모두 200 UP이다. 계정과 토큰은 저장소 밖 권한 600 파일로만 보관한다.

- 폴더 HTTP 11건: 생성 201, 동일 부모 대소문자 중복 409/DUPLICATE_NAME, 다른 부모 동명 201, rename 200, move 200, rename 충돌 409, delete 200, 트리 재조회 200 및 이동/삭제 결과 확인.
- 문서 HTTP 8건: 부모를 지정한 Markdown 생성 201과 응답 folder_id, 파일·폴더 교차 이름 충돌 409, 다른 부모 동명 파일 생성 201, 목록 200의 folder_id, 충돌 이동 409 후 원래 위치 유지, 루트 이동 200 후 목록 folder_id=null 확인.
- 제약: 기존 AI 컨테이너들이 restart loop이며 localhost:8000이 없어 문서 상세 GET의 wiki context 호출이 연결 실패로 500을 반환했다. AI 코드나 기존 컨테이너 설정은 수정하지 않았다. 문서 상세의 실제 HTTP 성공과 AI 처리 흐름은 확인하지 못했다.
- 운영 및 원격 DB는 접근하거나 변경하지 않았다. 보호 규칙 우회와 force push는 사용하지 않는다.
