#!/usr/bin/env python3
"""AI 모델 단가·폐기 일정 주간 점검(#99).

카탈로그(AiModelCatalog.java) 모델의 시드 단가(V70 마이그레이션)를 LiteLLM 단가표와 비교하고, 공급사 폐기 페이지에
카탈로그 모델이 폐기 대상으로 올라왔는지 본다. 차이가 있으면 GitHub 이슈 하나를 만들거나 본문을 갱신한다.
공급사 API 키는 쓰지 않는다. 표준 라이브러리만 쓴다.

실행: python3 scripts/ai_price_drift.py [--dry-run]
"""
import html
import json
import os
import re
import sys
import urllib.request
from datetime import datetime, timezone

CATALOG = 'src/main/java/fruition/shared/ai/AiModelCatalog.java'
SEED = 'src/main/resources/db/migration/V70__seed_ai_model_prices.sql'
LITELLM_URL = 'https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json'
DEPRECATION_URLS = {
    'openai': 'https://developers.openai.com/api/docs/deprecations',
    'gemini': 'https://ai.google.dev/gemini-api/docs/deprecations',
}
ISSUE_TITLE = 'chore: AI 모델 단가·폐기 일정 주간 점검 결과'
# LiteLLM 키. OpenAI·Anthropic은 모델 id 그대로, Gemini(AI Studio)는 gemini/ 접두사다.
LITELLM_KEY = {'openai': '{}', 'gemini': 'gemini/{}', 'claude': '{}'}
# 시드 열 → LiteLLM 토큰당 USD 필드. LiteLLM에 없는 필드는 비교하지 않는다
# (OpenAI "-"·Gemini의 쓰기 단가는 시드에서 입력 단가로 넣었다).
FIELDS = {
    'input': 'input_cost_per_token',
    'output': 'output_cost_per_token',
    'cache_read': 'cache_read_input_token_cost',
    'cache_write': 'cache_creation_input_token_cost',
}
NO_SHUTDOWN = 'No shutdown date announced'


def catalog_models(java):
    """AiModelCatalog.java의 (provider, model) 목록."""
    return re.findall(r'new AiModel\("([^"]+)",\s*"([^"]+)"', java)


def seeded_prices(sql, now):
    """시드 SQL에서 (provider, model)마다 now에 적용 중인 행(가장 늦은 effective_from <= now)의 단가."""
    rows = re.findall(r"\('(\w+)',\s*'([^']+)',\s*'([^']+)',\s*([\d.]+),\s*([\d.]+),\s*([\d.]+),\s*([\d.]+)", sql)
    latest = {}
    for provider, model, effective, *values in rows:
        at = datetime.fromisoformat(effective.replace('Z', '+00:00'))
        key = (provider, model)
        if at <= now and (key not in latest or at > latest[key][0]):
            latest[key] = (at, dict(zip(FIELDS, map(float, values))))
    return {key: prices for key, (_, prices) in latest.items()}


def price_diffs(models, seeded, litellm):
    """시드 단가와 LiteLLM 단가(USD / 1M tokens로 환산)가 다른 항목."""
    diffs = []
    for provider, model in models:
        ours = seeded.get((provider, model))
        entry = litellm.get(LITELLM_KEY.get(provider, '{}').format(model))
        if ours is None:
            diffs.append(f'`{provider}/{model}`: 시드 단가 없음')
            continue
        if entry is None:
            diffs.append(f'`{provider}/{model}`: LiteLLM에 항목 없음')
            continue
        for field, litellm_field in FIELDS.items():
            value = entry.get(litellm_field)
            if not isinstance(value, (int, float)):
                continue
            theirs = round(value * 1_000_000, 6)
            if abs(theirs - ours[field]) > 1e-6:
                diffs.append(f'`{provider}/{model}` {field}: 시드 {ours[field]:g} ≠ LiteLLM {theirs:g} (USD / 1M tokens)')
    return diffs


