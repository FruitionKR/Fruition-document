package fruition.core.usage.service;

import fruition.TestcontainersConfiguration;
import fruition.shared.ai.AiModelCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카탈로그에 넣은 모델은 지금 적용 중인 단가 행이 있어야 한다(#99). 없으면 그 모델 호출이 모두 unpriced로 청구에서 빠진다.
 * 모델을 추가할 때 단가 마이그레이션을 함께 넣는다. 단가를 넣지 못한 모델은 아래 목록에 이유와 함께 적는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AiModelCatalogPriceIntegrationTest {

    /** 공식 단가를 확인하지 못해 일부러 시드하지 않은 모델(provider/model → 이유). 지금은 없다. */
    private static final Map<String, String> UNPRICED = Map.of();

    /** 카탈로그에 없지만 ai가 과금 대상으로 원장에 남기는 음성 전사 모델(ai openai_speech.py). 분당 단가가 없으면 CI가 실패한다. */
    private static final List<String> TRANSCRIPTION_BILLED = List.of("openai/gpt-transcribe", "openai/gpt-live-transcribe");

    /** 답변 음성 합성 모델(#113). 출력 토큰을 오디오 토큰으로 보고 토큰 단가로 계산한다(V73). */
    private static final List<String> SPEECH_BILLED = Stream.concat(TRANSCRIPTION_BILLED.stream(),
            Stream.of("openai/gpt-realtime-2.1-mini")).toList();

    /** 카탈로그 밖 판단 모델: Fruition-ai pipeline/app/core/jev_client.py의 JEV_MODEL과 함께 갱신한다. */
    private static final List<String> DECISION_BILLED = List.of("typesafe/jev-1.13.0");

    @Autowired JdbcTemplate jdbc;

    @Test
    void everyCatalogModelHasAPriceEffectiveNow() {
        var missing = Stream.concat(new AiModelCatalog("openai,gemini,claude").enabledModels().stream()
                                .map(model -> model.provider() + "/" + model.model()),
                        Stream.concat(SPEECH_BILLED.stream(), DECISION_BILLED.stream()))
                .filter(key -> !UNPRICED.containsKey(key))
                .filter(key -> jdbc.queryForObject("SELECT count(*) FROM ai_model_prices WHERE provider = ? AND model = ? "
                        + "AND effective_from <= now()", Long.class, key.split("/", 2)[0], key.split("/", 2)[1]) == 0)
                .toList();

        assertThat(missing).isEmpty();
    }

    /** 전사 모델은 분당 단가가 없으면 audio_seconds가 있어도 계산할 수 없어 unpriced가 된다. */
    @Test
    void speechModelsHaveAudioPerMinutePrice() {
        var missing = TRANSCRIPTION_BILLED.stream()
                .filter(key -> jdbc.queryForObject("SELECT count(*) FROM ai_model_prices WHERE provider = ? AND model = ? "
                        + "AND effective_from <= now() AND audio_usd_per_minute IS NOT NULL", Long.class,
                        key.split("/", 2)[0], key.split("/", 2)[1]) == 0)
                .toList();

        assertThat(missing).isEmpty();
    }

    /** 소급 시작 시각과 무료 출력/미사용 캐시를 확인해 초기 호출의 unpriced 재발을 막는다. */
    @Test
    void jevPriceCoversFirstProductionCall() {
        var row = jdbc.queryForMap("SELECT * FROM ai_model_prices WHERE provider = 'typesafe' "
                        + "AND model = 'jev-1.13.0' AND effective_from <= ? ORDER BY effective_from DESC LIMIT 1",
                Timestamp.from(Instant.parse("2026-11-07T04:02:54Z")));
        assertThat((BigDecimal) row.get("input_usd_per_mtok")).isEqualByComparingTo("0.042");
        for (String column : List.of("output_usd_per_mtok", "cache_read_usd_per_mtok", "cache_write_usd_per_mtok")) {
            assertThat((BigDecimal) row.get(column)).isEqualByComparingTo(BigDecimal.ZERO);
        }
        assertThat(row.get("audio_usd_per_minute")).isNull();
        assertThat(row.get("tts_usd_per_mchar")).isNull();
        assertThat(((Timestamp) row.get("effective_from")).toInstant()).isEqualTo(Instant.parse("2026-11-07T00:00:00Z"));
    }
}
