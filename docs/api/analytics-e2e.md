# 로컬 제품 경로 분석 E2E cookbook

이 문서는 요청 계약을 소스에서 확인한 실행 절차다. 실제 실행 결과를 의미하지 않는다.
실행 담당자는 로컬 전용 Document/AI/Access, PostgreSQL/Redis/Kafka/MinIO와 테스트 계정을 사용한다.
실제 PostHog 전송 대신 로컬 수신기를 사용하거나 `POSTHOG_PROJECT_TOKEN`을 비워 relay를 끈 상태에서
`ANALYTICS_ENABLED=true`로 outbox를 확인한다. 기존 실행 중인 서비스의 설정이나 DB를 이 절차 준비 과정에서 변경하지 않는다.

아래 `$DOCUMENT`는 로컬 Document URL, `$WS`는 테스트 workspace, `$SESSION`은 본인 소유 세션이다.
모든 API 요청에 `Authorization: Bearer <access_token>`을 붙이고 JSON 요청에는
`Content-Type: application/json`을 붙인다. 실제 토큰을 보고서에 저장하지 않는다.
ID와 시각은 예시이며 응답에서 얻은 실제 값을 다음 요청에 사용한다.

## 채팅 export: 새 문서와 기존 문서 반환

1. `GET /api/workspaces/$WS/chat/sessions/$SESSION/messages` → 200 `{"messages":[...]}`.
   같은 `pair_id`의 user/assistant가 모두 완료된 문답을 선택한다.
2. `POST /api/workspaces/$WS/chat/sessions/$SESSION/wiki`:

   ```json
   {"pair_ids":["<completed_pair_id>"],"export_id":"<new_UUID>","mode":"selected"}
   ```

   200 응답:

   ```json
   {"exportDocumentId":"doc_<generated>","status":"saved","export_id":"<same_UUID>"}
   ```

3. 같은 세션/완료 문답 내용을 새 `export_id`로 다시 보내면 200이며 `status:"skipped"`,
   같은 `exportDocumentId`, 새 `export_id`를 반환한다. 이것이 **기존 export 문서 반환** 경로다.
   임의의 기존 문서에 append하는 `document_id`/target 필드는 없다.
4. `mode:"all"`도 모든 문답의 `pair_ids`를 직접 보내야 한다. mode는 선택을 자동 확장하지 않는다.
   같은 export_id 재시도는 이벤트 중복을 제거하며 다른 내용/사용자/대화/mode로 재사용하면 거절한다.
5. `chat_exported`의 `export_id`, `document_id`, `conversation_id`, `pair_count`, `mode`,
   `status(saved/skipped)`를 대조한다. 저장 자체는 Wiki ingest가 아니다.
   후속 편입은 `POST /api/workspaces/$WS/documents/<exportDocumentId>/ingest` 제품 API로 따로 요청한다.

근거: `ChatWikiExportController`, `ChatWikiExportRequest/Response`, `ChatWikiExportService`, `ChatExportLinks`.

## AI 작업 되돌리기

완료된 실제 AI 문서 편집 또는 ingest/lint 작업을 사용한다. 일반 문서 버전 restore나
태스크 cancel API는 이 분석 시나리오의 대체 경로가 아니다.

1. `GET /api/workspaces/$WS/ai-operation-logs?status=succeeded`에서 실제 작업 ID를 고른다.
2. `GET /api/workspaces/$WS/ai-operation-logs/<original_operation_id>/restore-preview` → 200.
   문서 편집 응답 예:

   ```json
   {"operation_id":"<original_operation_id>","delete_count":0,"restore_count":1,"rebuild_count":0,"pages":[],"document":{"document_id":"doc_<id>","from_version":5,"to_version":3},"preview_token":"<opaque_token>"}
   ```

   Wiki 작업은 `document`가 생략되고 `pages`에
   `{"page_id":"<id>","action":"delete|restore|rebuild","target_revision":null,"contribution_count":0}`
   형태의 계획이 들어간다(`restore`의 target_revision은 숫자).
