-- 단가 조회 경로와 모델 전환을 청구 행에 남긴다(#99).
-- requested_model: AI 원장의 요청 모델. 재계산이 AI 원장을 다시 조회하지 않고 같은 순서로 단가를 찾는다.
-- price_model: 단가를 찾은 모델 이름. 실제 응답 모델(model) → 요청 모델(requested_model) 순으로 찾는다.
-- model_routing: same(같은 모델) | snapshot(요청 모델에 스냅샷·버전 접미사만 붙음) | routed(공급사가 다른 모델로 보냄).
-- 이 마이그레이션 전 행은 requested_model을 모르므로 세 열이 NULL이다.
ALTER TABLE usage_charges
    ADD COLUMN requested_model TEXT,
    ADD COLUMN price_model TEXT,
    ADD COLUMN model_routing TEXT CHECK (model_routing IN ('same', 'snapshot', 'routed'));

-- 매시간 재계산이 금액 미확정 행만 훑는다.
CREATE INDEX idx_usage_charges_open ON usage_charges (id) WHERE status <> 'charged';
