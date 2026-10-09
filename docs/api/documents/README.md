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

| API | 권한 | 동작 |
|---|---|---|
| `PUT /api/workspaces/{workspace_id}/documents/{document_id}/permission` | 문서 소유자, OWNER | 본문 `{"access": "edit" \| "view" \| null}`. `null`이면 설정을 지운다. 응답 `200 {"access": ...}` |
| `PUT /api/workspaces/{workspace_id}/folders/{folder_id}/permission` | OWNER | 같은 형식. 폴더 안 문서와 하위 폴더가 따른다 |

오류: `400` access 값이 잘못됨, `403` 바꿀 권한 없음(`DOCUMENT_WRITE_FORBIDDEN`), `404` 문서·폴더 없음.
권한 설정이 바뀌면 문서 트리의 ETag도 바뀐다. 진입점: `src/main/java/fruition/core/document/controller/DocumentPermissionController.java`, 판정: `DocumentAccessPolicy.java`.

## 편집 충돌

공동 편집에서 두 사람이 같은 revision에서 고친 본문을 저장하면 나중 저장은 409 `DOCUMENT_VERSION_CONFLICT`를 받는다.
충돌은 자동으로 합치지 않고, 클라이언트가 자기 본문을 충돌로 등록하면 워크스페이스 OWNER 중 한 명이 고른다.

1. 본문 저장 409 응답의 `error.current_revision`에 서버 현재 revision이 온다([Content](content.md)의 `PUT .../content`).
2. 클라이언트는 자기 본문을 `POST .../conflicts`로 올려 보존한다.
3. OWNER는 `GET .../conflicts`에서 충돌 본과 서버 본을 비교하고 `POST .../conflicts/{conflict_id}/resolve`로 고른다.

| API | 권한 | 동작 |
|---|---|---|
| `POST /api/workspaces/{workspace_id}/documents/{document_id}/conflicts` | 그 문서 편집 권한 | 본문 `{"markdown", "base_revision", "client_conflict_id"}`. `201`로 충돌(`id`, `document_id`, `base_revision`, `markdown`, `author_user_id`, `status`, `resolution`, `resolved_by`, `resolved_revision`, `created_at`, `resolved_at`)을 반환. 같은 `client_conflict_id` 재전송은 기존 충돌을 그대로 돌려준다 |
| `GET /api/workspaces/{workspace_id}/conflicts` | OWNER | 미해결 충돌을 오래된 순으로 `{"conflicts": [{"conflict": {...}, "document_name", "server": {"markdown", "revision", "updated_by", "updated_at"}}]}`. 휴지통 문서의 충돌은 뺀다 |
| `POST /api/workspaces/{workspace_id}/conflicts/{conflict_id}/resolve` | OWNER | 본문 `{"choice": "server" \| "conflict" \| "merged", "markdown", "base_revision"}`. `server`는 본문을 그대로 두고, `conflict`는 충돌 본을, `merged`는 `markdown`을 새 revision으로 저장한다. `conflict`·`merged`는 `base_revision`(목록의 `server.revision`)이 필요하고 `merged`는 `markdown`도 필요하다. `200`으로 해결된 충돌을 반환 |

- 해결은 충돌 행을 잠그고 처리해서 OWNER 여럿이 동시에 해결해도 먼저 한 요청만 반영된다. 이미 해결된 충돌은 409 `CONFLICT_ALREADY_RESOLVED`.
- 고르지 않은 본문도 남는다. 서버 본은 버전 이력에, 충돌 본은 해결된 충돌 기록(`document_edit_conflicts`)에 있다.
- OWNER는 문서 권한과 관계없이 저장할 수 있지만, 다른 사용자가 편집 잠금을 쥐고 있으면 `conflict`·`merged`는 423이고, 그사이 서버 revision이 바뀌었으면 409 `DOCUMENT_VERSION_CONFLICT`다.
- 오류: `400` 요청 값이 잘못됨, `403` 편집 권한 없음·OWNER 아님(`DOCUMENT_WRITE_FORBIDDEN`), `404` 문서·충돌 없음(`EDIT_CONFLICT_NOT_FOUND`).
- 충돌 생성·해결 알림은 아직 없다(이후 작업). 그때까지 OWNER는 목록 API로 확인한다.

진입점: `src/main/java/fruition/core/document/controller/DocumentEditConflictController.java`, 처리: `DocumentEditConflictService.java`.