3. `POST /api/workspaces/$WS/ai-operation-logs/<original_operation_id>/restore`:

   ```json
   {"preview_token":"<exact_preview_token>"}
   ```

   문서 편집은 200:

   ```json
   {"run_id":null,"operation_id":"<new_restore_operation_id>","restored_from":"<original_operation_id>","delete_count":0,"restore_count":1,"rebuild_count":0,"rebuilding":false,"status":"succeeded"}
   ```

   Wiki는 202이며 같은 필드에 `run_id`, 새 `operation_id`, 실제 계획의 count,
   `rebuilding:true`, `status:"queued"`를 반환한다. 202는 완료가 아니다.
4. **새** restore operation ID로 `GET /api/workspaces/$WS/ai-operation-logs/<new_restore_operation_id>`를 조회한다.
   `status`, `restored_from`, `completed_at`, `changes`, `restore` 요약을 확인한다.
   진행 상태는 `processing/applying/notify_pending/rebuilding`, terminal은
   `succeeded/partially_succeeded/failed/conflict`다. 성공 판정은 `succeeded`와 실제 본문/버전 확인으로 한다.
5. `ai_operation_rolled_back`에서 원래 `operation_id`, 새 `restore_attempt_id`, `status`,
   `restored_operation_count`, `cascaded_operation_ids`를 확인한다.
   409 stale preview는 새 preview가 필요하며, 실행 전 거절만으로 실패 이벤트를 기대하지 않는다.

근거: `OperationQueryController`, `RestorePreviewResponse`, `RestoreExecuteResponse`, `DocumentAnalytics.restored`.

## 유효한 query 요청의 실제 실패

`POST /api/workspaces/$WS/chat/sessions/$SESSION/query/runs`:

```json
{"question":"테스트 문서의 핵심 내용을 알려 주세요.","allow_web_search":false}
```

provider/model은 함께 생략하면 워크스페이스 기본값을 사용한다. 명시할 때는 현재 허용된 조합을 둘 다 보낸다.
202 응답은 `{"request_id":"query_<id>","status":"pending"}` 형태다(즉시 진행된 경우 상태는 달라질 수 있다).
`GET /api/query/runs/<request_id>` 또는 `GET /api/query/runs/<request_id>/events`(SSE)로 확인한다.

실제 실패 시 상태 응답 예:

```json
{"request_id":"query_<id>","status":"failed","provider":"<resolved_provider>","model":"<resolved_model>","web_search_enabled":false,"error":"<사용자용 오류 문구>","error_code":"provider_unavailable"}
```

실패 입력을 넣을 JSON 필드는 없다. 실패는 유효한 요청이 접수된 뒤 로컬 AI worker의 실제 provider 호출에서
발생해야 한다. 실행 담당자가 별도 격리된 AI 인스턴스에서 provider 장애/timeout 조건을 구성해 제품 경로로
재현하고, 실제 AI 결과가 Kafka를 통해 Document에 적용됐는지 확인한다. 정상 요청만으로 실패가 보장되지는 않는다.
빈 질문, 잘못된 model, 잘못된 allow_web_search, 권한 거절, 크레딧 거절은 실행 전 거절이므로 대체 증거가 아니다.
AI 결과나 analytics 행을 직접 INSERT하거나 가짜 callback으로 만들지 않는다.

`query_failed`는 `query_id=request_id`로 확인한다. 원시 error_code와 분석 failure_reason은 다르다:

| 제품 error_code | 분석 failure_reason |
| --- | --- |
| provider_unavailable / provider_rate_limited | provider_error |
| deadline_exceeded | timeout |
| input_rejected | validation |
| internal_error / task_failed / agent_turn_failed | internal_error |
| permission | permission |
| storage_error | storage_error |
| 그 밖의 코드 | unknown |

cancelled는 query_failed를 남기지 않는다. 같은 query_id에 query_answered와 query_failed가 동시에 없어야 한다.
`X-PostHog-Session-Id`에 유효한 UUID를 넣었다면 `$session_id` 연결도 확인한다.

근거: `QueryController`, `QueryRequest`, `QueryRunStatusResponse`, `AiTaskResultApplier.applyQuery`, `AnalyticsSchema`.

## PDF 변환 성공과 실패

1. `POST /api/workspaces/$WS/documents`, multipart `file=@<local.pdf>`, 새 UUID `Idempotency-Key` → 201.
   PDF는 `.pdf` 확장자와 `%PDF-` 헤더가 필요하다.
