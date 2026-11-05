# Backend 변경 기록

## 2026-11-06

- 보기 권한 문서를 연 채 `이 문서 고쳐 줘`라고 했을 때 첨부가 수정될 수 있던 문제의 document 쪽이다. 서버가 확인한 `read_only_document_id`를 agent command에 싣는다(그 밖의 턴은 키가 없다). 구 AI는 키를 무시하고, 새 AI가 구 document-svc를 받으면 기존 동작이라 배포 순서는 상관없다.
- 문서 영구 삭제의 빈틈을 메운다. `source_refs`에만 그 문서가 있는 근거도 본문을 비운다(`agent_apply_projections.result`, 변경 기록, 질의 receipt, 삭제 뒤 도착한 결과). 대표 문서는 살아 있고 `source_refs`의 다른 문서만 이 기능 전에 지워진 인용은 정리 작업이 원문을 비운다(V107, `CONCURRENTLY`). 다른 문서의 근거와 살아 있는 문서만 가리키는 인용은 그대로다.
- `POST .../ai/tasks/{id}/cancel`이 이미 끝난(`completed`·`failed`) Agent turn을 `409`로 거절한다. `POST .../agent/turn/{id}/cancel`과 같은 조건이다. 되돌리면 완료된 답변이 `pending`으로 돌아가 세션이 잠겼다. 다른 종류의 작업은 그대로다.

## 2026-11-04

