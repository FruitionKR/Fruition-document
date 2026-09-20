# Document 데이터 모델

DB migration 원본은 `src/main/resources/db/migration/`입니다. 다른 서비스의 DB를 직접 수정하지 않습니다.

### core_db (document-svc)

- 활성 문서의 전체 파일명(확장자 포함)과 폴더명은 각각 워크스페이스 전체에서 고유하다. 폴더 위치가 달라도 중복을 허용하지 않는다. 앞뒤 공백 제거·Unicode NFC·소문자 변환 후 DB expression unique index로 비교한다(V48). 휴지통 항목은 이름을 점유하지 않으며, 복구 시 활성 이름과 충돌하면 전체 트랜잭션을 거절한다. 기존 중복은 적용 전에 별도로 검토해 정리해야 한다.

- `chat_messages.web_search_enabled`: 질의 요청의 `allow_web_search` 실행 시점 snapshot

| 테이블 | 소유 | 용도 | 핵심 컬럼/관계 |
|---|---|---|---|
| documents | document-svc | 원본 문서 업로드·처리 상태 | `status`, `content_hash`(일반 문서는 동일 값 허용), `pipeline_run_id`, `origin`(upload/chat_export), `pipeline_input_blocks`(chat_export의 문답 provenance). `chat_export`만 `(workspace_id, content_hash, selection_mode)` partial unique이고 읽기 전용이다 |
| document_edit_states | document-svc | canonical 최신 Markdown과 편집 CAS 상태 | PK/FK `document_id` → `documents(id)`(삭제 cascade), `markdown`, `content_hash`, `revision bigint > 0`, `created_at`, `updated_at`. `revision`이 HTTP 편집 version·동시 저장 CAS·event 순서 기준이며 `documents.current_version`은 lifecycle metadata다 |
| document_edit_writes | document-svc | `revision_write_id` 멱등 replay receipt | PK `(document_id, revision_write_id)`, FK document(삭제 cascade), `request_hash`, `result_revision > 0`, `result_content_hash`, `result_updated_at`, `actor_user_id`, `changed`, `created_at`. 같은 request hash는 replay하고 다른 payload는 conflict |
| document_edit_outbox | document-svc | 편집 이벤트 transactional outbox | PK `event_id`, `document_id`(문서 hard delete와 무관하게 발행 완료까지 보존), `workspace_id`, `revision > 0`, `content_hash`, `event_type=document.edit.saved.v1`, `schema_version > 0`, `created_at`, `published`, `published_at`. pending index `(created_at,event_id)`로 순서 발행하며 at-least-once |
| ai_command_outbox | document-svc | AI command의 transactional outbox | `run_id` UK, Kafka topic·key·payload |
| ai_operation_logs | document-svc | 문서·Wiki AI 작업 및 복구 감사 로그 | `target_display_name`은 작업 시작 시점 대상 이름 snapshot이다. `document_restore_blocked`는 V39 당시 기존 `document_edit`만 true로 표시하며 해당 감사 행의 복구를 차단한다. 새 작업과 ingest/lint는 false; 복구는 `restore_token_hash`(미리보기 토큰 SHA-256)와 `(restored_from, restore_token_hash)` partial unique로 동일 실행을 DB에서 1회만 선점 |
| ai_operation_changes | document-svc | 작업별 변경 리소스 감사 내역 | `resource_display_name`은 변경 시점 리소스 이름 snapshot이며 이후 Wiki rename/delete와 무관하게 유지된다. |
| ai_task_runs·ai_task_changes | document-svc | 공개 AI 작업 상태·업무 행의 변경 전후 값·복구 완료 기록 | run ID와 actor, trigger로 같은 트랜잭션에 기록; V47 |
| ai_task_result_receipts | document-svc | `ai.task.event` 멱등 반영 영수증 | `event_id` PK, `run_id`, `task_kind` |
| agent_apply_projections | document-svc | Markdown Agent 적용 예약·결과 projection | `run_id` PK, `apply_operation_id` UK, `base_version`, V33 `apply_revision_write_id`, V35 `ready_markdown`, queued→ready/failed→consumed. V36은 기존 ready를 backfill하고 복구 불가 건을 `failed`로 전환 |
| wiki_page_versions | document-svc | Wiki 본문 revision 이력 | 복합 PK `(page_id, revision)`, 페이지 ID는 ai_db 논리 참조 |
| wiki_page_contributions | document-svc | 복구용 ingest 기여 원장 | 복합 PK `(page_id, ingest_operation_id)`, 비활성화 이력 보존 |
| chat_sessions | document-svc | 채팅 세션(workspace당 10개) | `context_summary` |
| chat_messages | document-svc | 질의응답 메시지 | `pair_id`로 user·assistant 쌍 식별, user·assistant 모두 `ai_provider`·`ai_model`·`web_search_enabled` snapshot |
| agent_route_outcomes (view) | document-svc | Agent route 운영 평가 후보 | 기존 적용 projection과 채팅을 연결하며 편집 적용은 `accepted`, 실행 실패는 `technical_failure`, 나머지는 `unlabeled`로 노출 |
| chat_message_references | document-svc | 답변 근거 source block 스니펫 | chat_messages 1:N, `source_block_ids` |
| chat_message_related_pages | document-svc | 답변 관련 Wiki 페이지 목록 | chat_messages 1:N, `relevance_score`·`depth` |
| chat_partial_wiki | document-svc | 채팅 export 문답↔페이지 멤버십 | `UNIQUE(pair_id, wiki_page_id)` |
| document_assets | document-svc | 문서 첨부 이미지 metadata(바이너리는 MinIO) | `storage_key` UK, `content_hash`(ETag), `unreferenced_since`(정리 후보 판정). workspace_id·uploaded_by는 access_db 논리 참조(물리 FK 없음) |
| document_asset_references | document-svc | 문서 본문↔asset 참조 동기화 | 복합 PK `(document_id, asset_id)`, asset 삭제 RESTRICT — 참조 중 asset 보호 |
| document_asset_orphans | document-svc | storage 정리 실패 asset 재시도 큐 | `storage_key` UK, `retry_count`, cleanup worker가 소비 |
| wiki_lint_state | document-svc | workspace별 마지막 lint 성공 시각(needs_lint 판단 기준점) | PK `workspace_id`(access_db 논리 참조), `last_lint_at` |

