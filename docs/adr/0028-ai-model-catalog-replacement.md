# ADR-0028: AI 모델 카탈로그 교체 정책

- 상태: 결정됨 (구현: [PR #100](https://github.com/FruitionKR/Fruition-document/pull/100), [Fruition-access#26](https://github.com/FruitionKR/Fruition-access/pull/26))
- 관련: [#86](https://github.com/FruitionKR/Fruition-document/issues/86), [Fruition-access#24](https://github.com/FruitionKR/Fruition-access/issues/24), [Fruition-ai#68](https://github.com/FruitionKR/Fruition-ai/issues/68), [ADR-0027](0027-ai-usage-billing-and-price-management.md)

## 맥락

`AiModelCatalog`는 사용자가 워크스페이스에서 고를 수 있는 AI 모델 목록이다. document와 access가 같은 목록을 갖고, `catalog[0]`이 기본값이다(프론트는 저장값이 없으면 첫 항목을 고른다). 2026-10-10 공급사 공식 문서를 대조한 결과, 38개 중 10개가 종료 예정이거나 다른 모델로 자동 전환되고 있었다(#86).

- 종료일이 공지된 모델: `o4-mini`·`gpt-4.1-nano`(2026-10-23), `gpt-5-nano`·`gpt-5`·`gpt-5-mini`·`o3`(2026-12-11, 이름이 가리키는 스냅샷이 하나뿐이라 그날부터 호출 실패), `gpt-5.4-nano`(2027-04-01), `gemini-3.1-flash-lite`(2027-05-07)
- 자동 전환 중인 모델: `gemini-3.7-flash` → `gemini-3.8-flash`, `gemini-3.5-flash` → `gemini-3.6-flash`
- 두 기본값(`gpt-5-nano`, `gemini-3.1-flash-lite`)이 모두 여기에 들어 있었다.

첫 감사 표는 대체 모델 열을 폐기 모델로 잘못 읽어 `gpt-5.6-*`를 제거 대상에 넣었고, 문서 재확인으로 정정했다. 판단 기준을 정해 두지 않으면 같은 일이 반복된다. 또 모델을 빼면 저장된 워크스페이스 설정과 아직 발행하지 않은 AI command가 빠진 모델을 가리키므로, 어디로 옮길지도 정해야 한다.

## 결정

### 1. 제거 기준: 종료일 공지 또는 자동 전환

공급사가 **종료일을 공지**했거나 요청을 **다른 모델로 자동 전환**하는 모델은 카탈로그에서 뺀다. 폐기 페이지의 "권장 대체 모델" 열에만 나오는 모델은 대상이 아니다. **종료일이 없는 preview 모델은 남긴다**(`gemini-3-flash-preview`는 대체 모델이 지정됐지만 종료일이 없어 유지). 빠진 모델로 오는 새 요청은 400 "선택할 수 없는 AI 모델입니다."로 거절한다.

자동 전환을 빼는 이유는 사용자가 고른 모델과 실제로 응답한 모델, 그리고 적용되는 단가가 달라지기 때문이다([ADR-0027](0027-ai-usage-billing-and-price-management.md)의 `routed` 감지).

### 2. 이관 규칙: 같은 등급의 가장 싼 현행 모델

저장된 워크스페이스 설정(access `workspaces`, V24)과 미발행 `ai_command_outbox` payload(document V68, V40과 같은 방식)를 같은 provider, 같은 등급에서 가장 싼 현행 모델로 옮긴다.

| 빼는 모델 | 옮길 모델 |
|---|---|
| `gpt-5-nano`, `gpt-4.1-nano`, `gpt-5.4-nano` | `gpt-6-luna` |
| `gpt-5-mini`, `o4-mini` | `gpt-5.4-mini` (GPT-6에 Luna와 Sol 사이 등급이 없어 mini 계보의 현행 모델) |
| `gpt-5`, `o3` | `gpt-6.1-sol` |
| `gemini-3.1-flash-lite` | `gemini-3.5-flash-lite` |
| `gemini-3.7-flash` | `gemini-3.8-flash` |
| `gemini-3.5-flash` | `gemini-3.6-flash` |

채팅·실행 기록에 남은 옛 모델 이름은 표시용 이력이라 그대로 둔다.

### 3. 기본값

OpenAI `gpt-6-luna`(`DEFAULT_MODEL`, 카탈로그 첫 항목), Gemini `gemini-3.5-flash-lite`(Gemini 첫 항목, access `workspaces` 컬럼 기본값). `catalog[0]` 계약은 유지한다.

### 4. 교체는 한 묶음으로 배포한다

document와 access 카탈로그는 같아야 하고, ai-svc는 사용자가 고른 모델로 호출해야 한다(Fruition-ai#68). 세 서비스를 같은 날, 가장 이른 종료일 전에 배포한다. 배포 전에 새 모델 단가 행이 있는지 확인한다. 이 확인은 이제 CI 가드가 맡는다([ADR-0027](0027-ai-usage-billing-and-price-management.md)). 이후 폐기 일정은 주간 점검 워크플로가 폐기 페이지를 보고 이슈로 알린다.

## 대안과 기각 사유

- **공급사 공식 대체 모델로 옮긴다:** 공급사 지정이라 설명하기 쉽지만 비용이 크게 오른다. 출력 단가 기준 `gpt-5-nano` → `gpt-5.6-luna` ×3.0, `o4-mini` → `gpt-5.6-terra` ×2.7, `o3` → `gpt-5.6-sol` ×2.5다(#86 단가 비교). 사용자가 아무것도 바꾸지 않았는데 비용이 2.5~3배가 된다.
- **provider 기본값으로 모두 옮긴다:** 가장 단순하지만 상위 모델(`o3`, `gpt-5`)을 고른 사용자가 경량 모델로 떨어져 품질이 내려간다. 사용자가 고른 등급을 유지해야 한다.
- **종료일까지 카탈로그에 두고 그날 뺀다:** 종료일에 맞춰 배포해야 하고, 그 사이 새로 고르는 사용자가 생긴다. 공지 시점에 빼면 새 선택을 막고 이관을 한 번에 끝낼 수 있다.
- **폐기 표시가 있는 모든 모델과 preview를 뺀다:** preview는 종료일 없이 오래 유지되기도 하고, 사용자가 일부러 고른 최신 모델일 수 있다. 종료일이 생기면 그때 같은 기준으로 뺀다.

## 결과

- 카탈로그는 28개(OpenAI 14, Gemini 5, Claude 9)다. 기본값이 모두 종료 일정이 없는 모델이다.
- 저장된 설정과 미발행 command가 호출 실패 모델을 가리키지 않는다.
- 비용 변화: OpenAI 기본값은 `gpt-5-nano` 대비 입력 ×2, 출력 ×1.25, Gemini 기본값은 입력 ×1.2, 출력 ×1.67이다. 공식 대체보다 작지만 비용은 오른다.
- 품질(JSON 응답 안정성 등)은 실제 호출로 확인하지 않은 채 결정했다. 문제가 확인되면 같은 등급 안에서 다시 고른다.
- 같은 판단(종료일 공지·자동 전환 → 제거, 같은 등급의 가장 싼 현행 모델로 이관)을 다음 교체에도 그대로 쓴다.