- 문서 영구 삭제가 채팅 인용의 원문을 지운다. 그 문서를 가리키는 `chat_message_references.quote`를 빈 문자열로 비우고 `ai_task_changes`의 같은 행 기록도 맞춘다(V104~V106, 응답의 `references[].text`가 `""`). 여러 문서를 걸친 인용은 원문을 나눌 수 없어 함께 비우고, 살아 있는 대표 문서는 남긴다. 이전에 지운 문서의 인용은 정리 작업이 찾아 지운다. 인용 위치와 답변 본문은 그대로다.
- 문서 영구 삭제가 남기던 복사본을 더 지운다. 이 문서의 agent 결과 receipt는 키만 남기고 `event_payload`를 비운다. 문서를 열지 않은 질의는 근거의 `source_document_id`로 찾아 `agent_apply_projections.result`, 그 변경 기록, 질의 receipt의 근거 본문만 비우고(V102·V103, CONCURRENTLY), agent receipt는 `event_payload`를 비운다. 답변 본문은 채팅 표시와 조회 API가 쓰여 남긴다. 삭제 뒤에 도착한 agent·질의 결과는 문서 행을 `FOR KEY SHARE`로 잠근 뒤 문서가 없는 근거의 본문을 비워 저장한다. `scrubOrphans`가 늦게 끝난 run의 receipt와 projection 변경 기록도 정리한다.
- 휴지통 보관 기간이 지나 문서를 영구 삭제할 때 같은 트랜잭션에서 `document_purged` command를 AI로 보낸다. AI가 수정안·되돌리기 스냅샷 등 본문 복사본을 지운다(AI를 먼저 배포해야 한다. 구 AI는 이 command를 폐기한다). 같은 트랜잭션에서 그 문서의 `ai_task_runs.command` 본문 필드, `agent_apply_projections`의 본문, `ai_task_changes`의 문서 변경 기록을 지운다(V98~V100 인덱스, `CONCURRENTLY`). 진행 중인 실행은 끝난 뒤 다음 실행에서 지운다. AI 결과 event `document_purge`가 알린 소스 페이지의 `wiki_page_versions.markdown`과 그 변경 기록도 지운다(V101). 오류 문구 `document_purged`·`rollback_document_purged`를 추가했다. API 변경은 없다.
- agent·query command에 `session_title_needed`를 추가했다. 세션 제목이 비었거나 `새 채팅`이면 `true`, 그 밖에는 `false`다. 저장하지 못할 제목을 만드느라 LLM을 부르던 호출을 AI가 건너뛴다(AI 배포 전에는 무시된다). 판단 기준은 `applySessionTitle`과 `ChatSession.DEFAULT_TITLE`·`isTitleUnset`으로 맞췄고, agent는 소유권 확인이, query는 누적 요약 조회가 읽은 세션을 재사용해 쿼리·트랜잭션이 늘지 않는다.
- `POST .../agent/turn`이 열린 문서의 편집 잠금으로 423 `DOCUMENT_EDIT_LOCKED`를 줄 때 잠긴 문서 제목을 `error.locked_document_name`에 담는다. 첨부를 고치는 요청도 열린 문서 잠금으로 막히는데 메시지로는 어느 문서인지 알 수 없었다. 거절 동작과 메시지는 그대로이고, 요청자가 읽을 수 없으면 키가 빠진다. 다른 API의 423 응답은 바뀌지 않는다.
- 클라이언트가 먼저 끊은 요청(SSE 구독 해제·다운로드 취소)을 `INTERNAL_ERROR status=500`으로 기록하던 것을 debug 로그로 낮췄다. `AsyncRequestNotUsableException`·`ClientAbortException` 타입만 해당하며 다른 입출력 오류는 지금처럼 500 오류 로그를 남긴다.
- API 문서의 작업 탭 설명을 고쳤다. `mode=task`도 첨부(`reference_document_ids`)가 있으면 답변을 고를 수 있다.
- 첨부 문서를 열지 않고 수정하는 흐름의 document 쪽을 추가했다. 내부 API `/internal/agent/skill-authoring/references/read` 응답에 `edit_revision`(본문과 같은 행의 revision)과 `can_edit`(편집 권한이 있고 `chat_export`가 아니며 변환 중이 아님, 파트 문서는 부모 기준)를 `EDITABLE` 문서에 한해 더했다. `GET /api/workspaces/{workspace_id}/agent/runs/{run_id}/edit-previews`를 추가해 ai-svc 응답에서 지금 읽을 수 있는 문서만 남기고 제목을 현재 값으로 덮어 돌려준다(ai-svc 배포 전에는 404).
- Agent `apply_document_edit` 저장을 병합 전용으로 바꿨다. 안 겹치는 변경은 지금처럼 병합하고, 겹치거나 기준 버전 스냅샷이 없으면 덮어쓰지 않고 409 `DOCUMENT_VERSION_CONFLICT`로 거절한다(ADR 0032의 예외). 일반 저장, Agent 수동 수락, 회의록 저장은 그대로 last-writer-wins다.
- 내부 API `/internal/agent/skill-authoring/references/read` 응답에 문서 제목 `display_name`을 추가했다. AI가 첨부 문서를 제목으로 구분하기 위한 것이다. 필드 추가만 있고 기존 필드와 동작은 그대로다.
- 내부 API `/internal/agent/skill-authoring/references/read`에 선택 필드 `max_chars`를 추가했다. 보내면 3만 자 초과 문서도 `413` 없이 앞부분을 돌려주고 응답에 `truncated`·`total_chars`를 넣는다. 글자 수는 코드 포인트로 세고 한 글자 중간에서 자르지 않는다. 생략하면 기존 동작(`413`, 응답 모양 동일)이고 권한 확인은 어느 쪽이든 같다.
- `POST .../agent/turn`에 참고 문서 `reference_document_ids`(최대 3개)를 추가하고 검증한 ID를 ai command에 같은 키로 전달한다(없으면 빈 배열, 본문은 싣지 않음). 문서 없이도, `documentId`와 함께여도 보낼 수 있고 읽기 권한만 요구한다. 중복과 `documentId`와 같은 ID는 서버가 뺀다. 없는 문서, 다른 워크스페이스 문서, 삭제된 문서, 비 Markdown, 읽을 수 없는 문서, 변환 중이거나 본문 없이 실패한 PDF 변환본이 하나라도 있으면 같은 메시지의 400으로 요청 전체를 거절한다. AI가 본문을 읽는 내부 API(`/internal/agent/skill-authoring/references/read`)는 멤버 확인만 하던 것에 문서 읽기 권한 판정을 더했다. 채팅 메시지에는 저장하지 않는다.
- Agent turn 결과의 되묻기 이유 코드 `clarify_reason`을 `chat_messages`에 저장하고(V97, NULL 허용) 채팅 메시지 조회 응답으로 내려준다. 화면이 되묻기 문장을 비교하지 않고 코드로 갈래를 정할 수 있고 새로고침 뒤에도 유지된다. `action=clarify`의 문자열 코드(소문자·숫자·밑줄 1~64자)만 받고, 모르는 코드는 그대로 전달하며, 형식이 어긋나면 값만 버린다. 값이 없거나 이전 메시지는 `null`이다.
- 보기 권한 문서를 연 채 보낸 Agent turn의 그 문서를 `POST .../agent/turn`의 `read_only_document_id`로 받아 `chat_messages`에 저장하고(V97, NULL 허용) 메시지 조회 응답으로 내려준다. 안내 기록이 브라우저에만 있어 다른 기기에서 AI 원문이 보이던 문제를 위한 것이다. 서버가 읽기 가능·편집 불가를 확인한 값만 남기고 그 밖의 값은 오류 없이 무시한다. 표시 전용이며 권한 판단에 쓰지 않고 AI로 보내지 않는다.
- 폴더를 편집할 수 없는 사람이 그 폴더에 넣거나 꺼내지 못하게 했다. 문서·폴더 이동(출발·목적지), 새 폴더, 새 문서, 파일·직접 업로드, Notion 가져오기, 복제, PDF 변환, 회의록 새 문서 저장, Agent 도구가 `403 DOCUMENT_WRITE_FORBIDDEN`("이 폴더에 넣거나 꺼낼 권한이 없습니다.")로 거절한다. 문서 소유자 예외가 없고 OWNER는 항상 되며 최상위는 멤버면 된다. 같은 폴더 안 순서 변경과 폴더 안 문서의 본문 편집·이름 변경에 대한 소유자 예외는 그대로다. 끝난 요청의 멱등 재요청은 권한이 회수돼도 저장된 응답을 돌려준다.
- 기존에 멤버가 하던 동작이 막힌다: 보기 폴더로 옮기기·새 항목 만들기, 보기 폴더의 자기 문서 꺼내기, 보기 폴더 문서 복제·PDF 변환.
- 문서 목록·트리·상세의 `can_convert`가 폴더를 보지 않아, 보기 폴더의 PDF에서 `true`인데 `convert-markdown`이 403이던 것을 맞췄다. 업로더와 `document.convert` 권한자도 그 폴더를 편집할 수 없으면 `false`이고 OWNER는 그대로다. 응답 모양은 같다.
- Agent 자동 승인이 출발 폴더도 본다(`move_document`·`move_folder`). 실행 때 거절될 계획은 승인 대기로 남고, 실행 때 허용되는 같은 폴더 안 순서 변경은 자동 승인 대상이 된다.
- 문서 복구가 항상 최상위로 가던 것을 원래 폴더의 마지막 위치로 돌아가게 했다. 소유자가 보기 폴더의 자기 문서를 삭제·복구해 꺼내는 경로를 막는다. 원래 폴더가 삭제됐거나 없을 때만 최상위로 돌아가고, 이름 충돌은 복구 위치 기준으로 `409 DUPLICATE_NAME`이다. 복구 권한은 그대로다. 삭제해도 `folder_id`가 남아 있어 스키마 변경이 없고, 이미 휴지통에 있는 문서에도 적용된다.
- 멤버의 빈 폴더 삭제에 그 폴더의 편집 권한을 요구한다(`403 DOCUMENT_WRITE_FORBIDDEN`). 보기 폴더를 비워 지운 뒤 문서를 복구해 최상위로 꺼내는 경로를 막는다. OWNER와 편집 가능한 빈 폴더는 그대로이고, 빈 폴더 복구와 Agent 취소 보상(`delete_folder`의 `require_empty`)은 권한이 회수돼도 그대로 동작한다.

