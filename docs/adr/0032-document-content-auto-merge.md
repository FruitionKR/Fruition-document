# ADR-0032: 본문 저장 자동 병합과 편집 충돌 기록 폐지

- 상태: 채택 (2026-10-19 사용자 결정 a안)
- 대체: V63 편집 충돌(`document_edit_conflicts`)의 "클라이언트 등록 → OWNER 수동 해결" 흐름

## 맥락

본문 저장(`PUT .../content`)은 `base_revision`이 서버 현재 revision과 다르면 409 `DOCUMENT_VERSION_CONFLICT`를 냈다. 클라이언트는 자기 본문을 충돌로 등록하고 워크스페이스 OWNER가 서버 본·충돌 본·직접 합친 본 중 하나를 골랐다.

409는 공동 편집만이 아니라 같은 사용자의 여러 탭, 만료된 편집 잠금 뒤 저장, 데스크톱 동기화, Agent·회의록 적용 저장에서도 났다. 대부분은 서로 다른 곳을 고친 저장이었는데도 편집이 막히고 OWNER가 처리해야 했다. 모든 저장은 이미 전체 본문을 버전 스냅샷(`document_content_versions`)으로 남기고, 버전 조회·복원 API가 있다.

## 결정

- **서버 한 곳에서 자동 병합한다.** `DocumentService.saveContentInTransaction`이 `PostgresDocumentEditStore.save`에 병합 resolver를 넘긴다. store는 문서 행 잠금 아래에서 현재 상태를 읽고, revision이 다르면 resolver가 정한 본문을 현재 revision 다음 revision으로 저장한다. 사용자 저장·이미지 포함 저장·Agent 적용(`apply_operation_id`)·Agent 도구 적용·회의록 덧붙이기가 모두 이 경로를 탄다.
- **줄 단위 3-way merge(diff3).** 기준 본은 `base_revision`의 버전 스냅샷, 상대 본은 서버 현재 본, 내 본은 요청 본이다. 두 쪽 diff(java-diff-utils, 선형 공간 Myers)를 기준 본 위치로 맞춰, 같은 줄을 건드리거나 같은 위치에 끼워 넣은 변경을 겹침으로 본다. 바로 이웃한 줄을 각각 바꾼 것은 겹치지 않는다. 겹쳐도 두 쪽 결과가 같으면 합친다.
- **겹치면 last-writer-wins.** 요청 본(나중에 저장한 쪽)을 그대로 저장한다. 덮어쓴 서버 본은 저장 경로가 버전 이력에 남기므로(직전 revision 스냅샷) 응답의 `merge.overwritten_version`으로 알려 주고, 버전 복원 API로 되살린다. 부분 병합(겹친 구간만 내 본)은 어느 쪽도 쓰지 않은 본문을 만들어 택하지 않았다.
- **기준 스냅샷이 없으면 last-writer-wins.** `base_revision`이 현재보다 크면 병합 근거가 없어 409를 유지한다.
- **응답.** 기존 200 응답에 `merge: {status, base_version, server_version, overwritten_version}`을 더한다. `status`는 `none`/`merged`/`overwritten`이다. `merged`·`overwritten`이면 `markdown`에 저장된 최종 본문을 담는다. `none`이면 `status`만 오고 `markdown`은 생략해 기존 클라이언트와 같다. 같은 `revision_write_id` 재전송은 요청 본 해시로 멱등을 판정하므로 처음 결과(병합본이면 `merged`와 그 본문)를 돌려준다.
- **버전 메타.** 병합·덮어쓰기로 만든 버전에 `merge_status`(`merged`/`overwritten`)를 남긴다. `source`는 기존 규칙(`user`/`agent`/`meeting_notes`/`restore`) 그대로다.
- **병합하지 않는 저장.** 버전 복원·AI 작업 되돌리기·충돌 해결처럼 정확히 그 본문이어야 하는 내부 저장(`saveContentInCurrentTransaction` 기본값)은 기존처럼 409로 거절한다.
- **사전 거절 제거.** Agent turn의 오래된 `baseVersion` 409와 회의록 `append`의 `DOCUMENT_REVISION_CHANGED` 409를 없앴다. 적용 저장이 병합하므로 미리 막을 이유가 없다.
- **편집 충돌 API는 deprecated.** 구 데스크톱 호환을 위해 `POST .../conflicts`는 남기되, 받은 본문을 곧바로 자동 병합 저장하고 해결된 행(`merged` 또는 `conflict`)으로 기록한다. OWNER 알림 `edit_conflict_registered`는 만들지 않는다. 목록·해결 API는 남은 기록을 위해 유지한다.
- **열린 충돌 정리(V80).** 열린 충돌의 본문을 그 문서 버전 이력에 `merge_status=conflict_archived` 버전(현재 revision 다음 번호, 등록 순)으로 옮기고, 현재 본문을 그 위 버전(`source=restore`, `restored_from_version`=원래 revision)으로 다시 남긴 뒤 편집 상태 revision을 맞춘다. 버전 번호와 편집 revision이 같은 수열이라 다음 저장과 겹치지 않게 하려는 것이다. 다른 서비스가 새 revision을 알도록 저장 event(outbox)도 남긴다. 충돌은 `resolution=server`, `resolved_by=system:auto-merge-migration`으로 닫는다. 본문은 하나도 지우지 않는다.
- **편집 잠금(423)은 그대로다.**

## 결과

- 오래된 기준으로 저장해도 편집이 막히지 않는다. 클라이언트는 `merge.status`가 `merged`·`overwritten`이면 편집기를 응답 본문과 `current_version`으로 맞춘다. `merge`를 읽지 않는 구 클라이언트도 다음 저장이 다시 병합되므로 막히지 않지만, 화면이 서버 본과 어긋난 채 남을 수 있다(구 데스크톱).
- 겹친 편집은 조용히 덮어써질 수 있다. 덮어쓴 본문은 항상 버전 이력에 남고 응답으로 알린다.
- 기준 스냅샷은 버전 이력에서 읽는다. 버전 이력을 정리하는 정책이 생기면 그만큼 last-writer-wins가 늘어난다.
- V80은 열린 충돌이 있는 문서의 revision을 올린다. 그 문서를 오래된 revision으로 연 클라이언트의 다음 저장은 자동 병합된다.
