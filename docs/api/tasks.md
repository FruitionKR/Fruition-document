# Document AI 작업 취소 API

[서비스 문서](../README.md) · [API 목차](README.md)

## 공개 API

### 1. Method + Path

- `POST /api/workspaces/{workspaceId}/ai/tasks/{id}/cancel`
- `GET /api/workspaces/{workspaceId}/ai/tasks/{id}`

### 2. 목적

Query, Agent turn, 문서·채팅 수집, 수집 후처리, Wiki lint·복구, PDF 변환,
Skill 작성·게시·수정 작업의 취소와 복구 상태를 관리합니다.
Agent turn 취소는 이미 완료한 하위 Agent run과 해당 턴의 Skill 게시도 포함합니다.
일반 자율 Agent run을 직접 다루는 계약은 [Agent API](agent.md)를 따릅니다.

### 3. Auth 필요 여부

Bearer access token. 해당 workspace 구성원이면서 작업을 시작한 사용자여야 합니다.

### 4. Request body

없음. `id`는 시작 API가 반환한 작업 ID입니다. 변환은 `convert:{document_id}`입니다.
동기 Query와 Skill 작성·게시·수정은 시작 요청의 선택 query parameter `run_id`로 ID를 미리 지정할 수 있습니다.
`run_id`는 영문·숫자·`:`·`_`·`-`의 1~120자입니다. 동기 Query는 이미 사용한 ID를 거절합니다.

### 5. Response body

```json
{"id":"query_example","status":"rolling_back","error_code":""}
```

| 상태 | 의미 |
|---|---|
| `running`, `completed` | 정상 작업 기록 |
| `failed` | 동기 AI 호출이 실패했거나 PDF 변환이 재시도를 다 쓰고 실패함 |
| `cancel_requested` | 취소를 저장하고 실행 중인 작업이 멈추기를 기다림 |
| `rolling_back` | AI DB·객체 저장소와 업무 DB를 복구 중 |
| `cancelled` | 복구 확인 완료 |
| `rollback_failed` | 후속 변경 충돌·불명확한 실행 결과 등으로 복구를 완료하지 못함 |

### 6. Error response

인증 실패 `401`, 권한 실패 `403`, 다른 actor의 작업 또는 없는 작업 `404`.
복구 실패는 상태의 `error_code`로 확인합니다. 연결 장애는 `rollback_retry_pending`을 남기고 자동 재시도합니다.
`rollback_failed`는 같은 취소 요청으로 재시도할 수 있으며 이미 복구한 단계는 재실행하지 않습니다.
Agent 복구 worker가 응답 없이 종료되면 마지막 시도의 lease도 만료 후 회수합니다.
회수한 job은 기존 실행 잠금과 복구 기록을 사용하며, 처리 오류가 발생하면 `rollback_failed`로 남깁니다.

### 7. Pagination / filtering

없음. 작업 하나를 ID로 조회합니다.

### 8. 권한·일관성 규칙

[공통 취소·복구 계약](https://github.com/FruitionKR/Fruition-flatform/blob/main/docs/api/ai-task-cancellation.md#권한일관성-규칙)을 따릅니다.

### 9. 예시 요청/응답

```http
POST /api/workspaces/ws_example/ai/tasks/query_example/cancel
Authorization: Bearer <access-token>
```

```json
{"id":"query_example","status":"cancelled","error_code":""}
```

### 10. 구현 파일

- Backend: `src/main/java/fruition/core/aitask/service/AiTaskCancellationService.java`, `V47__add_ai_task_rollback_journal.sql`
- Python: `app/modules/task_cancellation/`, `app/modules/agent_run/application/rollback_agent_run.py`
- 기계 판독 계약: `api-specs/openapi.yaml`, `AI/pipeline/api-specs/openapi.yaml`
- 호출자: 프론트엔드 — `src/entities/wiki/api/wiki.ts:40`(취소), `:49`(상태 조회) (`cancelQueryRun`)
- 하위 호출: ai-svc `POST ${AGENT_STATUS_ENDPOINT 호스트}/internal/ai/tasks/{id}/cancel`·`/rollback-backend`·`GET /{id}`·`POST /documents/{id}/cancel` (`PipelineTaskCancellationClient`), 되돌리기 중 객체 저장소 `removeObject`
- 배선 상태: 배선됨

## 업무 변경 복구 내부 API

AI가 Document의 업무 변경을 복구할 때 호출합니다.

| Method + Path | 인증 | 입력 | 출력·목적 |
|---|---|---|---|
| `POST /internal/agent/tools/rollback/{id}/changes` | `X-Agent-Service-Token` | `workspace_id`, `user_id` | `200`: 미복구 변경 ID 배열, 내림차순 |
| `POST /internal/agent/tools/rollback/{id}/finalize-edits` | `X-Agent-Service-Token` | 같은 actor | `200`: 본문 복구 revision·hash 이벤트 배열. 후속 변경 충돌 `409` |
| `POST /internal/agent/tools/rollback/{id}/changes/{changeId}` | `X-Agent-Service-Token` | 같은 actor | `200`: 한 변경 복구. 충돌 `409` |

세 경로 모두 **ai-svc가 호출하는 서비스 간 API**다. 호출자는 `pipeline/app/modules/task_cancellation/infrastructure/backend_rollback.py`이며
`/changes`는 `:12`(조립)·`:15`(전송), `/changes/{changeId}`는 `:18`, `/finalize-edits`는 `:20`에서 만든다.
하위 호출은 core DB 역순 복구와 객체 저장소 `removeObject`이고, ai-svc로 다시 나가는 HTTP는 없다. 프론트엔드 호출자는 없다(내부 전용이므로 정상이다).

전체 순서와 검증 범위는 [공통 계약](https://github.com/FruitionKR/Fruition-flatform/blob/main/docs/api/ai-task-cancellation.md)을 따릅니다.
