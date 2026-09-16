package fruition.core.query.dto;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class QueryRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void objectMapper_rejectsNonBooleanWebSearchValues() {
        for (String value : new String[]{"null", "\"true\"", "1", "1.5", "{}", "[]"}) {
            assertThatThrownBy(() -> objectMapper.readValue(
                    "{\"question\":\"질문\",\"allow_web_search\":" + value + "}", QueryRequest.class))
                    .isInstanceOf(JsonMappingException.class);
        }
    }

    @Test
    void objectMapper_keepsMissingWebSearchValueAsNullForValidation() throws Exception {
        assertThat(objectMapper.readValue("{\"question\":\"질문\"}", QueryRequest.class)
                .allowWebSearch()).isNull();
    }
}
