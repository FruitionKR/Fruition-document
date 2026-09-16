package fruition.core.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * JPA 배선. 애플리케이션 클래스에 직접 붙이면 @WebMvcTest 같은 slice 테스트까지
 * repository 부트스트랩이 끌려와 entityManagerFactory 부재로 깨지므로 별도 구성으로 분리한다.
 */
@Configuration
@EntityScan(basePackages = {"fruition.core", "fruition.shared.idempotency"})
@EnableJpaRepositories(basePackages = {"fruition.core", "fruition.shared.idempotency"})
public class JpaConfig {

    /** @Transactional과 TransactionTemplate의 기본 대상을 PostgreSQL로 둔다. */
    @Bean
    @Primary
    PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
