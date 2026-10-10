package fruition.core.usage.service;

import fruition.TestcontainersConfiguration;
import fruition.shared.ai.AiModelCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

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

    @Autowired JdbcTemplate jdbc;

    @Test
    void everyCatalogModelHasAPriceEffectiveNow() {
        var missing = new AiModelCatalog("openai,gemini,claude").enabledModels().stream()
                .map(model -> model.provider() + "/" + model.model())
                .filter(key -> !UNPRICED.containsKey(key))
                .filter(key -> jdbc.queryForObject("SELECT count(*) FROM ai_model_prices WHERE provider = ? AND model = ? "
                        + "AND effective_from <= now()", Long.class, key.split("/", 2)[0], key.split("/", 2)[1]) == 0)
                .toList();

        assertThat(missing).isEmpty();
    }
}