def table_rows(page):
    """HTML 표의 행마다 셀 텍스트 목록."""
    rows = []
    for row in re.findall(r'<tr[^>]*>(.*?)</tr>', page, re.S):
        cells = re.findall(r'<t[dh][^>]*>(.*?)</t[dh]>', row, re.S)
        rows.append([' '.join(html.unescape(re.sub(r'<[^>]+>', ' ', cell)).split()) for cell in cells])
    return rows


def deprecations(provider, models, page):
    """폐기 페이지에서 카탈로그 모델이 폐기 대상으로 올라온 행.

    마지막 열(권장 대체 모델)에만 나오는 경우는 대체 모델로 추천된 것이라 뺀다. 종료일도 대체 모델도 없는 행
    (Gemini 수명 표의 'No shutdown date announced')은 폐기 대상이 아니다.
    """
    rows = table_rows(page)
    if not rows:
        return [f'{provider} 폐기 페이지에서 표를 찾지 못함(페이지 구조 변경 확인 필요)']
    hits = []
    for _, model in (m for m in models if m[0] == provider):
        pattern = re.compile(r'(?<![\w.-])' + re.escape(model) + r'(?![\w.-])')
        for cells in rows:
            if len(cells) < 2 or not any(pattern.search(cell) for cell in cells[:-1]):
                continue
            if cells[-1] or not any(NO_SHUTDOWN in cell for cell in cells):
                hits.append(f'`{provider}/{model}`: ' + ' | '.join(cells))
    return hits


def report(diffs, hits):
    lines = ['AI 모델 단가·폐기 일정 주간 점검 결과다. `scripts/ai_price_drift.py`가 만든다.', '']
    lines += ['## 단가 차이 (시드 V70 vs LiteLLM)', '']
    lines += [f'- {d}' for d in diffs] or ['- 없음']
    lines += ['', '## 폐기 페이지에 오른 카탈로그 모델', '']
    lines += [f'- {h}' for h in hits] or ['- 없음']
    lines += ['', '공식 단가 페이지에서 확인한 뒤 새 effective_from 단가 행(운영 DB 또는 새 마이그레이션)과',
              '`AiModelCatalog`·시드를 함께 고친다. LiteLLM은 참고용이며 근거는 공급사 공식 페이지다.',
              '', f'점검 시각: {datetime.now(timezone.utc).isoformat(timespec="seconds")}']
    return '\n'.join(lines)


def fetch(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'fruition-ai-price-drift'})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode('utf-8', errors='replace')


def github(method, path, body=None):
    request = urllib.request.Request(
        'https://api.github.com' + path, method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Authorization': 'Bearer ' + os.environ['GITHUB_TOKEN'],
                 'Accept': 'application/vnd.github+json', 'User-Agent': 'fruition-ai-price-drift'})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read() or b'null')


def upsert_issue(body):
    """같은 제목의 열린 이슈가 있으면 본문을 바꾸고, 없으면 만든다."""
    repo = os.environ['GITHUB_REPOSITORY']
    # ponytail: 열린 이슈 100건까지만 본다. 넘으면 페이지를 넘겨 찾는다.
    issues = github('GET', f'/repos/{repo}/issues?state=open&per_page=100')
    existing = next((i for i in issues if i['title'] == ISSUE_TITLE and 'pull_request' not in i), None)
    if existing:
        github('PATCH', f'/repos/{repo}/issues/{existing["number"]}', {'body': body})
        print(f'이슈 #{existing["number"]} 갱신')
    else:
        created = github('POST', f'/repos/{repo}/issues', {'title': ISSUE_TITLE, 'body': body, 'labels': ['enhancement']})
        print(f'이슈 #{created["number"]} 생성')


def main(argv):
    with open(CATALOG, encoding='utf-8') as f:
        models = catalog_models(f.read())
    with open(SEED, encoding='utf-8') as f:
        seeded = seeded_prices(f.read(), datetime.now(timezone.utc))
    diffs = price_diffs(models, seeded, json.loads(fetch(LITELLM_URL)))
    hits = [h for provider, url in DEPRECATION_URLS.items() for h in deprecations(provider, models, fetch(url))]
    body = report(diffs, hits)
    print(body)
    if not diffs and not hits:
        return 0
    if '--dry-run' not in argv:
        upsert_issue(body)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
