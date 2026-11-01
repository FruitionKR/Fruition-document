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
- **다시 만든 페이지.** 예전 버전이 있어도 lint 직전에 받치는 기여가 없던 페이지(지워진 페이지)를 lint가 바꾸면 `created`로 남긴다. 그래야 그 lint를 복원할 때 다시 지운다(예: lint 복원으로 지운 자식을 다음 lint가 다시 나눔).
- **합쳐 지운 페이지.** `deleted_pages`의 활성 기여를 `deactivated_by`=lint로 끄고 `deleted` 변경을 남긴다.
- **받치는 기여.** lint 자기 기록(개념, 항목 이동)만 남은 페이지는 받치는 기여가 없는 것으로 본다. ingest 복원이 그런 페이지를 삭제한다.
- **ingest 복원.** 남은 활성 행에 lint 행이 순서대로 들어간다. 이은 기록은 원래 작업 id라 그 작업을 제외하면 자식 페이지에서도 빠진다.
- **lint 복원.** 그 lint의 `lint_operation_id` 행을 끄고, 그 lint가 끈 행(`deactivated_by`=lint)을 다시 켠다. 만든 페이지는 삭제, 바꾼 페이지는 다시 만들고, 지운 페이지는 다시 켠 기여로 `rebuild_pages`에 넣어 되살린다.

## 대안과 기각 사유

- **`document_id` NULL만으로 lint 기록 구분**: 사람 수정 기여도 NULL이라 구분되지 않는다. 이은 기록은 문서가 있어 lint 복원이 찾을 수 없다. 기각.
- **같은 `sequence_revision` + 별도 순서 컬럼**: 정렬 기준을 모든 판정 코드와 ai 서명(`expected_contributions`)에 함께 바꿔야 한다. revision을 행 수만큼 넘기는 쪽이 기존 정렬을 그대로 쓴다. 기각.
- **lint 결과마다 이은 기록을 새 작업 id로 저장**: ai 재생은 원래 작업 id로 기록을 묶어 그 편입을 되돌리면 자식에서도 빠지게 한다(ADR 0042). 기각.

## 결과

- 편입 복원·lint 복원이 lint 변경을 재생하고, 분리·다시 합치기를 되돌릴 수 있다.
- `wiki_page_versions` revision에 빈 번호가 생긴다. 번호 연속을 가정하는 코드는 없다.
- 이 변경 전 lint는 원장 행이 없다. 개발 데이터는 초기화하므로 이전 데이터 보정은 하지 않는다.
