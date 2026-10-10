\set ON_ERROR_STOP on
-- 월별 공급사·모델 원가 합계(#78). 공급사 청구서와 비교한다. 월은 UTC 기준, 호출 시작 시각으로 나눈다.
-- 단가 없음(unpriced)·확인 필요(needs_review) 호출은 원가 합계에 빠지므로 건수를 함께 보고 차이를 설명한다.
-- 실행 예: psql -Xq --csv ... -f scripts/sql/usage-cost-monthly.sql > usage-cost-monthly.csv
SELECT to_char(date_trunc('month', started_at AT TIME ZONE 'UTC'), 'YYYY-MM') AS month,
       provider,
       model,
       count(*) AS calls,
       round(coalesce(sum(cost_usd_micro), 0) / 1000000.0, 6) AS cost_usd,
       round(coalesce(sum(charge_krw_milli), 0) / 1000.0, 3) AS charge_krw,
       count(*) FILTER (WHERE status = 'unpriced') AS unpriced_calls,
       count(*) FILTER (WHERE status = 'needs_review') AS needs_review_calls,
       coalesce(sum(input_tokens), 0) AS input_tokens,
       coalesce(sum(output_tokens), 0) AS output_tokens,
       coalesce(sum(audio_seconds), 0) AS audio_seconds,
       coalesce(sum(tts_characters), 0) AS tts_characters
FROM usage_charges
GROUP BY 1, 2, 3
ORDER BY 1 DESC, 2, 3;
