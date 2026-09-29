# 회의 녹음 원본 V56 배포 검증

## 변경

> 번호 변경: 처음 `V54__add_meeting_recordings.sql`로 만들고 아래 검증을 회의록 초안(당시 V53) 위에서 했다. dev에 V52·V53이 들어와 회의 테이블 V54, 회의록 V55로 옮겨졌고, 이 마이그레이션은 내용 그대로 **V56**으로 옮겼다. 아래 표의 V53·V54는 당시 번호다. 새 번호로 다시 확인한 결과는 문서 끝 "번호 변경 후 재검증"에 있다.

`V56__add_meeting_recordings.sql`은 `meetings`에 NULL 허용 컬럼 5개(`recording_key`, `recording_content_type`, `recording_bytes`, `error`, `claimed_at`)와 partial index `idx_meetings_transcribing`을 추가한다. 기본값·backfill이 없어 기존 행을 다시 쓰지 않는다(`migration_mode: expand-only`). 회의록 초안 마이그레이션(V55)이 먼저 적용돼 있어야 한다. 계약은 [Meetings API](../api/meetings.md#녹음-원본과-회의-삭제), 설계는 [ADR-0023](../adr/0023-meeting-transcripts-and-recordings.md)을 따른다.

## 배포 제약

- `meetings`의 기존 runtime 권한을 그대로 쓴다. 인덱스 생성은 `meetings` 크기에 비례해 짧게 쓰기를 막는다(신규 테이블이라 작다).
- 새 앱은 `SPEECH_TRANSCRIPTION_ENDPOINT`(AI 파일 전사) 설정과 S3/MinIO `meetings/` 접두사 쓰기·읽기·삭제 권한이 필요하다.
- 녹음 원본은 음성 개인정보다. 회의 삭제 전까지 보관하며 용량 한도는 없다(`recording_bytes`로 추후 적용).
- 롤백: 회의록 초안 버전 앱은 이 마이그레이션이 적용된 DB에서 그대로 동작한다(아래 검증). 새 컬럼은 이전 앱이 읽지 않는다.

## 로컬 호환성 검증 (2026-09-28)

개발 DB는 사용하지 않았다. 격리 컨테이너를 만들었다.

- PostgreSQL 16: platform 초기화 스크립트로 `core_migration`·`core_runtime` 분리
- Redis, MinIO

| 순서 | 대상 | 결과 |
|---|---|---|
| 1 | V53 버전(회의록 초안 브랜치) `--migrate-only` 후 앱 | V1~V53 적용, 회의 생성·조회 200 |
| 2 | 새 버전 `--migrate-only` | V54 success, 실행 15ms. `core_runtime`의 `meetings` DELETE·INSERT·SELECT·UPDATE 유지 |
| 3 | 새 버전 앱 | 기존 회의 조회 200 |
| 4 | 롤백: V54 DB에서 V53 앱 | 기동 정상(Flyway 검증 오류 없음), 기존 회의 조회 200, ticket 발급 200 |

## 실제 AI 연결 (같은 날, 격리 DB)

로컬 ai-svc(`localhost:8000`, 실제 모델)와 access(8081)의 실제 OWNER 멤버십을 사용했다(멤버십은 읽기만, 데이터는 격리 DB·MinIO에만 저장).

1. 녹음 파일 회의를 만들고 27초 한국어 m4a를 올렸다: `202`, `transcribing`.
2. 작업자가 약 11초 만에 전사해 4문장 구간(`s1_seg_0001`~`0004`)으로 저장하고 `open`, `transcript_complete=true`가 됐다.
3. 그 전사로 회의록 초안 200(약 17초) → 새 문서 저장 200.
4. 재생 주소 GET 200(`audio/mp4`), 받은 파일이 올린 원본과 같았다.
5. 회의 삭제 204: 회의·초안 행 0건, MinIO 원본 삭제 확인. 저장한 회의록 문서는 남았다.

AWS 경로(S3·IRSA·ALB)는 검증하지 않았다. 상태 전이·재업로드·실시간 회의 원본·거절 규칙은 `MeetingRecordingIntegrationTest`(실제 MinIO, 가짜 AI 서버)로 검증했다.

## 번호 변경 후 재검증 (2026-09-28)

dev(`6060879`, V52·V53 포함)를 합친 뒤 격리 DB에서 다시 확인했다.

| 순서 | 대상 | 결과 |
|---|---|---|
| 1 | dev `--migrate-only` 후 앱 | V1~V53 적용, Markdown 문서 생성 201 |
| 2 | 녹음 원본 브랜치(회의·회의록·녹음 포함) `--migrate-only` | V54 `add meetings`, V55 `add meeting notes`, V56 `add meeting recordings` 순서대로 success. `core_runtime`에 `meetings`·`meeting_streams`·`meeting_segments`·`meeting_notes` DELETE·INSERT·SELECT·UPDATE |
| 3 | 새 버전 앱 | 기존 문서 목록 200, 회의 생성·조회 200, 초안 없음 404 |
| 4 | 롤백: V56 DB에서 dev 앱 | 기동 정상(Flyway 검증 오류 없음), 문서 목록 200 |
