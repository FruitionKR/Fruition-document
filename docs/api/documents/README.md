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
