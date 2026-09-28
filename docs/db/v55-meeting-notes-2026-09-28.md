# 회의록 초안 V55 배포 검증

## 변경

> 번호 변경: 처음 `V53__add_meeting_notes.sql`로 만들고 아래 검증을 회의 테이블(당시 V52) 위에서 했다. dev에 V52·V53이 들어와 번호가 겹쳐, 회의 테이블은 V54, 이 마이그레이션은 내용 그대로 **V55**로 옮겼다. 아래 표의 V52·V53은 당시 번호다. 새 번호로 다시 확인한 결과는 문서 끝 "번호 변경 후 재검증"에 있다.

`V55__add_meeting_notes.sql`은 `meeting_notes` 테이블 하나를 새로 만든다(`meetings` FK, 회의 삭제 cascade). 기존 테이블을 변경·잠금하지 않고 backfill도 없다(`migration_mode: expand-only`). 회의 테이블 마이그레이션(V54)이 먼저 적용돼 있어야 한다. 계약은 [Meetings API](../api/meetings.md#회의록-초안과-저장), 설계는 [ADR-0023](../adr/0023-meeting-transcripts-and-recordings.md)을 따른다.

## 배포 제약

- runtime role(`core_runtime`)에 `meeting_notes` SELECT/INSERT/UPDATE/DELETE 권한이 필요하다. 로컬에서는 platform `init-db-isolation.sh`의 default privileges로 자동 부여됐다.
- 새 앱은 `MEETING_NOTES_ENDPOINT` 설정이 필요하다(기본 `http://localhost:8000/meeting-notes/preview`).
- 롤백: 회의 실시간 받아쓰기 버전 앱은 이 마이그레이션이 적용된 DB에서 그대로 동작한다(아래 검증). `meeting_notes`에는 회의 발언 요약이 들어가므로 테이블을 지울 때는 데이터를 먼저 처리한다.

## 로컬 호환성 검증 (2026-09-28)

개발 DB는 사용하지 않았다. 별도 컨테이너를 만들었다.

- PostgreSQL 16: 127.0.0.1:15433. platform 초기화 스크립트로 `core_migration`·`core_runtime` 분리.
- Redis·MinIO: 격리 컨테이너.

| 순서 | 대상 | 결과 |
|---|---|---|
| 1 | V52 버전(회의 실시간 받아쓰기 브랜치) `--migrate-only` | V1~V52 적용 |
| 2 | V52 앱으로 회의 데이터 생성 | 회의 생성 201. 연결·확정 구간은 `core_runtime`으로 직접 추가(받아쓰기 결과 대신). 회의 조회 200 |
| 3 | 새 버전 `--migrate-only` | V53 success, 실행 9ms. `core_runtime`에 `meeting_notes` DELETE·INSERT·SELECT·UPDATE 권한 확인 |
| 4 | 새 버전 앱 | 기존 회의 조회 200(`transcript_complete=true`, 구간 유지). 초안 없음 404. 검증용 내부 토큰으로 AI 호출 시 503(`MEETING_NOTES_UNAVAILABLE`), 실패 버전 기록 후 최신 초안 조회 200 |
| 5 | 롤백: V53 DB에서 V52 앱 | 기동 정상(Flyway 검증 오류 없음), 회의 조회 200 |

## 실제 AI 연결 (같은 날, 별도 격리 DB)

로컬 ai-svc(`localhost:8000`, 실제 모델)와 access(8081)의 실제 OWNER 멤버십을 사용했다(멤버십은 읽기만, 데이터는 격리 DB에만 저장).

1. 기존 Markdown 문서를 만들고, 그 문서를 대상으로 회의를 만들었다.
2. 실시간 받아쓰기로 한국어 합성 음성 2문장을 확정·저장했다(`finished`, segment_count 2).
3. 초안 생성 200, 약 20초. 네 항목 모두 실제 구간 ID를 근거로 가리켰고, document가 만든 본문에는 근거 ID가 없었다.
4. `append-preview` → `apply(append)` 200. 문서 revision 1 → 2, 기존 본문 뒤에 빈 줄 하나를 두고 회의록이 붙었다. 같은 `Idempotency-Key` 재시도 200, revision 2 유지(중복 추가 없음).
5. 다른 회의로 `apply(create, display_name="출시 회의록")` 200, 새 문서에 회의록 본문 저장.

AWS 경로는 검증하지 않았다. 버전·재시도·충돌·거절 규칙은 가짜 AI 서버 통합 테스트(`MeetingNotesIntegrationTest`)로 검증했다.
