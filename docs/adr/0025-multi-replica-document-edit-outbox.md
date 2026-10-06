# ADR-0025: 다중 replica에서 문서 편집 outbox 발행

- 상태: 적용됨
- 관련: [ADR-0016](0016-consolidate-document-body-into-postgres.md) 5절 일부 대체, [#43](https://github.com/FruitionKR/Fruition-document/issues/43)

## 맥락

ADR-0016은 document service를 1 replica로 운영한다는 전제로, 문서 편집 outbox에 다중 publisher용 claim/lease나 `FOR UPDATE SKIP LOCKED`를 넣지 않았다. 지금 AWS에서는 document-svc가 2 replica(HPA 2~4)로 돈다.

`PostgresDocumentEditOutboxPublisher`는 1초마다 `published = false` 행을 잠금 없이 최대 100건 읽었다. 그래서 두 Pod가 같은 주기에 같은 행을 읽으면 둘 다 Kafka로 보냈다. `published = false` 조건은 표시 중복만 막고 전송 중복은 막지 못했다. 유실은 없었고 AI consumer가 revision 비교로 중복을 흡수했다. 하지만 메시지가 replica 수만큼 중복될 수 있었고, 정확성은 consumer 멱등성에만 기대고 있었다.

## 결정

1. `publishPending()`을 트랜잭션 하나로 실행한다. pending 조회에 `FOR UPDATE SKIP LOCKED`를 붙인다. 같은 저장소의 `AiCommandOutboxPublisher`와 같은 방식이다.
2. Kafka ACK 뒤 `published = true` UPDATE가 같은 트랜잭션에서 커밋될 때까지 행 잠금을 유지한다. 그동안 다른 Pod는 이 행을 건너뛰고 잠기지 않은 다음 행을 가져간다.
3. 첫 실패에서 주기를 끝내는 기존 동작은 유지한다. 예외를 잡고 반환하므로 트랜잭션은 커밋된다. 이미 보낸 행은 published로 남고, 남은 행은 잠금이 풀려 다음 주기에 다시 시도된다.
4. 인덱스는 V39의 부분 인덱스 `idx_document_edit_outbox_pending (created_at, event_id) WHERE published = false`를 그대로 쓴다. DB 변경은 없다.

## 순서와 중복 보장 범위

- Pod마다 다른 batch를 가져가므로 같은 문서의 rev N과 rev N+1이 서로 다른 Pod에서 나갈 수 있다. 그러면 Kafka 도착 순서가 뒤집힐 수 있다. 문서별 발행 순서는 보장하지 않는다.
- consumer는 더 큰 edit revision만 반영해야 한다(AI `edit_event_consumer`의 `last_edit_revision < EXCLUDED.last_edit_revision` 조건). 이 전제가 순서 역전과 남은 중복을 흡수한다. consumer를 새로 만들 때도 이 조건을 지켜야 한다.
- 발행은 계속 at-least-once다. 중복이 생길 수 있는 경우는 다음 두 가지다.
  - Kafka ACK 뒤 커밋 전에 프로세스가 끝나는 경우. 그 batch에서 이미 보낸 행이 모두 다시 나간다.
  - published 표시 UPDATE가 SQL 오류로 실패하는 경우. PostgreSQL 트랜잭션이 aborted 상태가 되어 커밋이 롤백으로 끝난다. 이 경우에도 같은 batch에서 이미 보낸 행까지 다시 나간다. 잠금이 없던 이전 구현은 실패한 행 하나만 다시 보냈다.

## 비용

한 batch 동안 트랜잭션과 DB 연결 하나를 잡는다. 최악의 경우 100건 × 전송 timeout 10초다. AI command outbox와 같은 수준이고 Pod당 연결은 하나다. 운영에서 연결 풀이 부족해지면 batch 크기를 줄인다.

## 대안과 기각 사유

- **단일 실행 보장(ShedLock 또는 `pg_try_advisory_xact_lock`)**: 한 번에 한 Pod만 발행하므로 전역 발행 순서가 지금처럼 보존된다. 하지만 consumer가 이미 revision 역행을 막으므로 순서 보존의 이득이 작다. ShedLock은 의존성이 추가되고, lock을 쥔 Pod가 멈추면 만료될 때까지 발행이 멈춘다. 이미 검증된 `AiCommandOutboxPublisher` 방식과 맞추는 쪽을 택했다.
- **claim/lease 컬럼**: 마이그레이션이 필요하다. 잠금을 짧게 잡는 이점은 지금 batch 크기에서는 필요하지 않다.

## 범위 밖

`AiTaskCancellationService#resume`도 1초마다 `cancel_requested`·`rolling_back` 행을 잠금 없이 읽는다. 그래서 여러 Pod가 같은 run을 진행할 수 있다. 하지만 `advance()`는 AI HTTP 호출 중에 Python 콜백(`changes`, `undo`, `finalize-edits`)을 받는다. 이 콜백들이 같은 `ai_task_runs` 행을 `FOR UPDATE`로 잠그므로, 행 잠금을 쥔 채 진행하면 교착된다. 그래서 별도 이슈([#48](https://github.com/FruitionKR/Fruition-document/issues/48))로 다룬다.
