# Document 알파 분석 계약

Document는 제품 트랜잭션 안에서 `analytics_outbox`에 이벤트를 저장한다. 별도 relay가 커밋된 행만 HTTP `/batch/`로 보낸다. 기본값은 `ANALYTICS_ENABLED=false`, `POSTHOG_PROJECT_TOKEN`은 빈 문자열이며, 토큰이 비거나 공개 phc_ 프로젝트 토큰이 아니면 relay가 실행되지 않는다. 잘못된 environment는 분석을 비활성화하고 제품은 계속 시작한다. `POSTHOG_HOST` 기본값은 `https://us.i.posthog.com`, `ANALYTICS_ENVIRONMENT`는 `local`/`staging`/`production`(기본 local), `APP_VERSION` 기본값은 `unknown`이다.

## 트랜잭션과 재전송

- `AnalyticsRecorder`는 제품 트랜잭션의 JDBC connection에 SAVEPOINT를 만들고, 분석 SQL이 실패하면 그 지점까지 rollback한다. JPA repository나 `@Transactional` 프록시를 savepoint 안에서 호출하지 않는다. 제품 트랜잭션이 rollback되면 이벤트도 사라진다.
- UUID는 RFC 4122 UUIDv5이며 namespace는 NAMESPACE_URL, name은 `fruition.analytics.v1:{event}:{logicalKey}`다. `uuid`와 `properties.event_id`가 같다. `timestamp`는 UTC 발생 시각을 저장하며 재전송으로 변경하지 않는다.
- relay는 50개씩 `FOR UPDATE SKIP LOCKED`로 잡고 HTTP 성공 응답 후 sent_at을 기록한다. 10초 HTTP timeout과 지수 backoff(최대 1시간), 12회 실패 후 dead_at을 사용한다. 프로세스 종료·응답 유실 시 같은 payload를 다시 보낼 수 있으므로 분석 SQL에서도 event_id로 중복 제거한다.
- 운영 복구 시 dead 행의 원인을 먼저 해결한 뒤 attempts=0, dead_at=NULL, next_attempt_at=now()로 재시도할 수 있다. payload/event_id/occurred_at은 바꾸지 않는다. 이 문서 작성 중 실제 전송이나 운영 데이터 변경은 하지 않았다.

## 이벤트

`document_uploaded`, `wiki_ingest_requested`, `wiki_ingest_completed`, `pdf_convert_completed`, `query_answered`, `query_failed`, `ai_edit_proposed`, `ai_edit_decided`(accepted), `ai_operation_rolled_back`, `skill_run`, `chat_exported`, `ai_task_failed`, `ai_document_created`, `meeting_notes_completed`를 기록한다. `skill_created`는 AI 서비스의 생성 트랜잭션이 소유한다.

모든 이벤트는 원래 요청 사용자 distinct_id, 대상 workspace 그룹과 일반 workspace_id 속성, schema_version=1, event_source=document_server, environment, app_version을 가진다. 속성은 `AnalyticsSchema`의 이벤트별 필수·선택 허용 목록을 통과해야 한다. 이름·제목·질문·답변·본문·URL·오류 원문을 보내지 않는다. 실패 코드는 제한된 failure_reason 값으로만 변환한다.

