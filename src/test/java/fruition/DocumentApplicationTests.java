package fruition;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import fruition.core.DocumentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
// 앱 클래스가 하위 패키지(fruition.core)에 있어 upward 탐색으로는 찾지 못한다 — 명시 지정.
@SpringBootTest(classes = DocumentApplication.class)
@AutoConfigureMockMvc
class DocumentApplicationTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void contextLoads() {
	}

	@Test
	void flywayPreparesWikiBoundaryWithoutDroppingDataTables() {
		for (String table : new String[]{
				"pipeline_runs",
				"wiki_page_embeddings",
				"wiki_embedding_vectors",
				"wiki_embedding_units",
				"wiki_pages",
				"source_blocks"
		}) {
			Boolean exists = jdbcTemplate.queryForObject(
					"SELECT to_regclass(?) IS NOT NULL",
					Boolean.class,
					"public." + table
			);
			assertThat(exists).as(table).isTrue();
		}
		Integer crossDatabaseConstraints = jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM information_schema.table_constraints
				WHERE constraint_name IN (
				    'fk_wiki_page_versions_page',
				    'fk_wiki_page_contributions_page',
				    'pipeline_runs_document_id_fkey'
				)
				""", Integer.class);
		assertThat(crossDatabaseConstraints).isZero();
		Integer actorColumns = jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM information_schema.columns
				WHERE table_name = 'pipeline_runs'
				  AND column_name IN ('user_id', 'workspace_id')
				""", Integer.class);
		assertThat(actorColumns).isEqualTo(2);
	}

	/** dev-up.sh와 README가 안내하는 진입 URL이다. permit 목록에서 빠지면 401이 되므로 회귀를 막는다. */
	@Test
	void swaggerUiEntryPoint_unauthenticated_isNotRejected() throws Exception {
		mockMvc.perform(get("/swagger-ui.html"))
				.andExpect(status().is3xxRedirection());
	}

	/**
	 * 커밋된 api-specs 명세가 현재 코드와 일치하는지 본다. 계약이 바뀌면 여기서 먼저 걸린다.
	 * 텍스트로 비교하는 이유: 의미만 비교하면 직렬화 스타일·순서가 흔들려도 통과해 diff가 신호를 잃는다.
	 */
	@Test
	void openApi_matchesCommittedSnapshot() throws Exception {
		String actual = renderOpenApiYaml();
		Path snapshot = snapshotPath();

		// -DupdateOpenApiSnapshot=true 로 실행하면 비교 대신 커밋 대상 파일을 갱신한다.
		if (Boolean.getBoolean("updateOpenApiSnapshot")) {
			Files.createDirectories(snapshot.getParent());
			Files.writeString(snapshot, actual, StandardCharsets.UTF_8);
			return;
		}

		assertThat(actual)
				.as("OpenAPI 명세가 %s와 다릅니다. ./gradlew :document-svc:test -DupdateOpenApiSnapshot=true 로 갱신하세요.", snapshot)
				.isEqualTo(Files.readString(snapshot, StandardCharsets.UTF_8));
	}

	private String renderOpenApiYaml() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString(StandardCharsets.UTF_8);

		Map<String, Object> spec = new ObjectMapper()
				.readValue(body, new TypeReference<LinkedHashMap<String, Object>>() {});
		// servers는 실행 호스트에 따라 달라져 계약과 무관하다.
		spec.remove("servers");

		return new ObjectMapper(YAMLFactory.builder()
				.enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
				.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
				// 긴 한글 설명이 임의 지점에서 접히면 줄 단위 diff가 무의미해진다.
				.disable(YAMLGenerator.Feature.SPLIT_LINES)
				.build())
				.writeValueAsString(spec);
	}

	private static Path snapshotPath() {
		String configured = System.getProperty("openapi.snapshot.path");
		if (configured == null) {
			throw new IllegalStateException("openapi.snapshot.path가 없습니다. Gradle test 태스크로 실행하세요.");
		}
		return Path.of(configured);
	}

}
