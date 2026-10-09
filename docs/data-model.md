# Document 데이터 모델

DB migration 원본은 `src/main/resources/db/migration/`입니다. 다른 서비스의 DB를 직접 수정하지 않습니다.

### core_db (document-svc)

- 활성 문서의 전체 파일명(확장자 포함)과 폴더명은 각각 워크스페이스 전체에서 고유하다. 폴더 위치가 달라도 중복을 허용하지 않는다. 앞뒤 공백 제거·Unicode NFC·소문자 변환 후 DB expression unique index로 비교한다(V48). 휴지통 항목은 이름을 점유하지 않으며, 복구 시 활성 이름과 충돌하면 전체 트랜잭션을 거절한다. 기존 중복은 적용 전에 별도로 검토해 정리해야 한다.
- 문서를 휴지통으로 옮기면(문서·폴더 삭제) 같은 트랜잭션에서 `document_deleted` command를 `ai_command_outbox`에 넣어 AI가 해당 문서의 source 페이지를 위키에서 뺀다. 같은 트랜잭션에서 그 문서의 `wiki_page_contributions`를 `active = false`(`deactivated_by` 없음)로 꺼서, 로그 되돌리기가 삭제한 문서의 source 페이지나 개념 기여를 다시 살리지 않게 한다. 개념 페이지는 유지한다. 편집 문서는 `status = uploaded`로 되돌려 복구 후 다시 편입하게 한다. 이 규칙 이전에 휴지통에 들어간 문서는 V52가 한 번 정리 command를 발행하고 기여를 껐다.

- `chat_messages.web_search_enabled`: 질의 요청의 `allow_web_search` 실행 시점 snapshot