- 업로드는 user_upload/chat_export/skill_reference/ai_generated origin으로 구분한다. 직접 업로드 완료와 사용자의 Markdown 생성도 같은 기록 경로를 사용한다. 가입 시 자동으로 생기는 초기 빈 노트는 사용자 업로드로 세지 않는다.
- 편입의 trigger(auto/manual), source(upload/chat/existing_document), operation_id와 export_id를 실행 접수 시 저장한다. 이 요청 provenance는 분석이 꺼져 있어도 보존한다. source는 원본 계보의 origin으로 정한다: chat_export → chat, upload/convert/convert_part → upload, 직접 만든 사용자/AI 노트 → existing_document. 재편입 여부로 source를 바꾸지 않는다. pages_changed는 OperationApplier가 실제 기록한 위키 페이지 수다.
- 마이그레이션 이전에 이미 실행 중이던 편입은 trigger를 알 수 없어 완료 이벤트를 추측해 만들지 않고 경고한다. 해당 성공은 아래 최초 편입 이력에는 포함된다.
- PDF는 마지막 batch가 합쳐지거나 최종 실패가 확정될 때만 기록한다. document_id는 원본 PDF, conversion_id는 convert 실행 ID이며 이전 변환 placeholder 존재 여부로 is_reconvert를 판정한다. 사용자 취소는 실패로 집계하지 않는다.
- query_answered/query_failed는 별도 `analytics_query_terminals(query_id PRIMARY KEY)`에서 상호 배제한다. AI receipt와 독립적이며 terminal guard와 outbox INSERT는 같은 savepoint 안에 있다. 취소는 기록하지 않는다.
- 질의의 query_requested_at은 접수 UTC 시각이다. has_citations/citation_count에는 내부 근거와 웹 근거가 모두 들어간다. internal_citation_count는 저장된 근거 수, web_citation_count는 web: 근거 수이며, related_pages는 인용으로 세지 않는다.
- 작업 탭은 payload.action=chat_answer 결과만 query 이벤트로 기록한다. 실패도 AI가 이 분류를 보존해야 한다. 분류되지 않은 agent 실패를 임의로 질문 실패에 넣지 않는다.
- `ai_task_failed`는 질문 답변이 아닌 AI 작업의 최종 실패다. task_kind는 agent_turn(작업 탭 결과 실패 stage=result, 결과 검증 실패 stage=validation), edit_apply(AI 편집 수락 후 적용 충돌, stage=apply, failure_reason=conflict, task_id는 제안 run_id), transcription(회의 받아쓰기), sync_call(사용자가 기다리는 동기 AI 호출: 채팅 음성 받아쓰기·답변 음성 읽기·Skill 요청, action은 run kind)이다. sync_call은 `UsageChargeService.failRun`이 실행을 실제로 닫을 때 별도 트랜잭션으로 함께 기록하며, 사용자가 듣다가 멈춘 음성 읽기는 기록하지 않는다. logicalKey는 `{task_kind}:{task_id}`이고 취소는 기록하지 않는다. action은 알려진 agent action만 보내고 그 밖의 값은 unknown이다.
- 작업 탭 초안으로 Markdown 문서를 만들 때 요청의 agent_run_id가 요청자 본인의 markdown_create run이면 문서를 AI 생성 문서(ai_origin=agent)로 만들고 `document_uploaded(origin=ai_generated)`와 `ai_document_created(proposal_id, document_id)`를 기록한다. 맞지 않으면 일반 문서로 만든다.
- `meeting_notes_completed`는 회의록 초안이 generating에서 ready/failed로 실제로 바뀔 때와 시간 초과 정리 때 한 번 기록한다. logicalKey는 AI run ID라 시간 초과 뒤 늦게 온 결과는 기록하지 않는다. 사용자 취소는 기록하지 않는다.
- `X-PostHog-Session-Id`가 UUID이면 접수 시 저장해 결과의 `$session_id`에 연결한다. 본문이나 URL 같은 임의 문자열은 받지 않는다.
- 편집은 replace+whole_document → rewrite, insert_after → append, replace+selection/current_section → replace로 분류한다. accepted의 proposal_id는 apply_operation_id에 연결된 projection에서 가져온다.
- 복구의 logicalKey는 성공 `success:{원래 operation_id}`, 실패 `failure:{restore_attempt_id}`다. restore_requested_at과 실제 완료 timestamp를 구분하고, manifest의 excludedOperationIds에서 지목 ID를 제외한 연쇄 ID를 보낸다.
- explicit skill 실행은 기존 제품 접수 경로를 유지한다. 분석이 켜져 있을 때만 AI get 응답의 scope_type(personal/team)와 created_by(첫 버전 생성자)를 best-effort로 조회한다(연결 500ms, 응답 1초 제한). created_by와 요청자 비교로 is_creator를 만든다. 조회 실패나 필수 메타데이터 누락 시 제품 실행은 계속하고 정제된 경고와 함께 skill_run만 생략한다. 완전한 수집을 위해 AI metadata 응답 변경을 먼저 배포한다.

## 오래된 문서와 팀 문서의 최초 편입

`analytics_document_origins` 뷰는 변환 파트 → 변환 문서 → 원본 PDF를 재귀적으로 연결한다. 변환 계보가 없는 Markdown은 그 문서 자체가 원본이다. `analytics_first_ingests`는 모든 사용자의 성공 ai_operation_logs에서 원본 문서별 completed_at 최소값을 구한다. 알파 이전 기록과 팀원의 기록도 포함하고 재편입해도 최초 시각을 바꾸지 않는다.

query_answered의 `cited_document_ids`는 저장된 reference 및 source_refs에서 얻은 중복 없는 내부 원본 ID 배열이다. 삭제된 문서와 다른 workspace 문서는 제외한다. `cited_document_first_ingested_at`은 `{ "원본 문서 ID": "UTC ISO-8601 최초 성공 시각" }` 객체다. 성공 이력이 없으면 그 ID의 map 항목만 생략한다. wiki_ingest_completed에도 알려진 `first_success_ingested_at`을 넣는다. 묵은 지식 계산은 query_requested_at과 이 map을 우선 비교하면 별도의 가짜 과거 이벤트/backfill 전송이 필요 없다.

## 채팅 export C6

요청은 기존 pair_ids 외 선택 필드 export_id(UUID), mode(all/selected)를 받는다. 생략하면 서버가 UUID를 생성하고 mode=selected로 취급한다. 응답에 export_id를 돌려주며 같은 ID를 다른 내용·사용자·대화·mode로 재사용하면 거절한다.

`chat_export_links`에 export_id/mode/문서/대화/사용자를 제품 트랜잭션으로 저장한다. 후속 ingest는 같은 문서·요청자의 가장 최근 export를 우선 연결하고, 다른 팀원이 편입하면 원래 export 링크를 사용한다. C6 이전에 만들어져 링크가 없는 문서에는 legacy:{document_id}로 만든 안정 UUID를 사용한다. 이 legacy ID는 새로운 frontend export_started와 연결된 실제 사용자 행동을 의미하지 않는다.

실제 내보낸 완전한 문답 수가 pair_count다. 중복 내용의 export도 새 요청 ID를 유지하고 status=skipped로 기록한다. 같은 export_id 재시도는 outbox에서 중복 제거한다.
