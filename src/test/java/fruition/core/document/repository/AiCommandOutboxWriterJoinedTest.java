package fruition.core.document.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.domain.AiCommandOutbox;
import fruition.core.usage.service.CreditService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AiCommandOutboxWriterJoinedTest {

    @Test
    @DisplayName("부모 실행에 딸린 command는 outbox에만 저장하고 run 등록과 크레딧 예약을 만들지 않는다")
    void enqueueJoinedOnlyWritesOutbox() {
        var repository = mock(AiCommandOutboxRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var credits = mock(CreditService.class);
        var writer = new AiCommandOutboxWriter(repository, new ObjectMapper(), jdbc, mock(EntityManager.class), credits);

        writer.enqueueJoined("convert:doc_1:0", "ai.convert.command", "convert:doc_1:0", "{\"kind\":\"convert_batch\"}");

        ArgumentCaptor<AiCommandOutbox> saved = ArgumentCaptor.forClass(AiCommandOutbox.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRunId()).isEqualTo("convert:doc_1:0");
        assertThat(saved.getValue().getTopic()).isEqualTo("ai.convert.command");
        assertThat(saved.getValue().getMessageKey()).isEqualTo("convert:doc_1:0");
        assertThat(saved.getValue().getPayload()).isEqualTo("{\"kind\":\"convert_batch\"}");
        verifyNoInteractions(jdbc, credits);
    }
}
