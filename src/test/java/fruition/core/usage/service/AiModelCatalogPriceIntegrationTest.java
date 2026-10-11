package fruition.core.usage.service;

import fruition.TestcontainersConfiguration;
import fruition.shared.ai.AiModelCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

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

    /** 카탈로그에 없지만 ai가 과금 대상으로 원장에 남기는 음성 전사 모델(ai openai_speech.py). 단가가 없으면 CI가 실패한다. */
    private static final List<String> SPEECH_BILLED = List.of("openai/gpt-transcribe", "openai/gpt-live-transcribe");

    /**
     * 일부러 넣지 않는 과금 모델. gpt-realtime-2.1-mini(TTS, 호출하는 곳 없음)는 공식 단가가 텍스트·오디오 토큰으로
     * 갈리는데 ai 원장은 토큰을 한 열로 합쳐 남겨 맞게 계산할 수 없다. 호출을 켜기 전에 원장이 토큰 종류를 나눠 남겨야 한다.
     */
    private static final Map<String, String> SPEECH_UNPRICED = Map.of(
            "openai/gpt-realtime-2.1-mini", "텍스트·오디오 토큰 단가가 달라 합쳐 기록한 토큰으로 계산할 수 없다");

    @Autowired JdbcTemplate jdbc;

    @Test
    void everyCatalogModelHasAPriceEffectiveNow() {
        var missing = Stream.concat(new AiModelCatalog("openai,gemini,claude").enabledModels().stream()
                                .map(model -> model.provider() + "/" + model.model()), SPEECH_BILLED.stream())
                .filter(key -> !UNPRICED.containsKey(key) && !SPEECH_UNPRICED.containsKey(key))
                .filter(key -> jdbc.queryForObject("SELECT count(*) FROM ai_model_prices WHERE provider = ? AND model = ? "
                        + "AND effective_from <= now()", Long.class, key.split("/", 2)[0], key.split("/", 2)[1]) == 0)
                .toList();

        assertThat(missing).isEmpty();
    }
}
