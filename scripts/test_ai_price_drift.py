"""ai_price_drift 파싱·비교 로직 테스트. 실행: python3 -m unittest discover -s scripts -p 'test_*.py'"""
import unittest
from datetime import datetime, timezone

import ai_price_drift as drift

JAVA = '''
    new AiModel("openai", "gpt-6-luna", "GPT-6 Luna"),
    new AiModel("gemini", "gemini-3.8-flash", "Gemini 3.8 Flash"),
    new AiModel("claude", "claude-opus-5-5", "Claude Opus 5.5")
'''
SQL = '''
    ('openai', 'gpt-6-luna',    '2026-01-01T00:00:00Z',  0.10,  0.50, 0.01,  0.125, NULL, NULL),
    ('gemini', 'gemini-3.8-flash', '2026-01-01T00:00:00Z', 0.75,  3.75, 0.075, 0.75, NULL, NULL),
    ('gemini', 'gemini-3.8-flash', '2027-01-01T00:00:00Z', 1.50,  7.50, 0.15,  1.50, NULL, NULL),
'''
NOW = datetime(2026, 10, 10, tzinfo=timezone.utc)


class DriftTest(unittest.TestCase):

    def test_catalog_and_seed_are_parsed(self):
        self.assertEqual(drift.catalog_models(JAVA), [('openai', 'gpt-6-luna'), ('gemini', 'gemini-3.8-flash'),
                                                      ('claude', 'claude-opus-5-5')])
        seeded = drift.seeded_prices(SQL, NOW)
        self.assertEqual(seeded[('openai', 'gpt-6-luna')]['cache_write'], 0.125)
        # 아직 적용 전인 2027 행이 아니라 지금 적용 중인 행을 쓴다.
        self.assertEqual(seeded[('gemini', 'gemini-3.8-flash')]['input'], 0.75)
        self.assertEqual(drift.seeded_prices(SQL, datetime(2027, 2, 1, tzinfo=timezone.utc))
                         [('gemini', 'gemini-3.8-flash')]['input'], 1.5)

    def test_price_diffs_convert_per_token_and_skip_missing_fields(self):
        models = drift.catalog_models(JAVA)
        litellm = {
            'gpt-6-luna': {'input_cost_per_token': 1e-07, 'output_cost_per_token': 5e-07,
                           'cache_read_input_token_cost': 1e-08, 'cache_creation_input_token_cost': 1.25e-07},
            # Gemini는 gemini/ 접두사 키이고 쓰기 단가가 없어 비교하지 않는다. 출력 단가만 다르다.
            'gemini/gemini-3.8-flash': {'input_cost_per_token': 7.5e-07, 'output_cost_per_token': 4e-06,
                                        'cache_read_input_token_cost': 7.5e-08},
        }
        diffs = drift.price_diffs(models, drift.seeded_prices(SQL, NOW), litellm)
        self.assertEqual(len(diffs), 2)
        self.assertIn('`gemini/gemini-3.8-flash` output: 시드 3.75 ≠ LiteLLM 4', diffs[0])
        self.assertIn('`claude/claude-opus-5-5`: 시드 단가 없음', diffs[1])

    def test_deprecations_ignore_replacement_column_and_rows_without_shutdown(self):
        models = [('openai', 'gpt-6-luna'), ('openai', 'gpt-4o'), ('gemini', 'gemini-3.8-flash'),
                  ('gemini', 'gemini-3-flash-preview')]
        openai = ('<table><tr><th>Shutdown date</th><th>Model / system</th><th>Recommended replacement</th></tr>'
                  '<tr><td>Apr 1, 2027</td><td><code>gpt-5.4-nano</code></td><td>gpt-6-luna</td></tr>'
                  '<tr><td>Jan 6, 2027</td><td>gpt-4o-2024-05-13</td><td>gpt-6-sol</td></tr>'
                  '<tr><td>Mar 1, 2027</td><td>gpt-4o</td><td>gpt-6-sol</td></tr></table>')
        hits = drift.deprecations('openai', models, openai)
        self.assertEqual(hits, ['`openai/gpt-4o`: Mar 1, 2027 | gpt-4o | gpt-6-sol'])
        gemini = ('<table><tr><td>gemini-3.8-flash</td><td>Sep 1, 2026</td><td>No shutdown date announced</td><td></td></tr>'
                  '<tr><td>gemini-3-flash-preview</td><td>Dec 17, 2025</td><td>No shutdown date announced</td>'
                  '<td>gemini-3.6-flash</td></tr></table>')
        self.assertEqual(len(drift.deprecations('gemini', models, gemini)), 1)
        self.assertIn('gemini-3-flash-preview', drift.deprecations('gemini', models, gemini)[0])
        self.assertIn('표를 찾지 못함', drift.deprecations('gemini', models, '<p>moved</p>')[0])


if __name__ == '__main__':
    unittest.main()
