# ADR-0027: AI 사용량 과금 규칙과 단가 관리

- 상태: 결정됨 (구현: [PR #103](https://github.com/FruitionKR/Fruition-document/pull/103), 경보: [Fruition-flatform#76](https://github.com/FruitionKR/Fruition-flatform/pull/76))
- 관련: [#93](https://github.com/FruitionKR/Fruition-document/issues/93), [#98](https://github.com/FruitionKR/Fruition-document/issues/98), [#99](https://github.com/FruitionKR/Fruition-document/issues/99), [#78](https://github.com/FruitionKR/Fruition-document/issues/78), [ADR-0026](0026-credit-payments.md)

## 맥락

document는 ai-svc 사용량 원장(`GET /internal/model-usage/calls`)을 호출 단위로 읽어 `usage_charges`에 청구액을 계산하고, 그 금액으로 선불 크레딧을 정산한다(#78). 과금 감사에서 다음 문제가 확인됐다.

- **단가 키 불일치(#99):** 원장의 `model`은 공급사 응답의 실제 모델명이다. OpenAI는 `gpt-5-nano-2025-08-07` 같은 날짜 스냅샷 이름을 돌려주는데, 단가 조회는 `provider = ? AND model = ?` 완전 일치였다. 단가표 키(`gpt-5-nano`)와 다르면 그 호출은 조용히 `unpriced`로 남았고, 단가를 나중에 넣어도 다시 계산하는 경로가 없어 영구히 청구되지 않았다.
- **과금 단위 이중 계산(#93):** ai는 TTS 호출에 토큰 usage와 `input_characters`를, 실시간 전사에 토큰과 `audio_seconds`를 함께 기록한다. 계산식은 단위별 비용을 더했으므로, 단가 행에 두 단위가 다 있으면 이중 청구, 한쪽 단가만 있으면 `unpriced`가 됐다. 분당 과금 모델은 토큰 단가 열이 NOT NULL이라 0으로 저장되는데, 토큰 우선 규칙을 쓰면 0원 `charged`가 된다.
- **사용량 없는 성공 호출(#98):** 공급사가 usage를 주지 않아 토큰이 NULL인 `succeeded` 호출을 0원 `charged`로 확정했다. `charged`는 다시 계산하지 않으므로 영구히 0원이다.
- **단가 관리 경로 없음:** 단가 행은 마이그레이션으로 시드되지 않고 운영자가 SQL로 넣었다. 카탈로그에 모델을 추가하거나 교체할 때([ADR-0028](0028-ai-model-catalog-replacement.md)) 단가 행이 빠져도 알 수 없었다.
- **공급사의 모델 자동 전환:** Gemini는 폐기한 모델 요청을 다른 모델로 돌린다(#86). 요청 모델과 다른 모델이 응답되면 다른 단가가 적용돼야 하는데, 이를 감지하는 수단이 없었다.

업계 관행을 참고했다. OpenRouter와 Cursor는 공급사의 토큰 단가를 그대로(또는 고정 마진을 얹어) 사용자에게 넘기는 pass-through 방식을 쓰고, GitHub Copilot도 고정 요청 수 방식에서 사용량 기반 과금으로 옮겼다. 공통점은 **공급사 단가표를 원천으로 두고, 실제 응답된 사용량을 호출 단위로 계산**한다는 것이다. 이 결정도 같은 방향을 따른다.

## 결정

### 1. 단가 조회: 응답 모델 → 요청 모델 순서 (#99)

단가는 응답 `model`과 정확히 일치하는 행에서 먼저 찾고, 없으면 `requested_model` 행을 쓴다. 둘 다 없으면 `unpriced`다. 별칭 테이블은 두지 않는다. `usage_charges`에 `requested_model`, 단가를 찾은 이름 `price_model`, 분류 `model_routing`(`same` / `snapshot` / `routed`)을 저장한다(V69).

- `snapshot`: 요청 모델 뒤에 `-` 다음 날짜(`\d{4}-\d{2}-\d{2}`, `\d{8}`), 세 자리 버전, `latest` 중 하나만 붙은 경우. 같은 모델이므로 요청 모델 단가를 쓴다.
- `routed`: 그 밖에 요청과 응답 모델이 다른 경우. 공급사가 다른 모델로 돌렸다는 뜻이다.

### 2. 과금 단위는 단가 행이 정하고, 단위를 더하지 않는다 (#93)

한 호출은 단위 하나로만 계산한다.

1. 단가 행에 `audio_usd_per_minute`가 있고 호출에 `audio_seconds`가 있으면 분당 단가로만 계산한다.
2. 아니고 `tts_usd_per_mchar`와 글자 수가 있으면 글자 단가로만 계산한다.
3. 그 밖에는 토큰으로만 계산한다. 토큰 없이 오디오 길이나 글자 수만 있는데 해당 단가가 없으면 `unpriced`다.

ai 응답의 TTS 글자 수는 계약대로 `input_characters`를 읽는다.

### 3. 사용량을 모르는 성공 호출은 `needs_review` (#98)

`succeeded`인데 입력·출력 토큰, 오디오 길이, 글자 수가 모두 NULL이면 0원 `charged`로 확정하지 않고 `needs_review`로 둔다.

### 4. `unpriced`·`needs_review`는 매시간 다시 계산한다 (#99)

`UsageChargeService.recompute`가 매시간(`USAGE_CHARGE_RECOMPUTE_INTERVAL_MS`) 최근 90일의 `succeeded` 호출 중 `unpriced`·`needs_review` 행을 `usage_charges`에 저장된 값으로 다시 계산한다. ai 원장은 다시 조회하지 않는다. 500행씩 `FOR UPDATE SKIP LOCKED`로 잠가 여러 replica가 같은 행을 중복 처리하지 않는다. `charged` 행은 건드리지 않는다. 새로 `charged`가 된 실행은 수집과 같은 `CreditService.settle` 경로로 정산한다. 90일은 단가 누락을 발견하고 고칠 시간으로 충분하면서, 오래된 호출을 뒤늦게 청구해 사용자를 놀라게 하지 않는 경계다.

### 5. 단가는 코드로 관리한다

- **시드:** 카탈로그의 모든 모델 단가를 Flyway 마이그레이션으로 넣는다(V70, `effective_from`, `ON CONFLICT DO NOTHING`). 조회는 `effective_from <= started_at`인 행 중 가장 늦은 행이므로, 예정된 인상(예: Gemini 3.6·3.8 Flash의 2027-01-01 인상)도 미리 넣어 둔다. 운영자가 더 늦은 시각으로 넣은 행은 그대로 우선한다.
- **CI 가드:** `AiModelCatalogPriceIntegrationTest`가 Flyway로 만든 DB에서 `AiModelCatalog`의 모든 모델에 지금 적용 중인 단가가 있는지 확인한다. 카탈로그에 모델을 추가하면서 단가를 빼먹으면 빌드가 실패한다.
- **주간 점검:** `.github/workflows/ai-price-drift.yml`이 매주 `scripts/ai_price_drift.py`를 실행해 시드 단가를 LiteLLM 단가표와 비교하고, OpenAI·Gemini 폐기 페이지에 카탈로그 모델이 올라왔는지 본다. 차이가 있으면 이슈 하나를 만들거나 갱신한다. 결과는 사람이 보고 마이그레이션으로 반영한다.

### 6. 자동 전환과 단가 누락은 경보로 알린다 (Fruition-flatform#76)

document는 `[AI 모델 전환 감지] provider=%s requested=%s actual=%s`와 `[AI 단가 미등록] provider=%s model=%s requested=%s`를 WARN으로 남긴다. 같은 (provider, 모델) 조합은 프로세스마다 UTC 하루에 한 번만 남긴다. Fruition-flatform이 이 두 접두사로 CloudWatch metric filter와 경보(1일 합계 ≥ 1)를 만들어 운영 채널로 보낸다. 접두사는 계약이므로 바꾸지 않는다.

## 대안과 기각 사유

- **응답 모델 완전 일치만 쓰고 별칭 테이블을 둔다:** 스냅샷 이름이 나올 때마다 별칭 행을 손으로 넣어야 한다. 넣기 전 호출은 `unpriced`로 남는다. 요청 모델로 한 번 더 찾으면 대부분의 스냅샷을 추가 데이터 없이 덮는다.
- **토큰 우선 규칙(토큰이 있으면 토큰으로만):** 처음 제안한 방식이다. 분당 과금 모델은 토큰 단가가 0으로 저장되므로 토큰과 `audio_seconds`가 함께 기록되면 0원 `charged`가 된다. 과금 단위는 호출이 아니라 공급사 단가 체계가 정하므로 단가 행이 정하게 했다.
- **단위별 비용을 더한다(기존 동작):** 같은 사용량을 두 단위로 기록하는 ai 원장에서는 이중 청구가 된다.
- **운영자가 단가를 수동 입력한다(기존 방식):** 카탈로그 변경과 단가 입력이 따로 움직여 누락을 알 수 없다. 이번 감사에서도 새 기본 모델의 단가 행이 있는지 배포 전에 손으로 확인해야 했다(#86).
- **공급사 가격 페이지를 스크래핑해 자동 반영한다:** 페이지 구조가 바뀌면 조용히 틀린 값이 들어간다. 프로모션 단가, long context 구간, 모달리티별 단가처럼 표에 주석으로 붙는 조건을 기계가 해석하기 어렵다. 과금 금액을 사람 검토 없이 바꾸는 것도 위험하다.
- **LiteLLM 단가표를 원천으로 삼는다:** 커뮤니티가 관리하는 데이터라 반영이 늦거나 틀릴 수 있고, 우리 계약 단가(배치·지역 할증 등)와 다를 수 있다. 원천은 공급사 공식 가격표로 두고, LiteLLM은 차이를 찾는 비교 대상으로만 쓴다.
- **0원 `charged`를 허용하고 나중에 수동 정정한다(#98 기존 동작):** `charged`는 재계산 대상이 아니라 정정 경로가 없다. 금액을 모르면 확정하지 않는 쪽이 안전하다.

## 결과

- 스냅샷 이름 응답이 `unpriced`로 새지 않는다. 단가를 늦게 넣어도 90일 안의 호출은 다음 재계산에서 청구된다.
- 같은 사용량이 두 번 청구되지 않는다. 대신 분당·글자 과금 모델은 단가 행에 해당 열이 있어야 하며, 없으면 토큰 단가로 계산된다.
- 카탈로그와 단가표가 CI로 묶인다. 모델을 추가하려면 단가 마이그레이션도 같이 넣어야 한다.
- 표현하지 못하는 단가가 남는다: OpenAI 272K 초과 long context, Gemini Pro 200k 초과 구간, Gemini 오디오 입력 단가, Anthropic 1시간 캐시 쓰기·지역 할증, 모달리티별 토큰 단가(`gpt-realtime` 계열). 기본 구간 단가로 계산되거나 시드하지 않는다(PR #103 참고).
- 기간 정산(`UsageSettlementService`, #59)은 아직 응답 모델 단가와 토큰만으로 계산한다. 같은 규칙으로 맞추는 일은 남아 있다.
- 마진·부가세·환율 정책은 이 ADR 범위 밖이다. 정책이 없으면 `[AI 환율·정책 없음]` 로그와 함께 `unpriced`가 된다.