2. 반환한 원본 `id`로 `POST /api/workspaces/$WS/documents/<source_id>/convert-markdown`,
   새 UUID `Idempotency-Key`, 본문 없음 → 202.
   두 응답은 모두 `DocumentUploadResponse` 계약:

   ```json
   {"id":"doc_<id>","filename":"<name>","mime_type":"<mime>","byte_size":123,"status":"processing","source_uri":"<storage_uri>","uploaded_at":"<UTC_time>","editable":true,"current_version":1,"document_role":"EDITABLE","folder_id":null}
   ```

   변환 응답의 `id`는 **새 Markdown placeholder**다. 업로드 응답은 원본 PDF ID이며
   `mime_type:application/pdf`, `editable:false`, `document_role:ORIGINAL`, `status:uploaded`다.
3. `GET /api/workspaces/$WS/documents/<placeholder_id>`에서 `status`, `error_message`,
   `pipeline_run_id`, `processing_state`, `processing_stage`, `source_document_id`를 확인한다.
   변환 run ID는 `convert:<placeholder_id>`다.
   `GET /api/workspaces/$WS/ai/tasks/convert:<placeholder_id>` → 200
   `{"id":"convert:<placeholder_id>","status":"<task_status>","error_code":""}`로도 확인한다.
   실패 시 error_code에 실제 실패 코드가 들어간다.
4. 성공은 유효한 텍스트가 있는 PDF를 실제 worker로 끝까지 처리한다. 마지막 batch 병합 후에만
   `pdf_convert_completed(status:succeeded,page_count:<positive_int>)`가 생긴다.
   이후 자동 ingest가 시작되면 문서의 현재 processing 상태가 바뀔 수 있으므로 변환 run과 이벤트를 함께 확인한다.
5. 실패는 `%PDF-` 헤더를 유지하지만 잘린/잘못된 구조의 별도 테스트 PDF를 업로드하고 변환 요청한다.
   업로드를 통과한 뒤 실제 parser/worker의 terminal 실패가 발생했는지 확인한다.
   손상 PDF 수용/거절은 parser에 달렸으므로 terminal 실패를 확인하기 전에는 성공한 실패 재현으로 기록하지 않는다.
   헤더 없는 파일의 업로드 4xx는 변환 실패 증거가 아니다. 사용자 취소도 변환 실패 이벤트를 만들지 않는다.
6. 이벤트의 `document_id`는 원본 PDF, `conversion_id`는 `convert:<placeholder_id>`다.
   실패에는 `status:failed`, 제한된 `failure_reason`이 있으며 성공에만 `page_count`가 필수다.
   새로운 Idempotency-Key로 재변환할 때 `is_reconvert:true`를 확인한다.

근거: `DocumentController`, `DocumentUploadResponse`, `DocumentDetailResponse`,
`DocumentService.convertToMarkdown/applyConvertResult`, `DocumentAnalytics.converted`.

## 로컬 상태 읽기와 증거 보존

DB 조회는 실행 담당자가 기존 로컬 read-only 접속으로 수행한다. 아래 SQL은 읽기 전용이며
`:workspace_id`와 ID 목록은 SQL client의 bind parameter로 전달한다.

```sql
SELECT event_name, event_id, occurred_at, attempts, sent_at, dead_at,
       payload->'properties' AS properties
FROM analytics_outbox
WHERE payload->'properties'->>'workspace_id' = :workspace_id
  AND event_name IN ('chat_exported','ai_operation_rolled_back','query_failed','pdf_convert_completed')
ORDER BY occurred_at DESC;

SELECT query_id, event_name, occurred_at
FROM analytics_query_terminals WHERE query_id = :query_id;
```

로컬 수신기 전송 검증은 outbox의 `sent_at`과 수신한 event UUID/payload를 함께 대조한다.
빈 토큰으로 relay를 끈 경우 sent_at이 NULL인 것이 정상이며 외부 전달 성공으로 보고하지 않는다.
각 시나리오에 HTTP 상태, 제품 ID, terminal 상태, event_id, 정제된 속성을 기록한다.
질문/답변/본문/토큰/저장소 URL이 분석 payload에 없는지도 확인한다.
