# Documents API

[서비스 문서](../../README.md) / [document-svc](../README.md)

문서 API를 기본 관리, 본문·편집, 삭제·버전 이력으로 나눈다.

채팅 Wiki page화로 만들어진 `chat_export` 문서는 목록·상세에 확인용으로 노출하되 읽기 전용이다
(`editable: false`). 자세한 이유와 거절 규칙은 [Content](content.md)를 본다.

| 구분 | API 수 | 역할 |
|---|---:|---|
| [Management](management.md) | 12 | 목록·생성·업로드(직접 업로드 포함)·조회·복제·이동·이름 변경 |
| [Content](content.md) | 10 | 본문·block·asset·원본·원본 주소·내보내기·편집 잠금 |
| [History](history.md) | 7 | 삭제·휴지통·복구·diff·버전 이력 |

## 공동 편집 권한

워크스페이스 멤버는 기본으로 **모든 문서를 편집**할 수 있다(노션 방식). 문서·폴더별로 `view`(보기만) 또는 `edit`로 덮어쓴다.

- 문서에 적용되는 권한은 문서 자기 설정 → 가장 가까운 상위 폴더 설정 → 기본값(`edit`) 순으로 정한다.
- **문서 소유자(업로드한 사람)와 워크스페이스 OWNER는 설정과 관계없이 항상 편집**할 수 있다.
- 편집 권한이 필요한 동작: 본문 저장, 이미지 첨부 저장, 이름 변경, 이동, 버전 복원, 편집 잠금, 위키 편입(ingest), AI 편집 적용, 회의록 저장.
  `view` 폴더는 OWNER 외에 이름 변경·이동할 수 없다.
- 읽기만 필요한 동작(상세·본문·버전 목록·diff 조회, 복제)은 멤버면 할 수 있다. 복제본은 복제한 사람 소유다.
- **삭제와 휴지통 복구는 문서 소유자와 OWNER만** 한다.
- 목록·트리·상세 응답의 `can_edit`, `can_delete`로 화면이 읽기 전용 여부를 정하고, `permission`은 문서에 직접 건 설정(없으면 `null`), `updated_by`는 마지막으로 본문을 저장한 사용자다.

### AI 출처 표시

목록·트리·상세 응답은 문서가 AI 결과물인지를 함께 준다. 화면은 이 값으로 "AI가 생성한 내용" 라벨을 붙이고 AI 변경 이력으로 이동한다(Fruition-frontend#131).

| 필드 | 값 |
|---|---|
| `ai_origin` | 문서를 처음 만든 경로가 AI면 `agent`(Agent 문서 생성), `meeting_notes`(회의록 적용 `mode=create`), `chat_export`(채팅 답변 원문 저장). 복제본은 원본 값을 물려받는다. 사용자가 직접 만들거나 올린 문서는 `null` |
| `last_ai_change` | 본문 이력 중 가장 최근 AI 저장. 없으면 `null` |
| `last_ai_change.version` | AI가 저장한 본문 버전 |
| `last_ai_change.source` | `agent`(Agent 편집 적용) 또는 `meeting_notes`(회의록 덧붙이기 `mode=append`) |
| `last_ai_change.operation_id` | `GET /ai-operation-logs/{operation_id}`로 여는 AI 작업 ID. 작업 로그가 없는 회의록 적용이면 `null` |
| `last_ai_change.changed_at` | 저장 시각(ISO-8601 UTC) |

```json
{
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "ai_origin": null,
  "last_ai_change": {
    "version": 3,
    "source": "agent",
    "operation_id": "op_7c2e4b1a9d3f4e6c8b0a2d5f7e9c1b3a",
    "changed_at": "2026-10-15T02:11:09Z"
  }
}
```

- `last_ai_change`는 버전 이력에서 읽는다. AI 저장 뒤 사람이 고쳤거나 이전 버전으로 복원해도 그 AI 저장을 가리킨다.
- 이 필드를 추가하기 전(V78)에 만든 문서는 `ai_origin`이 `null`이다. 그 전 버전은 Agent 편집(`operation_id`가 있는 버전)만 `last_ai_change`로 잡히고 회의록 덧붙이기는 잡히지 않는다.

| API | 권한 | 동작 |
|---|---|---|
| `PUT /api/workspaces/{workspace_id}/documents/{document_id}/permission` | 문서 소유자, OWNER | 본문 `{"access": "edit" \| "view" \| null}`. `null`이면 설정을 지운다. 응답 `200 {"access": ...}` |
| `PUT /api/workspaces/{workspace_id}/folders/{folder_id}/permission` | OWNER | 같은 형식. 폴더 안 문서와 하위 폴더가 따른다 |

오류: `400` access 값이 잘못됨, `403` 바꿀 권한 없음(`DOCUMENT_WRITE_FORBIDDEN`), `404` 문서·폴더 없음.
권한 설정이 바뀌면 문서 트리의 ETag도 바뀐다. 진입점: `src/main/java/fruition/core/document/controller/DocumentPermissionController.java`, 판정: `DocumentAccessPolicy.java`.

## 편집 충돌 (deprecated)

본문 저장은 기준 revision이 지나가도 409를 내지 않고 서버가 자동 병합한다([Content의 자동 병합](content.md#자동-병합), [ADR 0032](../../adr/0032-document-content-auto-merge.md)).
그래서 클라이언트가 충돌을 등록하고 OWNER가 고르던 흐름은 더 쓰지 않는다. 아래 API는 구 데스크톱 호환과 자동 병합 도입 전 기록 조회를 위해 남긴다.

| API | 권한 | 동작 |
|---|---|---|
| `POST /api/workspaces/{workspace_id}/documents/{document_id}/conflicts` | 그 문서 편집 권한 | 본문 `{"markdown", "base_revision", "client_conflict_id"}`. 받은 본문을 본문 저장과 같은 자동 병합 경로로 곧바로 저장하고, 해결된 충돌(`status=resolved`, 병합되면 `resolution=merged`, 충돌 본 그대로 저장되면 `conflict`, `resolved_by`=작성자, `resolved_revision`=저장 후 revision)을 `201`로 반환한다. 같은 `client_conflict_id` 재전송은 기존 행을 그대로 돌려주고 다시 저장하지 않는다. 편집 가능한 Markdown 문서가 아니면 `400`, 다른 사용자가 편집 잠금을 쥐고 있으면 `423` |
| `GET /api/workspaces/{workspace_id}/conflicts` | OWNER | 미해결 충돌 목록. V80이 열린 충돌을 모두 닫아 보통 비어 있다 |
| `POST /api/workspaces/{workspace_id}/conflicts/{conflict_id}/resolve` | OWNER | 미해결 충돌을 고른다. 동작은 이전과 같다(`server`·`conflict`·`merged`, 이미 해결됐으면 409 `CONFLICT_ALREADY_RESOLVED`) |

- 충돌 등록은 OWNER에게 `edit_conflict_registered` 알림을 만들지 않는다. 해결 API를 쓰면 작성자에게 `edit_conflict_resolved` 알림이 생긴다.
- V80 마이그레이션은 열린 충돌의 본문을 그 문서의 버전 이력에 `merge_status=conflict_archived` 버전으로 옮기고(복원 가능), 현재 본문을 그 위 버전(`source=restore`)으로 다시 남긴 뒤 충돌을 `resolution=server`로 닫는다. 현재 본문은 바뀌지 않고 revision만 올라간다.

진입점: `src/main/java/fruition/core/document/controller/DocumentEditConflictController.java`, 처리: `DocumentEditConflictService.java`.
