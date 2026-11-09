# 기존 가입자 면제와 베타 크레딧 제한

기존 가입자는 `credit_accounts.limit_exempt=true`로 잔액 부족 검사만 면제한다.
신규 계정의 기본값은 false다. 일반 AI 작업의 사전 승인·구간 갱신과 MCP 승인 모두 적용한다.
사용량, 예약, 정산은 계속 기록하므로 면제 계정의 잔액은 음수가 될 수 있다.
이는 외상 결제 약속이 아니라 내부 사용 기록이다. 잔액을 임의로 크게 지급하지 않는다.
업로드 크기·동시 실행·권한 등 기존 보호 장치는 유지한다. 일일 AI 토큰 한도는 기존·신규 계정 모두 기존 설정을 그대로 따른다.

## 적용 순서

1. 우선 **BILLING_ENFORCE=false를 유지한 채** document의 V117 migration과 면제 검사 코드를 배포한다.
   변경된 AWS overlay의 true 값은 이 단계에서 적용하지 않는다. 기존 MCP 변경들과도 배포 버전을 맞춘다.
2. 신규 가입을 잠시 중지하고, 진행 중인 가입 트랜잭션이 끝난 뒤 전환 시각을 시간대 포함으로 기록한다.
   아래 시각은 예시다. 실제 작업 시각으로 대체한다. 전환 중에도 기존 로그인은 유지할 수 있다.
3. access DB에서 가입자 전체를 CSV로 내보낸다. AI 사용 여부나 credit_accounts 존재 여부로 대상을 고르지 않는다.
   서비스 파일과 `.pgpass` 등 기존 DB 인증을 사용한다. 명령에 비밀번호를 넣지 않는다.

```bash
PGSERVICE=fruition-access psql -Xq -v ON_ERROR_STOP=1 \
  -v cutoff='2026-11-09T12:00:00+09:00' \
  -f scripts/sql/export-existing-credit-users.sql > existing-credit-users.csv
```

4. export 성공과 명단을 확인한 뒤 같은 디렉터리에서 core DB에 반영한다.
   실패한 export 파일을 import하지 않는다. 동일 파일·cutoff 재실행은 잔액을 바꾸거나 신규 사용자를 추가하지 않는다.

```bash
PGSERVICE=fruition-core psql -X -v ON_ERROR_STOP=1 \
  -v cutoff='2026-11-09T12:00:00+09:00' \
  -f scripts/sql/import-existing-credit-users.sql
```

5. snapshot 인원과 core의 대상 계정 면제 설정을 대조한다. 이력 확인을 위해 cutoff와 CSV를 접근 제한된 운영 기록에 보관한다.
6. AWS overlay의 BILLING_ENFORCE=true를 적용하고 document-svc rollout을 완료한다. 모든 Pod의 설정을 확인한다.
7. 면제 계정이 잔액 0에서도 작업을 시작·이어갈 수 있는지, 면제 없는 잔액 0 테스트 계정은 402인지 확인한다.
   제한은 요청 전 검사이므로 이미 시작한 작업이 정산되면 신규 사용자의 잔액도 음수가 될 수 있다. 정확한 API 원가 상한은 아니다.
8. 가입을 재개한다. 이후 신규 가입자는 기본 제한 대상이고, 선정된 50명에게만 기존 credit-grant-adjust.sql로 지급한다.

실패 시 가입을 재개하기 전에 BILLING_ENFORCE=false로 복구하고 document-svc를 재시작한다.
MCP는 전역 enforce=false와 무관하게 잔액을 검사하지만, 명시적으로 면제한 계정은 계속 통과한다.
면제 명단과 원장은 롤백을 위해 삭제하지 않는다.