## 2026-11-03

- 외부 호출을 Pod 수만큼 반복하던 스케줄 작업 4개(`DocumentPipelineRunReconciler`·`ChatWikiExportReconciler`·`DocumentOriginalHashWorker`·`FxRateService`)를 회차마다 한 Pod만 하게 했다. 대상이 있을 때만 `scheduler_leases` 행을 조건부 UPSERT 한 문장으로 점유하므로 커넥션·세션 잠금을 쥐지 않고, 일이 없는 서버의 쿼리 수는 그대로다. 주기·결과는 바뀌지 않는다(V96).
- 휴지통 영구 삭제의 보관 기간 지난 문서 조회가 documents 전체를 훑던 것을 `deleted_at` 부분 인덱스로 바꿨다(V95, CONCURRENTLY).
- 채팅에서 만든 문서(`origin=chat_export`)는 본문뿐 아니라 이름도 잠근다. `PATCH .../documents/{id}/rename`과 이를 그대로 쓰는 Agent `rename_document` 도구가 409 `DOCUMENT_TITLE_LOCKED`("채팅에서 만든 문서는 이름을 바꿀 수 없습니다.")로 거절한다. 내보내기 직후 서버가 위키 제목으로 이름을 확정하는 경로와 이동·삭제는 그대로 허용한다.
- 복구 미리보기 토큰 서명 키를 `RESTORE_PREVIEW_TOKEN_SECRET`이 없을 때 Pod마다 무작위로 만들던 것을, 모든 Pod가 함께 받는 `INTERNAL_CALLBACK_TOKEN`에서 용도별로 파생하게 했다. 여러 replica 뒤에서 미리보기와 실행이 다른 Pod로 가거나 그사이 재시작·배포가 있으면 409 `RESTORE_PREVIEW_STALE`로 롤백이 가끔 거절되던 문제다. 전용 키를 설정하면 그 값을 그대로 쓰고, 둘 다 없을 때(로컬)만 무작위 키를 쓴다. API·토큰 형식은 바뀌지 않는다.

