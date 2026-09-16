package fruition;

import fruition.core.DocumentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * actuator는 management.server.port로 업무 포트와 분리돼 있다. ALB Ingress가 업무 포트만
 * 라우팅하므로, 이 분리가 /actuator/prometheus를 외부에 노출하지 않는 유일한 근거다.
 * MockMvc는 업무 서블릿 컨텍스트만 보므로 실제 포트를 열어 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
		classes = DocumentApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "management.server.port=0")
// Boot는 테스트에서 metrics export를 기본으로 끈다 — prometheus 엔드포인트를 실제로 확인하려면 켜야 한다.
@AutoConfigureObservability
class ManagementPortTests {

	@Autowired
	TestRestTemplate restTemplate;

	@LocalServerPort
	int serverPort;

	@LocalManagementPort
	int managementPort;

	@Test
	void health_onManagementPort_unauthenticated_returnsUp() {
		ResponseEntity<String> response = get(managementPort, "/actuator/health");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("\"status\":\"UP\"");
	}

	@Test
	void prometheus_onManagementPort_unauthenticated_exposesMetrics() {
		ResponseEntity<String> response = get(managementPort, "/actuator/prometheus");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("jvm_memory_used_bytes");
	}

	/** 업무 포트에 actuator가 남아 있으면 ALB를 통해 인터넷에 공개된다 — 매핑 자체가 없어야 한다. */
	@Test
	void actuator_onServerPort_isNotMapped() {
		assertThat(get(serverPort, "/actuator/health").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(get(serverPort, "/actuator/prometheus").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	private ResponseEntity<String> get(int port, String path) {
		return restTemplate.getForEntity("http://localhost:" + port + path, String.class);
	}
}
