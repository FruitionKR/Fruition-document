# Backend 변경 기록

## 2026-10-22

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
