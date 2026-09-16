-- 실패로 끝난 복구는 Wiki를 하나도 바꾸지 못했는데도 미리보기 토큰 선점을 붙들고 있었다.
-- 토큰이 계획의 결정적 해시라, 이 행들이 남아 있으면 해당 복구를 영영 다시 시도할 수 없다.
UPDATE ai_operation_logs
   SET restore_token_hash = NULL
 WHERE operation_type = 'restore'
   AND status = 'failed'
   AND restore_token_hash IS NOT NULL;
