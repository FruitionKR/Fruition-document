package fruition.core.document.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class UniqueResourceNamesTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    private String document(String workspace, String name, UUID folder) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO documents (id, workspace_id, user_id, filename, display_name, normalized_filename,
                    byte_size, mime_type, status, uploaded_at, updated_at, document_role, current_version, sort_order, folder_id)
                VALUES (?, ?, 'user', ?, ?, lower(?), 0, 'text/markdown', 'uploaded', now(), now(), 'EDITABLE', 1, 0, ?)
                """, id, workspace, name, name, name, folder);
        return id;
    }

    private UUID folder(String workspace, String name, UUID parent) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO folders (id, workspace_id, name, parent_folder_id, sort_order, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, now(), now())
                """, id, workspace, name, parent);
        return id;
    }

    @ParameterizedTest
    @ValueSource(strings = {"보고서.md", " 보고서.MD ", "보고서.md"})
    void documentsAreUniqueAcrossFoldersAndUnicodeForms(String duplicate) {
        String workspace = UUID.randomUUID().toString();
        UUID folder = folder(workspace, "자료", null);
        document(workspace, "보고서.md", null);
        assertThatThrownBy(() -> document(workspace, duplicate, folder))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_documents_active_name");
        document(UUID.randomUUID().toString(), duplicate, null);
        document(workspace, "보고서.pdf", null);
    }

    @Test
    void renameAndRestoreCannotReuseAnActiveDocumentName() {
        String workspace = UUID.randomUUID().toString();
        String first = document(workspace, "계획.md", null);
        String second = document(workspace, "결과.md", null);
        assertThatThrownBy(() -> jdbc.update("UPDATE documents SET filename = '계획.md' WHERE id = ?", second))
                .isInstanceOf(DuplicateKeyException.class);
        jdbc.update("UPDATE documents SET deleted_at = now() WHERE id = ?", first);
        jdbc.update("UPDATE documents SET filename = '계획.md' WHERE id = ?", second);
        assertThatThrownBy(() -> jdbc.update("UPDATE documents SET deleted_at = NULL WHERE id = ?", first))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT filename FROM documents WHERE id = ?", String.class, second))
                .isEqualTo("계획.md");
    }

    @Test
    void foldersAreUniqueAcrossParentsAndRestoreIsAtomic() {
        String workspace = UUID.randomUUID().toString();
        UUID first = folder(workspace, "운영", null);
        UUID second = folder(workspace, "자료", null);
        assertThatThrownBy(() -> folder(workspace, "운영", second))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_folders_active_name");
        assertThatThrownBy(() -> jdbc.update("UPDATE folders SET name = '운영' WHERE id = ?", second))
                .isInstanceOf(DuplicateKeyException.class);
        jdbc.update("UPDATE folders SET deleted_at = now() WHERE id = ?", first);
        folder(workspace, "운영", second);
        assertThatThrownBy(() -> jdbc.update("UPDATE folders SET deleted_at = NULL WHERE id = ?", first))
                .isInstanceOf(DuplicateKeyException.class);
        folder(UUID.randomUUID().toString(), "운영", null);
    }

    @Test
    void concurrentCreatesCommitExactlyOneDocument() throws Exception {
        String workspace = UUID.randomUUID().toString();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> create = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    document(workspace, "동시 생성.md", null);
                    return true;
                } catch (DuplicateKeyException expected) {
                    return false;
                }
            };
            var first = executor.submit(create);
            var second = executor.submit(create);
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE workspace_id = ?", Integer.class, workspace))
                .isEqualTo(1);
    }
}
