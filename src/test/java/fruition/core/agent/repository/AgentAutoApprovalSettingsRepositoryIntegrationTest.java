package fruition.core.agent.repository;

import fruition.TestcontainersConfiguration;
import fruition.core.agent.dto.AgentAutoApprovalSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AgentAutoApprovalSettingsRepositoryIntegrationTest {

    @Autowired AgentAutoApprovalSettingsRepository repository;

    @Test
    void unsavedUserHasEverythingOff() {
        assertThat(repository.find("user_never_saved")).isEqualTo(AgentAutoApprovalSettings.DISABLED);
    }

    @Test
    void saveUpsertsPerUser() {
        repository.save("user_a", new AgentAutoApprovalSettings(true, false));
        repository.save("user_a", new AgentAutoApprovalSettings(false, true));
        repository.save("user_b", new AgentAutoApprovalSettings(true, true));

        assertThat(repository.find("user_a")).isEqualTo(new AgentAutoApprovalSettings(false, true));
        assertThat(repository.find("user_b")).isEqualTo(new AgentAutoApprovalSettings(true, true));
    }
}
