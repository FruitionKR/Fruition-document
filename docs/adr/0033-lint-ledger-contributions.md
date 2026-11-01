# ADR-0033: lint 기록을 기여 원장에 남긴다

- 상태: 채택 (2026-11-01)
- 관련: ai [ADR 0042 하위 항목 개념 페이지](https://github.com/FruitionKR/Fruition-ai/blob/main/docs/adr/0042-hierarchical-concept-pages.md) 3단계

## 맥락

`wiki_page_contributions`는 ingest와 사람 수정 기여만 담았다. lint 복원은 lint가 만든 페이지를 지우고 바꾼 페이지를 남은 ingest 기여로 다시 만들어 동작했다. 하지만 나중에 ingest 복원이 lint가 바꾼 페이지를 다시 만들면 lint 변경이 재생되지 않았다. 병합한 근거가 사라지고, ADR 0042 분리 뒤에는 옮긴 항목이 부모에 다시 나타나며 자식 페이지는 원장 행이 없어 되돌린 문서의 근거를 그대로 가졌다.

ai는 lint 결과에 `ledger_contributions`(`page_id`·`operation_id`·`document_id`·`contribution_key`·`linked_by_operation_id`, 재생 순서)와 `deleted_pages`(다시 합치기로 지운 자식)를 싣는다. 행은 lint 자기 기록과, 원래 편입 작업 id로 다른 페이지에 이은 편입 기록 두 종류다.

## 결정

- **행 기록.** lint 결과 반영(`LintOperationApplier`)이 `ledger_contributions`를 받은 순서대로 원장에 더한다. 자기 기록은 `ingest_operation_id`=lint id, `source_document_id`=NULL이다. 이은 기록은 원래 작업 id와 문서이며 같은 워크스페이스 작업이어야 한다. 두 종류 모두 새 컬럼 `lint_operation_id`(V84)에 그 lint를 적는다. 자기 기록은 `ingest_operation_id = lint_operation_id`로 구분한다.
- **순서 값.** 한 페이지에 행이 여러 개라 행마다 다른 `sequence_revision`이 필요하다. 그 페이지 최대 revision 다음부터 행마다 하나씩 쓰고, lint가 만드는 새 버전은 마지막 값에 둔다. 다음 편입의 revision이 항상 뒤에 오도록 원장 행은 lint가 새 revision을 남기는 페이지(`changed_pages`)에만 받는다. 그 사이 revision 번호에는 버전이 없다.
- **멱등.** 페이지에 이 lint가 만든 버전이 이미 있으면 재전송으로 보고 건너뛴다. 같은 (페이지, 작업) 행이 켜져 있으면 그대로 두고, 꺼져 있으면(예: 다시 합치기를 lint 복원한 뒤 다시 합치기) 그 행을 이 lint로 다시 잇는다. 기록이 페이지·작업마다 하나라 새 행을 만들 수 없다.
- **다시 만든 페이지.** 예전 버전이 있어도 그 페이지의 마지막 변경(`ai_operation_changes`)이 `deleted`이면 lint가 지워진 페이지를 다시 만든 것으로 보고 `created`로 남긴다(변경 이력 표시용). 기여 수로는 가르지 않는다. 문서를 휴지통으로 옮기면 기여만 꺼지고 개념 페이지는 남기 때문이다.
- **합쳐 지운 페이지.** `deleted_pages`의 기존 기여는 끄지 않는다. 삭제는 그 페이지에 남은 이 lint 기록을 재생한 결과다(ai ADR 0042 최종안 결정 2). 끄면 그 사이 취소한 편입과 합치기가 끈 기여를 구분할 수 없어, lint 복원이 취소한 근거를 되살린다. `deleted` 변경은 남긴다.
- **페이지 삭제는 ai 재생이 정한다.** 복원 계획은 남은 활성 행이 하나라도 있는 페이지를 `rebuild_pages`에 넣고, 활성 행이 하나도 없을 때만 `deleted_pages`에 넣는다. lint 기록만 남았다는 이유로 지우지 않는다. 클러스터 승격 페이지는 lint 기록이 근거라 지우면 안 된다. 재생 결과 남은 유효 근거가 없거나 삭제 기록에서 끝나면 ai가 결과의 `deleted_pages`로 알리고 document-svc는 삭제로 기록한다.
- **되돌리기 = 대상 작업의 효과만 빼기**(결정 3).
  - ingest 복원: 남은 활성 행에 lint 행이 순서대로 들어간다. 이은 기록은 원래 작업 id라 그 작업을 제외하면 자식 페이지에서도 빠진다.
  - lint 복원: 그 lint의 `lint_operation_id` 행(자기 기록·이은 기록)만 끈다. 다시 켜는 행은 없다. 대상 lint가 바꾼 모든 페이지를 남은 활성 기여로 다시 만들고, 남은 활성 행이 없으면 삭제한다. 대상이 만든 페이지라도 뒤 기여가 남으면 재조립에 맡긴다. 합치기를 되돌리면 지운 자식은 남은 기여로 다시 만들어진다.
  - 충돌: 대상 lint 뒤에 되돌리지 않은 다른 lint가 같은 페이지를 바꿨으면 쓰기 전에 거절한다(뒤 lint부터 되돌린다). 뒤 lint의 구조 변경이 대상 lint에 기대고 있을 수 있기 때문이다. 같은 항목이 두 페이지에 재생되는 것 같은 재생 충돌은 ai가 쓰기 전에 실패 코드 `restore_conflict`로 돌려준다. document-svc는 원장을 바꾸지 않고 복원을 실패로 표시하며 요약은 "뒤에 실행한 작업과 충돌해 되돌리지 않았습니다. 뒤 작업부터 되돌려 주세요."다.
  - 복원 결과 `changed_pages`에 `rebuild_pages` 밖의 페이지가 올 수 있다. ai가 사라진 하위 항목을 가리키던 참조를 정리해 다시 그린 살아 있는 페이지다(본문 key는 `ops/{복원 작업}.md`). 원장은 건드리지 않고 직전 버전의 기여 수로 새 버전과 `rebuilt` 변경을 남긴다.
- **재편입 대체.** 같은 문서를 다시 편입한 결과가 성공으로 기록되면, 그 문서의 이전 편입 행(같은 `source_document_id`, 먼저 시작한 작업, lint가 다른 페이지에 이은 사본 포함)을 `deactivated_by`=새 편입으로 끈다. 새 편입이 더는 다루지 않는 페이지의 행도 끈다. 그래야 빠진 개념의 이전 기여가 재생에서 살아나지 않는다. 일부만 반영한 결과는 대체하지 않는다. 이 결과 반영 트랜잭션에서, 대체해 끈 행이 있는 개념 페이지를 ai에 다시 만들게 한다. ai는 편입 때 원장의 활성 기여를 모르므로 추측하지 않는다. 재편입이 다시 쓴 페이지도 대상이다. ai는 그 페이지를 현재 상태 위에 새 편입을 더해 쓰므로 재편입이 더는 뽑지 않은 이전 편입의 하위 항목·근거가 남는다(2026-11-02 실험에서 다시 편입한 파드 페이지에 이전 편입의 하위 항목이 남았다). 소스 페이지는 재편입이 문서 전체로 다시 쓰므로 뺀다. 대상 작업이 없는 `restore` 작업 로그(`restored_from` NULL)와 Kafka `kind: restore_rebuild` 명령을 만든다. 형식은 복구 명령과 같고 `target_operation_id`=재편입, `rebuild_pages`=남은 활성 행(적용 순서), 활성 행이 없는 페이지는 `deleted_pages`, `cancel_operation_ids`·`restore_to_operation_id`·`source_page`는 null이다. 결과는 복구 결과로 받아 새 버전과 `rebuilt`·`deleted` 변경으로 기록하고 원장은 바꾸지 않는다(제외 작업 없음). 새 작업 유형을 두지 않고 복구 경로를 그대로 쓴다. 그 재편입을 되돌리면 `deactivated_by`가 제외 대상인 행만 다시 켜고, 그 행이 있는 페이지도 복원 대상에 넣어 남은 기여로 다시 만든다. 문서를 휴지통으로 옮기면 이렇게 꺼진 행의 `deactivated_by`도 비워, 재편입 복원이 휴지통 문서의 기여를 켜지 않게 한다.
  - 복원 결과 source 페이지의 `snapshot_operation_id`는 ai가 수동 링크 재생에 쓰는 값이라 document-svc는 저장하지 않는다. lint 기록의 `added_links[].requires_operation_id`도 document-svc가 읽지 않는다.

## 대안과 기각 사유

- **`document_id` NULL만으로 lint 기록 구분**: 사람 수정 기여도 NULL이라 구분되지 않는다. 이은 기록은 문서가 있어 lint 복원이 찾을 수 없다. 기각.
- **같은 `sequence_revision` + 별도 순서 컬럼**: 정렬 기준을 모든 판정 코드와 ai 서명(`expected_contributions`)에 함께 바꿔야 한다. revision을 행 수만큼 넘기는 쪽이 기존 정렬을 그대로 쓴다. 기각.
- **합치기 때 자식 기여를 `deactivated_by`=lint로 끄고 lint 복원이 다시 켜기**: 처음 구현했던 방식이다. 합친 뒤 원래 편입을 취소하면 그 편입 행은 이미 꺼져 있어 취소가 기록되지 않고, lint 복원이 그 행을 다시 켜 취소한 근거가 살아난다. 기각.
- **대상 lint가 만든 페이지는 복원 때 무조건 삭제**: 분리 뒤 자식에 들어온 독립 편입까지 지운다. 기각.
- **lint 결과마다 이은 기록을 새 작업 id로 저장**: ai 재생은 원래 작업 id로 기록을 묶어 그 편입을 되돌리면 자식에서도 빠지게 한다(ADR 0042). 기각.

## 결과

- 편입 복원·lint 복원이 lint 변경을 재생하고, 분리·다시 합치기를 되돌릴 수 있다. 취소한 편입은 합치기와 그 복원을 거쳐도 살아나지 않는다.
- 뒤 lint가 같은 페이지를 바꿨으면 대상 lint를 바로 되돌릴 수 없다. 뒤 lint부터 되돌려야 한다.
- `wiki_page_versions` revision에 빈 번호가 생긴다. 번호 연속을 가정하는 코드는 없다.
- 이 변경 전 lint는 원장 행이 없다. 개발 데이터는 초기화하므로 이전 데이터 보정은 하지 않는다.
