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
| [Meetings](meetings.md) | 15 + WS 1 | 회의 생성·조회·삭제, 실시간 받아쓰기 WebSocket, 녹음 원본 직접 업로드·비동기 파일 전사·취소, 회의록 초안·저장 |
| [Navigation](navigation.md) | 10 | 폴더와 문서 트리 탐색 |
| [Notifications](notifications.md) | 3 | 앱 안 알림 목록과 읽음 처리 |
| [Query](query.md) | 4 | 동기·비동기 Query와 SSE |
| [Speech](speech.md) | 2 | 채팅 음성 입력(음성 → 텍스트, 저장 없음), 답변 음성 듣기(NDJSON 스트림) |
| [Skills](skills.md) | 8 | Skill 작성·게시·설정과 참조 읽기 |
| [Wiki](wiki.md) | 7 | Wiki 조회·기여·유지보수 |
| [Usage](agent.md#본인-모델-사용량-조회) | 1 | 본인 모델별 토큰 사용량 조회 |

기계 판독 원본은 `api-specs/openapi.yaml`이며 **path 115개(`/api/**` 103 + `/internal/**` 12), operation 127개**다.
위 표의 "API 수"는 각 도메인 문서가 다루는 API 수이고, 충돌할 경우 실행 코드와 생성된 OpenAPI를 우선한다.
표의 도메인별 API 수 합계는 같은 path의 여러 method를 세는 단위가 섞여 operation 수와 다를 수 있다. 정확한 수는 OpenAPI를 본다.

## AI 실패 코드와 사용자 문구

ai-svc는 실패 원인을 **코드**로만 보내고 예외 원문은 자기 로그에만 남긴다. 사용자에게 나가는 문구는
`AiErrorMessages`(`fruition/core/aitask/AiErrorMessages.java`) 한 곳에서 코드를 한국어로 바꾼다.
코드가 목록에 없으면 기능별 기본 문구(질의 "질의 처리 중 오류가 발생했습니다.", Agent "Agent 처리 중 오류가 발생했습니다.",
위키 편입 "위키 편입에 실패했습니다.", 위키 정리 "위키 정리에 실패했습니다.", PDF 변환 "PDF 변환에 실패했습니다. …", 회의 녹음 전사 "녹음 파일을 전사하지 못했습니다. 다시 올려 주세요.")로 바꾸고 코드마다 한 번 WARN을 남긴다. 회의록 API(`MEETING_NOTES_*`)는 이 목록을 거치지 않고 코드를 그대로 준다.

| 노출 위치 | 코드 | 문구 |
|---|---|---|
| Agent `GET /agent/turn/{id}` | `error` | `error_message` |
| Agent 채팅 말풍선 `error_message`, Agent·질의 SSE `error` | - | 문구만. SSE에는 `code`도 함께 간다 |
| 질의 `GET /query/runs/{id}` | `error_code` | `error` |
| 질의 SSE `query.failed` | `code` | `error` |
| 문서 `error_message`(목록·상세), 회의 전사 `error` | 저장 안 함 | 문구 (변환은 `convert`, 전사는 `transcription` 문구) |
| Wiki 정리 `GET .../wiki/maintenance/runs/{id}` | `error_code` | `error` |

| 코드 | 문구 |
|---|---|
| `provider_rate_limited` | AI 서비스 요청이 많아 처리하지 못했습니다. 잠시 후 다시 시도해 주세요. |
| `provider_unavailable` | AI 서비스에 일시적인 문제가 있어 처리하지 못했습니다. 잠시 후 다시 시도해 주세요. |
| `input_rejected` | 요청 내용을 처리할 수 없습니다. 입력을 확인해 주세요. |
| `deadline_exceeded` | 처리 시간이 초과되어 중단했습니다. 다시 시도해 주세요. |
| `cancelled` | 처리가 취소되었습니다. |
| `MEETING_RECORDING_*` / `MEETING_TRANSCRIPT_*` 등 | 회의 전사 전용 문구(`docs/api/meetings.md` 표) |
| `internal_error`, `task_failed`, `agent_turn_failed` | 기능별 기본 문구 |
| `interrupted_attempts_exhausted` | 처리가 여러 번 중단되어 완료하지 못했습니다. 다시 시도해 주세요. |
| `rollback_failed`, `rollback_child_failed`, 그 밖의 `rollback_*`(개별 항목 우선, 없으면 접두사) | 변경을 되돌리지 못했습니다. 작업 기록을 확인해 주세요. |
| `agent_turn_cancelled` / `agent_turn_interrupted` | Agent 작업이 취소되었습니다. / 중단되었습니다. 다시 시도해 주세요. |
| `invalid_agent_turn_request` / `agent_not_configured` / `agent_turn_route_contract_failed` | Agent 요청이 올바르지 않습니다. / 사용할 수 없는 상태입니다. 관리자에게 문의해 주세요. / 처리 결과를 확정하지 못했습니다. 다시 시도해 주세요. |
| `mutation_intent_required` | 어떤 변경을 원하시는지 조금 더 구체적으로 알려 주세요. (오류가 아니라 되묻는 문구) |
| `ready_markdown_migration_unrecoverable` | 이전 편집안을 복구할 수 없어 적용하지 못했습니다. 다시 요청해 주세요. |
| `markdown_*` | 편집안을 만들지 못했습니다. 다시 시도해 주세요. |
| `REFERENCE_DOCUMENT_TOO_LARGE` / `dependency_failed` | 편집 대상 참조 문서가 30,000자를 넘어 처리할 수 없습니다. / 앞선 작업이 실패해 이 작업은 건너뛰었습니다. |
| `SKILL_NOT_FOUND` / `SKILL_DISABLED` / `invalid_llm_selection` | 선택한 Skill을 찾을 수 없습니다. / 비활성화되어 있습니다. / 선택한 모델을 사용할 수 없습니다. 모델을 다시 선택해 주세요. |
| `agent_result_*` | 결과 형식·요청 불일치·본문 누락 등 Agent 결과를 적용할 수 없는 사유별 문구 |
| `PIPELINE_UNAVAILABLE` / `PIPELINE_TIMEOUT` / `INSUFFICIENT_CREDIT` | AI 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요. / 질의 대기 시간이 지나 취소를 요청했습니다. / 크레딧이 부족해 작업을 시작하지 않았습니다. 크레딧을 충전한 뒤 다시 시도해 주세요. |

## 공통 오류: `402 INSUFFICIENT_CREDIT`

`app.billing.enforce`(`BILLING_ENFORCE`)가 true인 환경에서 비용이 드는 AI 작업은 시작 전에 가용 잔액(잔액 − 진행 중인 예약)을 확인하고,
그 작업 종류의 최저 비용(`app.billing.minimum.<kind>`, 없으면 예상 상한의 10%)보다 작으면 작업을 등록하지 않고 `402`로 거절한다.
본문은 공통 `ErrorResponse`인 `{"error":{"code":"INSUFFICIENT_CREDIT","message":"크레딧이 부족해 작업을 시작하지 않았습니다. …"}}`이다(Agent 계획 수정·승인과 실시간 전사 갱신은 "크레딧이 부족해 중단했습니다. …"). 두 문구 모두 `AiErrorMessages`에만 있다.
대상: Query(`query/runs`, 동기 `query`), Agent 턴·`revise`·`approve`, 문서 ingest(재처리)·변환(변환 뒤 자동 ingest의 거절은 문서 `failed`, `error_message`는 `INSUFFICIENT_CREDIT` 문구(크레딧이 부족해 작업을 시작하지 않았습니다…)), 음성 합성·전사, Wiki lint, Skill 작성·게시·수정.
Wiki 복원(`restore_ingest`·`restore_lint`)은 모델을 부르지 않아 비용이 없으므로 예약·사전 승인 없이 항상 시작한다.
비동기로 처리되는 회의록 초안은 버전이 `failed`/`error_code=INSUFFICIENT_CREDIT`, 녹음 파일 전사는 실패 사유, 실시간 받아쓰기는 `insufficient_credit` 오류 후 close `1008`이다.
최저 비용의 기본값은 그 작업 종류 예상 상한(`app.billing.estimate.<kind>`)의 10%(최소 1)이고 `app.billing.minimum.<kind>`로 덮어쓴다. 예약 금액은 `min(예상 상한, 가용 잔액)`이다. (ADR-0029 구현 노트: 최저 비용 기본값을 상한의 10%로 둔 것은 이 구현의 선택이며, ADR 본문의 "기본 모델 최단 요청 비용"은 운영 데이터로 `minimum`을 채우며 대체한다.)
잔액 조회는 `GET /api/users/me/credits`, 의미 상세는 [Agent API의 선불 크레딧 절](agent.md#본인-크레딧-잔액-선불)에 있다.

## 호출 관계(배선) 요약

각 API의 상세 계약 `10. 구현 파일`에 `호출자` / `하위 호출` / `배선 상태` 세 줄을 함께 적는다.
상세 계약을 두지 않은 축약 항목(Meetings, Usage, 업무 변경 복구 내부 API)은 해당 문서의 배선 표나 문단에 같은 내용을 적는다.

| 구분 | path 수 | 호출자 확인 | 호출자 없음 | 미조사 |
|---|---:|---:|---:|---:|
| `/api/**` (프론트엔드 전용 표면) | 103 | 55 | **31** | 17 |
| `/internal/**` (서비스 간 표면) | 12 | 12 | **0** | 0 |
| 합계 | 115 | 67 | **31** | 17 |

path 수는 `api-specs/openapi.yaml`에서 센 값이다. 호출자 조사는 `/api/**` 86개 시점에 한 것이라, 이후 추가된 17개(path 103 − 86)는 아직 프론트엔드 호출을 확인하지 않았다(미조사).

### 호출 주체

- `/api/**`는 프론트엔드만 호출한다. ai-svc·access-svc 어느 쪽도 이 서비스의 `/api/**`를 호출하지 않는다
  (ai-svc의 outbound 조립은 `/internal/**`과 access-svc `/internal/authz/**`뿐이고, access-svc의 outbound RestClient는 `DocumentInternalClient` 하나다).
- `/internal/**` 12개 중 8개는 ai-svc가, 3개(`POST /internal/workspaces/{workspace_id}/initial-note`와 데이터 파기 2개)는 access-svc가 호출하고, 1개(`POST /internal/payments/webhook`)는 결제 대행사(PG)가 호출한다.
  데이터 파기 2개(`POST /internal/purge/workspaces`, `POST /internal/purge/users`)는 access-svc `DataPurgeRequestJob`이 호출하고,
  성공하면 access-svc가 이어서 ai-svc 파기 API를 부른다(document는 ai-svc 파기를 호출하지 않는다).
- 주의: `/internal/agent/runs`와 `/internal/ai/tasks`는 이 서비스의 경로가 **아니다**. ai-svc가 노출하고 이 서비스가 호출하는 경로다
  (`pipeline/app/modules/agent_run/interfaces/http/routes.py:30`, `pipeline/app/modules/task_cancellation/interfaces/http/routes.py:10`).

### 호출자 없는 31개

의도된 미사용과 실제 공백을 구분한다. path 기준 집계이므로 한 path의 여러 method는 1개로 센다(13+1+4+4+1+2+1+5 = 31).

| 분류 | API | 판정 |
|---|---|---|
| 회의·음성 (13) | `meetings`, `meetings/{id}`, `meetings/{id}/live-tickets`, `meetings/{id}/notes`, `meetings/{id}/notes/{version}/append-preview`, `meetings/{id}/notes/{version}/apply`, `meetings/{id}/recording/uploads`, `meetings/{id}/recording/uploads/parts`, `meetings/{id}/recording/uploads/abort`, `meetings/{id}/recording/uploads/complete`, `meetings/{id}/transcription/cancel`, `meetings/{id}/recording-url`, `speech/transcriptions` | **공백** — 서버 기능은 완성되어 있으나 프론트엔드에 화면·호출 지점이 없다 |
| 동기 Query (1) | `chat/sessions/{id}/query` | **의도된 미사용** — 프론트엔드가 비동기 `query/runs` + SSE `GET /api/query/runs/{request_id}/events`를 쓴다. 결함이 아니다 |
| 삭제 되돌리기 (4) | `documents/trash`, `documents/{id}/restore`, `folders/{id}/restore`, `documents/{id}/versions/{version}` | **공백** — 휴지통·복구 API가 있는데 UI에 되돌리기 경로가 없다 |
| 탐색 (4) | `navigation`, `navigation/breadcrumb`, `navigation/search`, `folders/{id}/children` | **의도된 미사용** — 프론트엔드는 `GET .../document-tree` 하나로 트리를 통째로 받는다 |
| Wiki 페이지 편집 (1) | `wiki/pages/{id}/diff` | **공백** |
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
- 이 문서를 쓴 뒤 편집 잠금 2개가 배선되어 미호출 목록에서 빠졌다.
