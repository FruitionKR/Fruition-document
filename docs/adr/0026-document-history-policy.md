# ADR-0026: 문서 자동저장과 이력 번호 분리

- 상태: 적용됨
- 관련: [ADR-0016](0016-consolidate-document-body-into-postgres.md), [이력 API 계약](../api/documents/history.md), [V58 배포 검증](../db/v58-document-history-policy-2026-10-07.md)

## 맥락

자동저장마다 복원용 이력을 만들면 공백 변경에도 버전이 계속 증가한다.
저장 충돌 검사와 AI 되돌리기에 필요한 편집 revision은 유지하면서, 사용자가 조회하는 이력 번호와 기록 시점을 분리한다.

## 저장 정책

최신 Markdown은 기존 자동저장 요청마다 PostgreSQL `document_edit_states`에 저장한다.
본문이 달라지면 내부 `revision`이 증가한다. 저장 충돌 검사, write receipt와 outbox는 이 번호를 계속 사용한다.

복원용 이력은 `document_content_versions`에 별도로 기록한다.

| 기록 종류 | 생성 조건 |
|---|---|
| initial | 첫 변경 저장 또는 첫 AI 적용에서 초기 본문 보존 |
| manual | 마지막 이력 기록 후 10분 경과 + 마지막 이력과 현재 본문 해시가 다름 |
| before_ai | 승인된 AI 적용 직전 revision의 이력이 없을 때 |
| ai | 서버가 검증한 AI 적용 성공. 동일 본문도 기록 |
| before_restore / restore | 복원 직전 미기록 revision과 실제 변경된 복원 결과 |
| before_convert / convert | 변환 저장 직전 미기록 revision과 PDF 변환 완료 시점의 이력 |
| legacy | V58 이전 이력. 기존 번호와 본문 보존 |

일반 저장에서 10분 조건을 검사하며, 서버 worker도 기본 10초마다 최대 100개의 기록 대상을 확인한다.
10분 기준을 넘긴 후 다음 worker 실행 또는 저장에서 기록되므로 실제 시각에 약간의 지연이 있을 수 있다.
내용 변경 없이 시간만 지나면 이력을 만들지 않는다. 편집 중지 후 2분 조건은 없다.
AI/복원 등으로 새 이력이 생성되면 그 기록 시각부터 일반 이력의 10분 기준을 다시 계산한다.
초기 본문과 AI 적용 전후 본문 보존은 일반 이력의 10분 제한에 해당하지 않는다.

AI 제안 거절·실패는 문서 이력을 만들지 않는다. 같은 저장 요청의 재전송은 write receipt를 재생하고
이력과 AI 로그를 다시 생성하지 않는다. 클라이언트가 `source=agent`나 복원처럼 보이는 write ID를
보내는 것만으로 즉시 이력을 만들 수 없다.

## 번호와 AI 되돌리기

`document_content_versions.version`은 문서별 이력이 생길 때 마지막 번호 + 1을 할당한다.
새 `revision` 컬럼은 해당 본문의 내부 편집 번호다. 번호 할당은 본문 저장과 같은 문서 행 잠금 안에서 수행한다.

예를 들어 이력 V2가 내부 revision 27, AI 결과 V3가 revision 28일 수 있다.
화면 이력 조회·비교는 version을 사용하고, AI 로그의 beforeRevision/afterRevision 조회는 revision을 사용한다.
AI 적용 직전 미기록 편집도 스냅샷으로 확보해 정확한 되돌리기를 유지한다.
동일 본문 AI 적용은 새 이력 번호만 부여하고 내부 revision은 유지한다. 이 작업은 기존 no-change 완료 처리를
유지하므로 되돌릴 본문 변경을 만들어내지 않는다. 여러 이력이 같은 revision에 연결되면 최신 이력을 조회한다.

AI 적용 표 소비, 본문 저장, 전후 이력, operation 연결과 감사 기록은 같은 transaction으로 처리한다.
AI 되돌리기도 본문·전후 이력·복원 로그를 같은 transaction에서 처리하고 실패 시 모두 롤백한다.
