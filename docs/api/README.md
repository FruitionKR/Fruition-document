# document-svc API

[서비스 문서](../README.md)

문서와 core DB를 소유하고 사용자용 AI·Wiki·Agent Gateway 요청을 중계한다. 로컬 base URL은
`http://localhost:8080`이다. 아래 `/api/**` 계약이 클라이언트가 사용하는 계약이며,
Backend가 ai-svc 내부 계약에 필요한 사용자·워크스페이스·모델 정보를 추가한다.

| 도메인 | API 수 | 역할 |
|---|---:|---|
| [작업 취소](tasks.md) | 5 | 공개 취소·상태와 Python의 업무 변경 역순 복구 |
| [AI](ai.md) | 9 | AI 모델 설정과 작업·변환·ingest 관리 |
| [Agent](agent.md) | 10 | Agent 실행·승인과 내부 Tool 호출 |
| [Chat](chat.md) | 7 | 채팅 세션·메시지와 Wiki 내보내기 |
| [Documents](documents/README.md) | 29 | 문서 관리·본문·편집·이력 |
| [Meetings](meetings.md) | 10 + WS 1 | 회의 생성·조회·삭제, 실시간 받아쓰기 WebSocket, 녹음 원본·파일 전사, 회의록 초안·저장 |
| [Navigation](navigation.md) | 10 | 폴더와 문서 트리 탐색 |
| [Notifications](notifications.md) | 3 | 앱 안 알림 목록과 읽음 처리 |
| [Query](query.md) | 4 | 동기·비동기 Query와 SSE |
| [Speech](speech.md) | 1 | 채팅 음성 입력(음성 → 텍스트, 저장 없음) |
| [Skills](skills.md) | 8 | Skill 작성·게시·설정과 참조 읽기 |
| [Wiki](wiki.md) | 8 | Wiki 조회·기여·유지보수 |
| [Wiki Schema](wiki-schema.md) | 5 | Wiki 스키마 초안·미리보기·활성화 |
| [Usage](agent.md#본인-모델-사용량-조회) | 1 | 본인 모델별 토큰 사용량 조회 |

기계 판독 원본은 `api-specs/openapi.yaml`이며 **path 98개(`/api/**` 87 + `/internal/**` 11), operation 110개**다.
위 표의 "API 수"는 각 도메인 문서가 다루는 API 수이고, 충돌할 경우 실행 코드와 생성된 OpenAPI를 우선한다.
표의 HTTP 합계는 107이고 위의 operation 108개와 1 차이가 난다. 96개 path 전부가 문서에 있음은 확인했으므로
누락된 API가 아니라 도메인별 집계 단위(같은 path의 여러 method를 1개로 세는지)가 섞인 결과다. 정확한 수는 OpenAPI를 본다.

## 호출 관계(배선) 요약

각 API의 상세 계약 `10. 구현 파일`에 `호출자` / `하위 호출` / `배선 상태` 세 줄을 함께 적는다.
상세 계약을 두지 않은 축약 항목(Meetings, Usage, 업무 변경 복구 내부 API)은 해당 문서의 배선 표나 문단에 같은 내용을 적는다.

| 구분 | path 수 | 호출자 확인 | 호출자 없음 |
|---|---:|---:|---:|
| `/api/**` (프론트엔드 전용 표면) | 87 | 59 | **28** |
| `/internal/**` (서비스 간 표면) | 11 | 11 | **0** |
| 합계 | 98 | 70 | **28** |

### 호출 주체

- `/api/**`는 프론트엔드만 호출한다. ai-svc·access-svc 어느 쪽도 이 서비스의 `/api/**`를 호출하지 않는다
  (ai-svc의 outbound 조립은 `/internal/**`과 access-svc `/internal/authz/**`뿐이고, access-svc의 outbound RestClient는 `DocumentInternalClient` 하나다).
- `/internal/**` 11개 중 8개는 ai-svc가, 3개(`POST /internal/workspaces/{workspace_id}/initial-note`와 데이터 파기 2개)는 access-svc가 호출한다.
  데이터 파기 2개(`POST /internal/purge/workspaces`, `POST /internal/purge/users`)는 access-svc `DataPurgeRequestJob`이 호출하고,
  성공하면 access-svc가 이어서 ai-svc 파기 API를 부른다(document는 ai-svc 파기를 호출하지 않는다).
- 주의: `/internal/agent/runs`와 `/internal/ai/tasks`는 이 서비스의 경로가 **아니다**. ai-svc가 노출하고 이 서비스가 호출하는 경로다
  (`pipeline/app/modules/agent_run/interfaces/http/routes.py:30`, `pipeline/app/modules/task_cancellation/interfaces/http/routes.py:10`).

### 호출자 없는 28개

의도된 미사용과 실제 공백을 구분한다. path 기준 집계이므로 한 path의 여러 method는 1개로 센다(9+1+4+4+2+2+1+5 = 28).

| 분류 | API | 판정 |
|---|---|---|
| 회의·음성 (9) | `meetings`, `meetings/{id}`, `meetings/{id}/live-tickets`, `meetings/{id}/notes`, `meetings/{id}/notes/{version}/append-preview`, `meetings/{id}/notes/{version}/apply`, `meetings/{id}/recording`, `meetings/{id}/recording-url`, `speech/transcriptions` | **공백** — 서버 기능은 완성되어 있으나 프론트엔드에 화면·호출 지점이 없다 |
| 동기 Query (1) | `chat/sessions/{id}/query` | **의도된 미사용** — 프론트엔드가 비동기 `query/runs` + SSE `GET /api/query/runs/{request_id}/events`를 쓴다. 결함이 아니다 |
| 삭제 되돌리기 (4) | `documents/trash`, `documents/{id}/restore`, `folders/{id}/restore`, `documents/{id}/versions/{version}` | **공백** — 휴지통·복구 API가 있는데 UI에 되돌리기 경로가 없다 |
| 탐색 (4) | `navigation`, `navigation/breadcrumb`, `navigation/search`, `folders/{id}/children` | **의도된 미사용** — 프론트엔드는 `GET .../document-tree` 하나로 트리를 통째로 받는다 |
| Wiki 페이지 편집 (2) | `wiki/pages/{id}/rename`, `wiki/pages/{id}/diff` | **공백** |
| Agent 계획 (2) | `agent/runs/{run_id}/cancel`, `agent/runs/{run_id}/revise` | **공백** — `decideAgentPlan`은 `approve`·`reject`만 보낸다 |
| Wiki 미리보기 (1) | `chat/sessions/{id}/wiki/preview` | **공백** — 사용자가 미리보기 없이 바로 저장한다 |
| 그 밖 (5) | `documents/markdown`, `documents/{id}/blocks`, `documents/{id}/duplicate`, `documents/{id}/export`, `usage/models` | **공백** |

### 근거와 한계

- 프론트엔드 근거: `/Users/mireutale/coding/git/Fruition-frontend`를 읽기 전용으로 확인했다. `src/` 전체의 `apiFetch(` 호출 지점 84개를 모두 해석했고,
  경로는 `src/shared/api/client.ts:144-149`의 `workspacePath(workspaceId, ...segments)`와 지역 helper(`documentPath()`, `mutateTreeItem()`,
  `uploadPdfMultipart()`)·템플릿 리터럴을 거쳐 조립된다. **리터럴 grep만으로는 과소 집계된다.**
- 이 동적 조립 때문에 프론트엔드 근거는 원칙적으로 과소 집계될 수 있다. 예로 `assets/{asset_id}/content`는 문서 Markdown을 정규식으로 훑어
  경로를 만들기 때문에(`src/shared/api/assets.ts:4-5`) 호출식에 리터럴이 없다.
- Next.js BFF route(`app/api/document-transport/route.ts`)와 middleware(`middleware.ts`)가 존재하며 **전수 추적하지 않았다**.
  확인한 범위에서 BFF는 `{ origin, directUpload }`만 반환하고 프록시하지 않으며, middleware는 `/api/:path*`에 대한 접근 코드 게이트다.
  `next.config.*` rewrite와 배포 프록시 설정은 확인하지 않았다.
- 이 문서를 쓴 뒤 wiki-schema 4개와 편집 잠금 2개가 배선되어 미호출 목록에서 빠졌다.
  `GET /api/workspaces/{workspace_id}/wiki-schema/drafts`도 함께 추가되어 `drafts`는 `GET`·`POST` 둘을 갖는다.