## 2026-10-23

- PDF 변환 결과가 5MB를 넘어도 모든 페이지를 같은 문서에 저장한다. 크기 기반 파트 생성 코드를 제거하고, 여러 묶음을 한 트랜잭션에서 병합할 때 JDBC 저장 후 최신 본문·revision을 읽도록 수정했다.
- 큰 변환 문서의 편집·이미지 첨부 저장·동시 편집 병합·복제·버전 복원·편집 상태 초기화에도 업로드용 5MB 제한이 적용되지 않도록 했다. 직접 Markdown 생성·업로드 제한은 유지한다.
- 전체 1,418개 테스트와 bootJar를 통과한 뒤, 추가 변경 경로 46개를 재검증했다. 실제 PostgreSQL·MinIO에서 5MB 초과 결과의 순서 역전·중복 수신·전체 본문 일치·단일 문서 유지를 검증했다. 스키마 변경은 없으며 기존 분리 문서는 자동 변경하지 않는다.

## 2026-10-22

- PDF 변환 실패 결과에서 부모 작업을 먼저 `failed`로 닫아 지연된 문서 UPDATE가 DB 작업 guard에 거절되고 계속 재처리되던 순서를 수정했다. 문서 실패 상태·변환 queue 삭제를 먼저 flush하고 같은 트랜잭션에서 작업 종료·크레딧 예약 해제·실패 분석 이벤트를 반영한다. guard는 유지하며 늦은 비용은 기존 수집 대기열에서 정산한다.
- 실제 PostgreSQL의 `applyConvertBatch` 실패 경로로 재현한 뒤 문서/작업 실패, queue·batch 정리, 예약 1회 해제, 실패 이벤트 1회, 중복 결과 무시, 전체 rollback 및 terminal 이후 쓰기 거절을 검증했다. 변환·분석 대상 60개 테스트를 통과했다.