V34는 `chat_export`에만 `(workspace_id, content_hash, selection_mode)` partial unique index를 추가한다.
채팅 export는 언제나 선택한 문답만 담은 새 문서라 `selection_mode`는 항상 `partial`이고, 같은 선택을 다시 내보내면
이 index가 기존 문서를 재사용하게 한다. 세션 전체를 위키에 누적하던 경로를 걷어내면서
`chat_sessions.wiki_page_id`·`chat_sessions.wiki_export_document_id`·`chat_messages.wiki_page_id`는 쓰지 않는 잔여 컬럼이 됐다
(코드 매핑만 제거했고 컬럼은 남아 있다).
V43은 `documents.pipeline_input_blocks`를 추가한다. 채팅 export는 문답 단위 블록(JSON 배열, `block_id =
session_id:pair_id`)을 여기에 보존하고, 완료 후처리가 이 값을 읽어 문답↔페이지 멤버십을 기록한다. 일반 문서
Ingest 경로는 이 필드를 쓰지 않고 block ID를 새로 부여하므로, 파이프라인이 돌려준 값은 provenance로 쓰지 않는다.
V44는 AI 작업 로그와 변경 항목에 실행 시점의 문서 표시 이름 스냅샷을 추가한다.
V45는 원문을 복제하지 않고 `agent_apply_projections`와 `chat_messages`를 연결하는
`agent_route_outcomes` view를 추가한다. 취소·재시도는 route 실패로 단정하지 않는다.

`chat_messages.progress`는 실제 진행 이벤트의 JSONB 배열입니다. `event_id`로 중복 반영을 막고, 최종 상태 이후의 이벤트는 추가하지 않습니다. SSE 캐시 만료 후에도 대화와 함께 보존됩니다.
