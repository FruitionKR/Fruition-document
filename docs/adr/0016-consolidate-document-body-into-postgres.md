# ADR-0016: 문서 본문·편집 저장소를 PostgreSQL로 통합

- 상태: 적용됨
- 관련: [ADR-0001](https://github.com/FruitionKR/Fruition-flatform/blob/main/docs/adr/0001-choose-primary-database.md), [ADR-0003](https://github.com/FruitionKR/Fruition-flatform/blob/main/docs/adr/0003-choose-event-processing-strategy.md), V39 `consolidate_document_edit_storage`
- 대체: ADR-0001의 MongoDB 문서 본문 저장 결정과 ADR-0003의 MongoDB 편집 outbox 결정

## 맥락

문서 서비스는 MongoDB의 `document_edit_states`, `document_edit_writes`,
`document_edit_outbox`에 최신 Markdown, 편집 revision, `revision_write_id` 처리 결과와
편집 이벤트를 저장해 왔다. 반면 문서 메타데이터, 콘텐츠 버전, 자산 참조와 AI 작업 감사
기록은 `core_db` PostgreSQL에 있었다. 따라서 MongoDB 저장 성공과 PostgreSQL 저장·감사·이벤트
기록을 하나의 commit 경계로 묶을 수 없었고, 한 저장 요청에서 부분 commit이 발생할 수 있었다.

이번 cutover에서는 기존 MongoDB 편집 데이터와 PostgreSQL `document_edit_states`,
`document_content_versions`를 import하지 않고 새 PostgreSQL 편집 상태로 시작한다. 이는 해당
MongoDB collection과 PostgreSQL table을 폐기해도 된다는 명시적 제품 결정 및 대상별 승인에 따른다.
MongoDB는 cutover 전후 대조를 위한 비교 oracle로만 취급하며 장래에 삭제할 수 있지만, 특정 MongoDB
database·volume이나 PostgreSQL table의 실제 삭제는 이 ADR의 범위가 아니다.

## 결정

### 1. 저장소 소유권과 revision 의미

`core_db`는 document-svc가 소유하며 다음 문서 편집 관련 데이터를 PostgreSQL에 저장한다.

- `documents`: 문서 생명주기 메타데이터. `current_version`은 이동·삭제·복원 등을 포함한
  생명주기 메타데이터이며 편집 revision으로 사용하지 않는다. 현재 편집본의 hash projection은
  `current_content_hash`에 둔다.
- `document_edit_states`: 최신 Markdown, `content_hash`, `revision`, 생성·갱신 시각.
  편집 동시성, HTTP 응답의 편집 version, 이벤트 순서 비교의 기준은 이 테이블의 `revision`이다.
- `document_edit_writes`: `(document_id, revision_write_id)` primary key의 write receipt.
  `request_hash`, 결과 revision/hash/시각, actor와 `changed`를 저장해 같은 요청을 replay한다.
- `document_content_versions`: 실제 변경된 편집 본문의 전후 snapshot과 `operation_id` 연결.
- `document_assets`, `document_asset_references`: 저장 요청의 자산 row와 문서 본문 내 자산 참조.
- `agent_apply_projections`, `ai_operation_logs`, `ai_operation_changes`: 유효한 Agent 적용
  표의 소비와 적용 감사 기록. `ai_operation_logs.document_restore_blocked`는 fresh cutover
  이전 `document_edit` 감사 행의 복구를 막는 값이다.
- `document_edit_outbox`: 편집 이벤트 전용 outbox. 기존 `ai_command_outbox`와 합치지 않는다.

### 2. PostgreSQL schema와 fresh cutover

V39는 다음을 적용한다.

- `document_edit_states.revision bigint NOT NULL`을 추가하고 양수 제약을 둔다.
- `document_edit_writes`에 `(document_id, revision_write_id)` primary key와 요청 hash 및 결과
  receipt 열을 만든다.
- `document_edit_outbox`에 `event_id`, `document_id`, `workspace_id`, `revision`,
  `content_hash`, `event_type`, `schema_version`, `created_at`, `published`, `published_at`을
  만들고 `(created_at, event_id)` pending index를 둔다.
- `ai_operation_logs.document_restore_blocked boolean NOT NULL DEFAULT false`를 추가하고,
  migration 당시 존재하는 `document_edit` 행만 `true`로 표시한다. 새 작업과 `ingest`·`lint`
  감사 행은 `false`로 유지해 Wiki 복구와 감사 로그 조회를 보존한다.
- migration은 `document_edit_states`와 `document_content_versions` 중 하나라도 비어 있지 않으면 중단한다.

따라서 cutover는 기존 Mongo state·write receipt·outbox를 PostgreSQL로 이관하는 절차가 아니라,
기존 Mongo 편집 데이터와 PostgreSQL `document_edit_states`·`document_content_versions`를 폐기한
fresh PostgreSQL 상태에서 시작하는 절차다. 따라서 migration 당시 남아 있는 `document_edit`
감사 행은 보존하되 revision 세대가 달라 복구하지 않으며, preview와 execute가 공통 validator로
이를 차단한다. 새 작업은 `false`로 기록되고 Wiki `ingest`·`lint` 복구는 계속 허용한다. runtime
fallback, dual write, 호환 계층은 두지 않는다. MongoDB 참조 제거와 운영 리소스의 실제 삭제는 이
결정의 후속 운영 범위이며, 이 ADR은 특정 database·volume 삭제 명령을 정의하지 않는다.

### 3. 하나의 PostgreSQL transaction으로 편집 단위를 기록

`DocumentService`의 기존 `TransactionTemplate`을 사용해 한 `core_db` PostgreSQL transaction에서
다음을 함께 commit하거나 rollback한다.

1. 문서 소유권·편집 잠금·입력 검증과 Agent 적용 표 검증/소비
2. `document_edit_states`의 Markdown, hash, `revision` 조건부 갱신
3. `document_edit_writes` write receipt 기록
4. 변경 전후 `document_content_versions` snapshot과 `documents.current_content_hash` projection
5. `document_assets`, `document_asset_references` row와 참조 동기화
6. 유효한 Agent 적용의 `ai_operation_logs`·`ai_operation_changes` 감사 기록
7. 실제 변경일 때만 `document_edit_outbox` row 기록

`document_edit_states`를 읽은 뒤 `WHERE document_id = ? AND revision = ?` 조건으로 갱신하고
갱신 행 수를 CAS 결과로 사용한다. 무변경 저장도 `revision`과 `content_hash` 조건을 검사하는
no-op update를 수행한다. 같은 `revision_write_id`에 같은 `request_hash`가 오면 저장 결과를
`replayed=true`로 반환하고 version·asset·감사·outbox를 다시 쓰지 않는다. 다른 요청 hash면
idempotency conflict다. 실제 변경이 없으면 receipt만 남기고 outbox는 만들지 않는다.

CAS conflict는 본문·receipt·version·asset·outbox를 남기지 않는다. Agent 적용의 유효한 성공
기록은 본문 저장 transaction에 포함하며, CAS conflict처럼 본문 변경이 없는 시도의 conflict
감사만 별도 PostgreSQL transaction으로 기록할 수 있다.

S3/MinIO object 업로드는 DB transaction 밖에 있다. 업로드 후 DB transaction이 실패하거나
본문이 무변경이면 호출자가 새 object를 정리하고, 성공한 변경만 asset row와 reference를
commit한다. DB transaction에 object storage를 억지로 포함하지 않는다.

### 4. 재시도 범위

저장 전체 단위를 제한된 횟수로 재시도한다. 재시도 대상은 unique 충돌, deadlock, serialization
failure뿐이다. CAS conflict와 idempotency conflict는 재시도하지 않고 즉시 기존 HTTP 오류
계약으로 반환한다.

### 5. Outbox 발행과 이벤트 계약

`PostgresDocumentEditOutboxPublisher`는 `published = false`인 row를 `created_at, event_id` 순서로
최대 100개 읽는다. Kafka topic은 기존 `document.edit.event`, key는 `document_id`를 유지한다.
payload의 event type `document.edit.saved.v1`, schema version 1과 JSON 필드도 유지한다.

Kafka 전송이 성공한 뒤에만 `published = true`와 `published_at`을 갱신한다. 전송 또는 marking이
실패하면 해당 row를 pending으로 남기고 현재 발행 주기를 중단한다. 전송 후 marking 전에
프로세스가 종료되면 중복 발행될 수 있으므로 발행은 at-least-once로 유지한다. AI consumer는
더 큰 edit revision만 반영해 중복과 역순 이벤트를 흡수한다.

이벤트 JSON 필드는 `event_id`, `event_type`, `schema_version`, `document_id`, `workspace_id`,
`revision`, `content_hash`, `created_at`으로 기존 계약을 그대로 유지한다.

현재 document service와 edit-event consumer가 각각 1 replica인 운영 전제를 유지한다. 다중
publisher를 위한 claim/lease나 `FOR UPDATE SKIP LOCKED`는 이번 결정에 추가하지 않는다.

## 대안과 기각 사유

- **MongoDB 유지 또는 PostgreSQL과 dual write/fallback**: MongoDB와 PostgreSQL 사이의 원자
  commit 문제를 해결하지 못하고, 전환 경로의 복잡성과 불일치를 남긴다. fresh PostgreSQL
  cutover와 단일 write 경계를 채택하므로 기각한다.
- **기존 MongoDB 데이터 import**: 제품이 기존 편집 데이터 폐기를 선택했다. 데이터 보존을
  위한 offline import와 이중 읽기는 범위에 넣지 않는다.
- **`documents.current_version`을 edit revision으로 재사용**: 문서 이동·삭제·복원에도 증가하는
  생명주기 값이므로 편집 CAS와 이벤트 순서를 나타낼 수 없다. 전용 `document_edit_states.revision`을
  둔다.
- **기존 `ai_command_outbox`를 범용 edit outbox로 변경**: 서로 다른 topic의 장애가 서로를
  막고 변경 범위가 커진다. 편집 이벤트 전용 `document_edit_outbox`를 유지한다.
- **다중 publisher claim/lease와 outbox tuning을 선제 도입**: 현재 1 replica 운영에 필요하지
  않다. 실제 scale-out 요구가 생길 때 별도 결정으로 다룬다.

## 결과

- 문서 Markdown, edit revision, write receipt, 콘텐츠 version, 자산 row·reference, 적용 감사와
  `document_edit_outbox`가 하나의 `core_db` PostgreSQL transaction 경계를 공유한다.
- HTTP 응답과 `document.edit.event` topic·key·JSON schema는 바뀌지 않는다. 발행은 계속
  at-least-once이며 AI consumer의 revision 비교가 중복을 흡수한다.
- 새 배포는 MongoDB 편집 상태와 기존 PostgreSQL `document_edit_states`·`document_content_versions`를
  import하지 않으므로 기존 편집 이력·receipt·pending outbox는 보존되지 않는다. 기존 PostgreSQL
  `ai_operation_logs` 감사 행은 보존하지만 migration 이전 `document_edit` 복구는 차단한다.
  MongoDB는 비교 oracle일 뿐 runtime dependency가 아니다.
- object storage와 PostgreSQL은 서로 다른 시스템이므로 실패·무변경 object cleanup 책임이
  남는다. outbox 중복, 현재 1 replica 전제와 그 운영 비용도 수용한다.
- receipt TTL, content version 보존 기간, TOAST/fillfactor/autovacuum 조정과 다중 publisher
  tuning은 측정된 운영 문제가 생길 때 별도 ADR로 결정한다.