- `query_answered`가 Query의 `message_context.assistantMessageId`를 Agent용 snake_case 키로 읽어 저장된 인용을 0건으로 집계하던 오류를 수정했다. Query와 Agent의 실제 직렬화 계약을 구분하며 제품 flush·SAVEPOINT 경계·이벤트 허용 속성은 변경하지 않는다.
- 실제 `applyQuery`/`applyAgent(chat_answer)` → JPA 근거 저장 → 커밋 전 JDBC 분석 경로를 PostgreSQL에서 회귀 검증한다. 내부/웹 인용 수, 원본 계보·최초 편입 이력, 워크스페이스/삭제/빈 근거 필터, 관련 페이지만 있는 답변, 중복 terminal, 전체 롤백 및 분석 INSERT 실패 후 제품 커밋을 포함한다. 실제 Query/Agent command 직렬화 회귀 8건을 포함한 전체 1,415건과 `bootJar`를 통과했다.

- 서명 검증을 통과해도 `exp`가 없거나 `sub`가 없고 비어 있는 JWT는 거부한다. RSA·전환용 HMAC 모두 같은 필수 claim 검증을 적용하며 정상 토큰·만료 토큰 회귀 검증을 유지한다.
- Document 알파 분석 이벤트(업로드·편입·PDF 변환·질의·AI 편집/복구·명시적 스킬 실행·채팅 export)를 트랜잭션 outbox에 기록하고 HTTP batch로 재전송한다. 기본 비활성, 공개 phc_ 토큰만 허용하며 원문/URL을 제외한 이벤트별 허용 속성과 고정 UUIDv5/UTC 시각을 사용한다.
- JDBC SAVEPOINT로 분석 INSERT 오류를 제품 커밋과 격리하고, query_id terminal guard 및 복구 성공/실패 논리 키로 중복·상호 충돌을 막는다. 이전 팀 문서와 재편입의 최초 성공 이력, 원본 문서 인용, 웹 인용 수, 요청 세션 연결을 보존한다.
- 채팅 export_id/mode를 저장·응답하고 다음 편입에 연결한다. 자동 초기 노트는 업로드 지표에서 제외하고 AI 생성/채팅/스킬 참고 문서 origin을 구분한다. 스킬 메타데이터 조회는 짧은 timeout으로 best-effort 처리해 제품 실행을 막지 않는다.

- PostgreSQL/Redis/MinIO Testcontainers 전체 1,406개 테스트를 통과했다. 실제 SQL 제약 위반 후 제품 커밋 유지, terminal 경합, HTTP 재시도 payload 동일성, 실패 복구 후 성공 및 최초 편입 계보를 검증했다.

- 운영 JWT를 RS256 공개키 검증으로 전환하여 Document가 Access 토큰을 발급할 수 없게 했다. `JWT_PUBLIC_KEY`와 `JWT_KEY_ID`가 필수이며, 키 교체 시 이전 공개키/ID를 함께 검증한다.
- 기존 HMAC 토큰은 `JWT_SECRET`과 절대 만료 시각 `JWT_LEGACY_UNTIL`을 모두 명시한 경우에만 최대 24시간 전환 기간 동안 검증한다. 전환 후 두 값을 제거해야 한다.
- 로컬/테스트용 HMAC 발급 헬퍼는 유지하되 개발 환경에서도 `JWT_SECRET`을 명시하도록 기본 공유 비밀값을 제거했다.
- 신뢰하지 않는 키, 알고리즘 혼동, 발급자/대상/만료 불일치, 취약한 RSA 키, 운영 발급 금지 및 전환 만료를 테스트한다.

## 2026-10-31 보안 수정 통합

- 인코딩된 내부 API 경로의 토큰 검사, 삭제된 대화의 늦은 결과 종결, 폴더 복원 권한, 이미지·음성 저장 보상과 파기 경합, 회의 연결·녹음 멤버십 재검사를 보강한다. 최신 main의 RSA·자동 병합 계약을 유지한다.
- 기존 수정의 격리 회귀 검증을 수행했고 최신 main 통합본은 CI에서 전체 회귀를 확인한다.
