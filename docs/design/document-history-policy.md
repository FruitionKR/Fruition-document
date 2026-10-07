# 문서 자동저장과 이력 번호 분리

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
| convert | 기존 PDF 변환 완료 시점의 이력 유지 |
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

## API 계약과 필요한 프론트 연결

이번 작업의 코드 변경 범위는 Fruition-document다. 프론트 이력 화면은 다음 계약에 맞춰 연결해야 한다.

| 응답/요청 | 번호 의미 |
|---|---|
| 문서 상세 `edit_revision` | 현재 내부 편집 revision, 기존 계약 유지 |
| 본문 저장 응답 `current_version` | 기존 필드 유지: 내부 편집 revision |
| 본문 저장 응답 `current_revision` | 위 값과 동일한 명시적 필드 |
| 이력 목록 `current_version` | 최신 본문과 일치하는 최신 이력 번호. 미기록 편집이 있거나 이력이 없으면 null |
| 이력 목록 `current_revision` | 최신 편집 revision. 복원 충돌 검사에 사용 |
| 이력 항목 `version`, `revision`, `record_type` | 이력 번호, 원본 편집 revision, 기록 종류 |
| `GET .../diff?from_version=N&to_version=M` | 두 이력 번호 비교 |
| `GET .../diff?from_version=N&to_version=0` | 이력 N과 미기록 편집을 포함한 최신 본문 비교 |
| `POST .../versions/N/restore` | 복원 대상 N은 이력 번호 |
| 복원 body `base_revision` (기존 `base_version`도 허용) | 직전에 조회한 목록의 current_revision. 이력 번호를 보내지 않는다 |

필요한 최소 프론트 변경:

1. 이력 응답에서 `current_revision`을 보관하고, 복원 요청의 충돌 검사 값에 사용한다.
2. `current_version`이 null일 때도 `to_version=0`으로 최신 본문과 비교하고 복원할 수 있게 한다.
3. 목록의 current_version은 현재 배지에만 사용한다. 마지막 이력 번호를 최신 revision으로 취급하지 않는다.
4. 자동저장 응답의 기존 current_version은 revision으로 유지되므로 기존 저장 흐름은 그대로 동작한다.

기존 프론트는 이력 목록의 current_version을 복원 base 값으로도 사용하므로, 번호 분리 뒤 프론트 수정 없이
배포하면 콘텐츠 이력 화면의 복원이 409로 실패하거나 미기록 본문 비교가 표시되지 않을 수 있다.
서버에서 현재 revision을 임의로 대입해 충돌 검사를 우회하지 않는다. AI 로그의 되돌리기는 별도의 revision
경로로 처리하므로 이 프론트 이력 화면의 계약과 독립적이다.

## 기존 데이터와 배포

V58은 기존 행에 `revision = version`을 채운다. 기존 version, operation_id와 restored_from_version은 유지한다.
기존 번호를 재정렬하거나 이력을 삭제하지 않는다. 새 번호는 기존 마지막 version에서 이어진다.
DB 마이그레이션과 새 백엔드를 함께 배포해야 한다. 이전 백엔드는 새 revision 필드를 기록하지 않으므로
이 변경의 전후 백엔드를 동시에 운영하는 rolling 배포는 지원하지 않는다.
프론트 이력 화면의 계약 수정도 배포 전에 맞춰야 한다. 운영 DB에는 이번 작업에서 마이그레이션을 실행하지 않는다.

과거 이력 본문에 남은 관리 이미지는 미참조 asset 정리에서 제외한다. Markdown에 asset 경로가 남아 있는지
보수적으로 검사하므로 텍스트로 적힌 같은 경로도 보존될 수 있고, 큰 이력량에서는 정리 조회 비용이 증가한다.

## 검증

- 일반 공백 편집, 6분 뒤 재편집, 10분 경계, 같은 내용 및 편집 후 원상복구의 이력 정책.
- revision/version이 분리된 AI 적용·비교·일반 후속 편집·AI 되돌리기.
- 동일 본문 AI 작업과 저장 재전송의 중복 방지.
- 기존 복원 트랜잭션 롤백 및 transient 재시도.
- 동시에 실행된 worker의 중복 방지와 서버 측 미기록 변경 기록.
- V57 → V58 기존 이력·AI 연결·복원 출처 보존.
- 과거 이력에 포함된 이미지의 정리 방지.
- OpenAPI 스냅샷과 전체 백엔드 테스트.
