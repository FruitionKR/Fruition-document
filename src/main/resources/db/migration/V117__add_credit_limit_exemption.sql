-- 적용 전 가입자 명단은 access DB에서 내보내 별도로 반영한다. AI를 쓴 적 없는 가입자도 포함해야 한다.
-- 잔액 제한만 면제하며 사용량·예약·정산은 계속 기록한다.
ALTER TABLE credit_accounts ADD COLUMN limit_exempt BOOLEAN NOT NULL DEFAULT false;
