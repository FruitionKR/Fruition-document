# ADR-0030: 위키 관리 권한 항목을 document가 판정한다

- 상태: 채택
- 관련: Fruition-ai [ADR 0033](https://github.com/FruitionKR/Fruition-ai/blob/main/docs/adr/0033-workspace-shared-wiki.md)(위키 공유 자산·편입 정책), Fruition-access ADR 0019(권한 항목·자동 편입 설정, [FruitionKR/Fruition-access#32](https://github.com/FruitionKR/Fruition-access/pull/32))

## 맥락

위키가 워크스페이스 공유 자산이 되면서(ai ADR 0033) 편입·정리·되돌리기는 팀 전체 위키를 바꾸고 LLM 비용을 쓴다. access는 멤버별 권한 항목(`wiki.ingest`, `wiki.lint`, `skill.author`, `wiki.restore`, `document.convert`)과 `auto_ingest_on_upload` 설정을 저장하고 내려 주기만 하며, 판정은 document가 한다(access ADR 0019). 그 전까지 document는 멤버 여부(`requireMember`)만 보았고, PDF 변환이 끝나면 업로더 이름으로 무조건 자동 편입했다.

## 결정

### 1. 권한 읽기

- `WorkspaceAccessGuard`는 새 Redis 키 `authz:role:v2:{ws}:{uid}`(TTL 300초)에 값을 JSON `{"role":"MEMBER","permissions":["wiki.ingest"]}`로 둔다. 옛 키 `authz:role:{ws}:{uid}`는 구버전 pod가 평문 역할로 읽으므로 JSON을 쓰면 비멤버가 통과하게 된다. 그래서 키를 분리하고 옛 키는 읽지도 쓰지도 않는다(access는 두 키를 모두 비운다). 키는 document Redis 사용자의 기존 ACL 패턴 `authz:role:*` 안에 일부러 두었다(새 prefix·`del` 권한 불필요).
- 캐시 값이 JSON이 아니거나 `role`이 없거나 `OWNER`/`MEMBER`/`NONE`이 아니면 miss로 보고 내부 API 결과로 `set` 덮어쓴다. 내부 API 응답이 같은 검증을 통과하지 못하면 `WorkspaceNotFoundException`으로 fail-closed 한다.
- access 내부 API 응답에 `permissions`가 없으면(옛 access) 빈 목록으로 본다.
- 역할과 권한은 한 번의 캐시 조회(miss면 한 번의 내부 API 호출)로 함께 읽는다.
- `getRole`·`isOwner`·`requireMember`는 그대로 두고 `hasPermission`·`requirePermission`을 더했다. OWNER는 항상 허용, NONE은 거절이다.
- 멤버가 아니면 기존 `WORKSPACE_NOT_FOUND`(404)이고, 멤버인데 항목이 없으면 `403 WORKSPACE_PERMISSION_REQUIRED`다. 메시지에 필요한 항목을 적는다.

### 2. 판정 위치

| 기능 | 규칙 |
|---|---|
| 수동 편입(`POST /documents/{id}/ingest`, 채팅 내보내기 문서 포함) | `wiki.ingest` |
| 위키 정리(`POST /wiki/maintenance/lint`, `dry_run` 포함) | `wiki.lint`. 상태·실행 조회는 멤버면 가능 |
| 팀 Skill 작성·게시(`scope_type=team`) | `skill.author`. 개인 Skill은 필요 없음 |
| 팀 Skill 수정·삭제·활성화·비활성화 | `skill.author`. 개인 Skill은 필요 없음 |
| 위키 작업(ingest·lint)의 복구 미리보기·실행 | 작업 실행자 본인, OWNER, `wiki.restore` 중 하나. 복구 불가 유형은 권한과 무관하게 먼저 400 |
| PDF 변환 요청 | 원본 PDF 업로더 본인, OWNER, `document.convert` 중 하나 |
| AI 작업 취소·상태 조회 | 요청자 본인 또는 OWNER |

- **Skill**: 활성화·비활성화는 사용자별 설정이 아니라 ai의 `skills.status`를 바꾸므로 팀 Skill이면 모든 멤버에게 영향을 준다. 그래서 수정·삭제와 같이 판정한다. 기존 Skill의 범위는 ai의 Skill 조회 응답의 `scope_type`으로 확인한다. `scope_type`이 정확히 `personal`이 아니면(누락 포함) `skill.author`를 요구한다(fail-closed). ai도 같은 항목으로 팀 Skill 관리를 판정한다(Fruition-ai#94, `dev` 머지됨). ai가 그 변경 전이면 `skill.author`를 받은 MEMBER는 document 판정을 통과해도 ai에서 거절된다.
- **복구**: `RestorePreviewService.loadOperation`(미리보기와 실행이 공유)에서 판정한다. `document_edit` 이외의 작업 유형은 위키 작업으로 본다. 복구 가능한 유형은 `document_edit`·`ingest`·`lint`뿐이라 사실상 ingest·lint다. 문서 편집 복구는 기존 규칙(복구하는 사람의 문서 편집 권한)을 유지한다.
- **취소·조회**: `AiTaskCancellationService`의 공개 `cancel`·`status`만 OWNER를 허용한다. 요청자가 OWNER면 작업 행의 `user_id`로 소유 판정을 대신하고, 이후 ai 호출에는 처음부터 행의 workspace·user를 넘긴다. 내부 복구 endpoint(`changes`·`undo`·`finalizeEdits`), `cancelDocumentTasks`, 에이전트의 `findStatus`는 바꾸지 않았다.

### 3. 자동 편입

- 업로드·변환 뒤 자동 편입은 `auto_ingest_on_upload`(access `GET /internal/workspaces/{ws}/settings`)가 켜져 있고 **요청자에게 `wiki.ingest`가 있을 때만** 한다. 조건이 하나라도 아니면 편입하지 않고 문서는 "위키 미반영" 상태로 남는다.
- 설정 조회 결과는 프로세스 안에서 60초 캐시한다(실패는 캐시하지 않는다). 그래서 설정을 바꾸면 최대 60초 늦게 반영된다.
- 설정 조회나 권한 판정에 실패하면 WARN을 남기고 편입하지 않는다. 업로드·변환은 막지 않는다.
- 자동 편입하는 곳(요청자):
  - PDF 변환 완료, AWS 묶음 경로(`doConvertBatches`)와 비AWS 단일 경로(`applyConvertedMarkdown`) 모두 — 변환을 요청한 사용자
  - Markdown·txt 업로드(`upload`) — 업로더. 직접 업로드 완료(`DocumentDirectUploadService.complete`)도 `upload`를 거치지만 PDF만 받으므로 편입 대상이 없다.
- 자동 편입하지 않는 곳: 빈 새 노트(`createMarkdown`), 회의록 저장, 채팅→위키 내보내기, 에이전트가 만든 문서, 스킬 참고 문서, PDF 원본 업로드 자체.
- 수동 편입과 같은 편입 등록(`enqueueIngest`, `IngestCommandOutbox`)을 쓴다.
  - **Markdown 업로드**: 업로드 트랜잭션이 커밋된 **뒤** 별도(REQUIRES_NEW) 트랜잭션에서 `reopenForReingest`와 편입 등록을 한다. 업로드한 파일이 곧 본문이라 원본은 다시 쓰지 않는다. 등록이 실패하면 그 트랜잭션만 롤백되어 고아 행이 남지 않고, WARN을 남기며 업로드는 이미 성공한 상태다. 문서는 `uploaded`(신규)로 남아 사용자가 수동 편입할 수 있다. 커밋과 편입 등록 사이에 프로세스가 죽어도 같은 상태가 된다. 업로드 응답의 상태는 `uploaded`이고 편입은 그 뒤에 시작된다.
  - **PDF 변환**: 변환 완료 트랜잭션 안에서 outbox에 쓰므로 문서 커밋과 편입 요청이 함께 확정된다.
- 설정·권한 판정의 내부 HTTP 호출은 항상 DB 트랜잭션 밖에서 한다. 변환은 **변환이 끝나는 시점**(AWS 경로는 마지막 묶음 완료 직후, 비AWS 경로는 변환 결과 수신 직후)에, 업로드는 커밋 직후 편입 트랜잭션을 열기 전에 판정한다. 변환 도중 권한이 회수되면 편입하지 않는다.

## 대안과 기각 사유

- **역할 키와 권한 키를 따로 두기**: 역할과 권한을 따로 읽으면 캐시·내부 API 호출이 두 번이다. 새 키 하나에 JSON으로 함께 넣어 한 번에 읽는다.
- **같은 키에 JSON을 덮어쓰기**: 롤링 배포 중 구버전 pod가 JSON을 평문 역할로 읽어 비멤버가 통과한다. 새 키 `authz:role:v2:`로 분리했다.
- **Skill 활성화·비활성화를 권한 없이 두기**: 사용자별 토글이 아니라 공유 Skill 상태를 바꾸므로 팀 Skill 작성 권한을 우회하는 통로가 된다. 기각.
- **취소·조회 소유 조건을 `owned()` 안에서 바꾸기**: `owned()`는 내부 복구 경로도 쓴다. 그 경로까지 OWNER 시점으로 바뀌면 ai 호출에 넘기는 사용자가 달라진다. 공개 진입점에서만 소유자를 정한다.
- **자동 편입을 변환 후 별도 작업으로 미루기**: outbox가 이미 트랜잭션 안에서 쓰이므로 별도 큐는 복잡도만 늘린다.

## 배포 순서

access #32(권한 항목 내부 API·설정 API)를 이 변경보다 먼저 배포해야 한다. 그렇지 않으면 멤버의 권한이 모두 빈 목록으로 읽히고, 설정 조회가 404가 되어 자동 편입이 꺼진 것으로 처리된다.

## 결과

- 권한 없는 멤버는 편입·정리·팀 Skill 관리·남의 위키 작업 복구·남의 PDF 변환에서 `403 WORKSPACE_PERMISSION_REQUIRED`를 받는다. 프런트는 `code`로 분기한다.
- 이 변경이 배포되면 기본 설정(자동 편입 꺼짐)에서는 업로드·변환 뒤 위키에 자동으로 들어가지 않는다. 수동 편입 UI는 이미 있다.
- 비AWS 변환 경로도 AWS 경로와 같은 정책으로 편입한다(이전에는 편입하지 않았다).
- `skill.author`가 MEMBER에게 실제로 효과를 내려면 ai가 Fruition-ai#94 이후 버전이어야 한다.