| 테이블 | 소유 | 용도 | 핵심 컬럼/관계 |
|---|---|---|---|
| documents | document-svc | 원본 문서 업로드·처리 상태 | `status`, `content_hash`(일반 문서는 동일 값 허용), `pipeline_run_id`, `origin`(upload/direct/chat_export/skill_reference 등), `pipeline_input_blocks`(chat_export의 문답 provenance). `updated_by`(마지막으로 본문을 저장한 사용자. 공동 편집에서 소유자 `user_id`와 다를 수 있음; V62). `original_sha256`(원본 PDF 파일 전체 SHA-256. 서버를 거친 업로드는 업로드 때, 직접 업로드·기존 문서는 `DocumentOriginalHashWorker`가 채운다. NULL은 계산 전, 빈 문자열은 원본 객체 없음; V61). `chat_export`만 `(workspace_id, content_hash, selection_mode)` partial unique이고 읽기 전용이다. `skill_reference`는 스킬 참고 문서로, 트리 이름 공간(`document_tree_names`)에 들어가지 않고 `uq_documents_skill_reference_name`(워크스페이스·정규화 파일명, 활성 참고 문서만)으로 이름을 따로 막는다 |
| document_permissions | document-svc | 문서·폴더 공동 편집 권한 설정 | `document_id`(FK documents, 삭제 cascade)와 `folder_id`(FK folders, 삭제 cascade) 중 하나만, `access`(`edit`/`view`), `workspace_id`, `updated_by`, `updated_at`. 설정이 없으면 기본 `edit`. 문서는 자기 설정 → 가장 가까운 상위 폴더 설정 순으로 따르고, 문서 소유자와 OWNER는 항상 편집 가능; V62 |
| document_edit_states | document-svc | canonical 최신 Markdown과 편집 CAS 상태 | PK/FK `document_id` → `documents(id)`(삭제 cascade), `markdown`, `content_hash`, `revision bigint > 0`, `created_at`, `updated_at`. `revision`이 HTTP 편집 version·동시 저장 CAS·event 순서 기준이며 `documents.current_version`은 lifecycle metadata다 |
| document_edit_conflicts | document-svc | 같은 revision에서 갈라진 편집 본문 보존·OWNER 해결 | `document_id`(FK documents, 삭제 cascade), `workspace_id`, `base_revision > 0`, `markdown`, `content_hash`, `author_user_id`, `client_conflict_id`(`(document_id, client_conflict_id)` UNIQUE로 재전송은 기존 행 반환), `status`(`open`/`resolved`), `resolution`(`server`/`conflict`/`merged`, 미해결이면 NULL), `resolved_by`, `resolved_revision`, `created_at`, `resolved_at`. 해결해도 지우지 않아 고르지 않은 충돌 본이 남고, 서버 본은 `document_content_versions`에 있다. 미해결 partial index `(workspace_id, created_at)`; V63 |
| notifications | document-svc | 앱 안 알림 | `id` uuid, `workspace_id`, `audience`(`user`/`workspace_owners`), `recipient_user_id`(`audience=user`일 때만 값이 있고 `workspace_owners`면 NULL), `type`(`edit_conflict_registered`/`edit_conflict_resolved`), `payload` jsonb, `created_at`. OWNER 대상 알림은 한 행만 넣고 조회 시점에 요청자가 OWNER인지로 거른다. 인덱스 `(workspace_id, created_at DESC)`; V64 |
| notification_reads | document-svc | 사용자별 알림 읽음 | PK `(notification_id, user_id)`, `notification_id`(FK notifications, 삭제 cascade), `read_at`. OWNER 대상 알림도 OWNER마다 따로 읽는다; V64 |
| document_edit_writes | document-svc | `revision_write_id` 멱등 replay receipt | PK `(document_id, revision_write_id)`, FK document(삭제 cascade), `request_hash`, `result_revision > 0`, `result_content_hash`, `result_updated_at`, `actor_user_id`, `changed`, `created_at`. 같은 request hash는 replay하고 다른 payload는 conflict |
| document_edit_outbox | document-svc | 편집 이벤트 transactional outbox | PK `event_id`, `document_id`(문서 hard delete와 무관하게 발행 완료까지 보존), `workspace_id`, `revision > 0`, `content_hash`, `event_type=document.edit.saved.v1`, `schema_version > 0`, `created_at`, `published`, `published_at`. pending index `(created_at,event_id)`로 순서 발행하며 at-least-once |
| ai_command_outbox | document-svc | AI command의 transactional outbox | `run_id` UK, Kafka topic·key·payload |
| ai_operation_logs | document-svc | 문서·Wiki AI 작업 및 복구 감사 로그 | `target_display_name`은 작업 시작 시점 대상 이름 snapshot이다. `document_restore_blocked`는 V39 당시 기존 `document_edit`만 true로 표시하며 해당 감사 행의 복구를 차단한다. 새 작업과 ingest/lint는 false; 복구는 `restore_token_hash`(미리보기 토큰 SHA-256)와 `(restored_from, restore_token_hash)` partial unique로 동일 실행을 DB에서 1회만 선점 |
| ai_operation_changes | document-svc | 작업별 변경 리소스 감사 내역 | `resource_display_name`은 변경 시점 리소스 이름 snapshot이며 이후 Wiki rename/delete와 무관하게 유지된다. |
| ai_task_runs·ai_task_changes | document-svc | 공개 AI 작업 상태·업무 행의 변경 전후 값·복구 완료 기록 | run ID와 actor, trigger로 같은 트랜잭션에 기록; V47. V58 `resume_claimed_by`·`resume_claimed_until`: 취소 진행을 한 Pod만 하도록 선점(3분 lease) |
| ai_task_result_receipts | document-svc | `ai.task.event` 멱등 반영 영수증 | `event_id` PK, `run_id`, `task_kind` |
| agent_apply_projections | document-svc | Markdown Agent 적용 예약·결과 projection | `run_id` PK, `apply_operation_id` UK, `base_version`, V33 `apply_revision_write_id`, V35 `ready_markdown`, queued→ready/failed→consumed. V36은 기존 ready를 backfill하고 복구 불가 건을 `failed`로 전환 |
| ai_model_prices | document-svc | AI 모델 단가 이력(USD / 1M tokens) | PK `(provider, model, effective_from)`, 단가 변경은 새 행 추가; V59. V65 `audio_usd_per_minute`·`tts_usd_per_mchar`(NULL이면 그 사용량은 단가 없음) |
| ai_usage_settlements | document-svc | 마감한 AI 사용량 정산 | `result` jsonb에 계산 결과 고정, `(workspace_id, from_at)` 인덱스; V59 |
| fx_rates | document-svc | USD→KRW 고정 환율 이력 | PK `effective_from`, `krw_per_usd > 0`. 수정하지 않고 새 행 추가; V65 |
| pricing_policies | document-svc | 마진·부가세율 이력 | PK `effective_from`, `margin_bp`·`vat_bp`(basis point, ≥ 0). 수정하지 않고 새 행 추가; V65 |
| usage_charges | document-svc | AI 호출 단위 청구 | `call_id`(AI 원장 행 id) UNIQUE, `user_id`, `workspace_id`, `run_id`, `kind`, `provider`, `model`, `call_status`, 토큰 5종, `audio_seconds`, `tts_characters`, 적용 버전 `price_/fx_/policy_effective_from`, `cost_usd_micro`, `charge_krw_milli`, `status`(`charged`/`unpriced`/`needs_review`, `unpriced`면 `charge_krw_milli` NULL), `started_at`(버전 선택 기준). `charged` 행은 다시 계산하지 않는다. `(user_id, started_at)`·`(started_at)` 인덱스; V65 |
| credit_accounts | document-svc | 사용자 선불 크레딧 계정 | PK `user_id`, `balance`(음수 가능), `reserved ≥ 0`, `updated_at`. 원장 합계와 같아야 한다(`scripts/sql/credit-balance-check.sql`). 탈퇴 파기 대상 아님(결제 기록 5년 보관); V66 |
| credit_entries | document-svc | 크레딧 원장(추가만) | `user_id`(FK credit_accounts), `type`(`purchase`/`grant`/`reserve`/`release`/`charge`/`refund`/`adjust`), `amount` milli-KRW(`charge`·`release`·`refund` ≤ 0, `adjust` ±, 나머지 ≥ 0. V67에서 `refund`를 결제 환불 회수로 보고 음수로 바꿈), `run_id`, `idempotency_key` UNIQUE(run_id 기반 예약·차감·해제 멱등), `reason`(`adjust`면 필수), `created_at`. 탈퇴 파기 대상 아님; V66 |
| credit_orders | document-svc | 크레딧 충전 주문(PG 결제) | PK `order_id`, `user_id`, `product_code`, `amount_krw > 0`, `credit_milli > 0`(서버 상품표 값), `status`(`created`/`paid`/`failed`/`refunded`/`partially_refunded`), `pg_payment_key` UNIQUE, `failure_code`, `refunded_krw`·`refunded_credit_milli`(누적, 주문 금액 이하), `created_at`, `paid_at`, `refunded_at`. 탈퇴 파기 대상 아님(결제 기록 5년 보관); V67, ADR-0026 |
| payment_events | document-svc | 서명 검증한 PG webhook 수신 기록 | PK `event_id`(본문 `eventId`, 없으면 본문 SHA-256)로 멱등, `body_sha256`, `event_type`, `order_id`, `received_at`. 원문은 저장하지 않는다; V67 |
| usage_collect_queue | document-svc | 호출 사용량 수집 대기열 | PK `run_id`, `attempts`, `available_at`. 실행 종료 시 넣고 worker가 SKIP LOCKED로 선점, 수집 후 삭제; V65 |
| usage_reconcile_cursor | document-svc | 종료 시각 구간 대사 진행 위치 | 단일 행 `reconciled_to`. 1시간 넘게 지난 구간까지 하루치씩 진행; V65 |
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
| meetings | document-svc | 회의 받아쓰기 단위(만든 사람만 조회) | `created_by`, `document_id`(회의록 저장 대상, 논리 참조), `source`(live/upload), `status`(open/awaiting_upload/transcribing/failed). V56: `recording_key`·`recording_content_type`·`recording_bytes`(원본, 용량 한도 대비), `error`(파일 전사 실패 사유), `claimed_at`(파일 전사 작업자 선점). `status='transcribing'`이 전사 대기열이다. V54·V56 |
| meeting_streams | document-svc | 받아쓰기 연결 한 번 | `(meeting_id, stream_order)` UK, `end_reason`(finished/interrupted/failed, NULL=진행 중). 회의 삭제 cascade. V54 |
| meeting_segments | document-svc | 확정 전사 구간 | PK `(meeting_id, id)`, `(meeting_id, position)` UK. `id`=`s{stream_order}_{AI 구간 ID}`, `position`은 AI `committed` 순서, `text` NULL=확정 전(끊겼으면 누락). V54 |
| meeting_notes | document-svc | 회의록 초안 버전과 저장 기록 | PK `(meeting_id, version)`, `(meeting_id, generation_request_id)` UK(생성 재시도), `status`(generating/ready/failed/applied), `segment_snapshot`(AI에 보낸 구간), `result`(이름·네 배열, AI markdown 제외), `apply_*`(저장 전에 기록하는 대상·본문·기준 revision). 회의 삭제 cascade. V55 |
| wiki_lint_state | document-svc | workspace별 마지막 lint 성공 시각(needs_lint 판단 기준점) | PK `workspace_id`(access_db 논리 참조), `last_lint_at` |

V34는 `chat_export`에만 `(workspace_id, content_hash, selection_mode)` partial unique index를 추가한다.
채팅 export는 언제나 선택한 문답만 담은 새 문서라 `selection_mode`는 항상 `partial`이고, 같은 선택을 다시 내보내면
이 index가 기존 문서를 재사용하게 한다. 세션 전체를 위키에 누적하던 경로를 걷어내면서
`chat_sessions.wiki_page_id`·`chat_sessions.wiki_export_document_id`·`chat_messages.wiki_page_id`는 쓰지 않는 잔여 컬럼이 됐다
(코드 매핑만 제거했고 컬럼은 남아 있다).

회의 실시간 받아쓰기는 Redis에 두 키를 둔다. `speech:ticket:{ticket}`은 WebSocket 접속용 일회용 ticket(사용자·workspace·회의, 60초, 접속 시 `GETDEL`)이고, `speech:live:{meeting_id}`는 회의당 연결 1개를 보장하는 잠금(90초 TTL, 연결 중 30초마다 연장)이다. 녹음 원본은 기존 버킷의 `meetings/{meeting_id}/recording-{uuid}.{ext}`(업로드마다 새 키)에 두고 회의 삭제 전까지 보관한다. 재업로드로 교체된 이전 원본과 상태 변경에 실패한 업로드 객체는 바로 지우며, 회의 삭제 시 원본을 먼저 지운다. 잠금이 없는데 `meeting_streams.end_reason`이 NULL인 연결은 기록 전에 인스턴스가 종료된 것으로 보고 조회 시 `interrupted`로 반환한다.
V43은 `documents.pipeline_input_blocks`를 추가한다. 채팅 export는 문답 단위 블록(JSON 배열, `block_id =
session_id:pair_id`)을 여기에 보존하고, 완료 후처리가 이 값을 읽어 문답↔페이지 멤버십을 기록한다. 일반 문서
Ingest 경로는 이 필드를 쓰지 않고 block ID를 새로 부여하므로, 파이프라인이 돌려준 값은 provenance로 쓰지 않는다.
V44는 AI 작업 로그와 변경 항목에 실행 시점의 문서 표시 이름 스냅샷을 추가한다.
V45는 원문을 복제하지 않고 `agent_apply_projections`와 `chat_messages`를 연결하는
`agent_route_outcomes` view를 추가한다. 취소·재시도는 route 실패로 단정하지 않는다.

`chat_messages.progress`는 실제 진행 이벤트의 JSONB 배열입니다. `event_id`로 중복 반영을 막고, 최종 상태 이후의 이벤트는 추가하지 않습니다. SSE 캐시 만료 후에도 대화와 함께 보존됩니다.
