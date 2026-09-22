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
    void documentsAreUniqueOnlyWithinTheirParentAndAcrossUnicodeForms(String duplicate) {
        String workspace = UUID.randomUUID().toString();
        UUID folder = folder(workspace, "자료", null);
        document(workspace, "보고서.md", null);
        document(workspace, duplicate, folder);
        assertThatThrownBy(() -> document(workspace, duplicate, null))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_document_tree_active_name");
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
    void foldersAreUniqueOnlyWithinTheirParentAndRestoreIsAtomic() {
        String workspace = UUID.randomUUID().toString();
        UUID first = folder(workspace, "운영", null);
        UUID second = folder(workspace, "자료", null);
        folder(workspace, "운영", second);
        assertThatThrownBy(() -> folder(workspace, "운영", null))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_document_tree_active_name");
        assertThatThrownBy(() -> jdbc.update("UPDATE folders SET name = '운영' WHERE id = ?", second))
                .isInstanceOf(DuplicateKeyException.class);
        jdbc.update("UPDATE folders SET deleted_at = now() WHERE id = ?", first);
        folder(workspace, "운영", null);
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

    @Test
    void filesAndFoldersShareOneNamespaceButExtensionsRemainDistinct() {
        String workspace = UUID.randomUUID().toString();
        UUID parent = folder(workspace, "parent", null);
        document(workspace, "Report.md", parent);
        assertThatThrownBy(() -> folder(workspace, "report.MD", parent))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_document_tree_active_name");
        folder(workspace, "report", parent);
        document(workspace, "report.pdf", parent);
        folder(workspace, "report.md", null);
        assertThatThrownBy(() -> document(workspace, "REPORT.MD", null))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void movesCheckDestinationAcrossKindsAndReleaseTheOldName() {
        String workspace = UUID.randomUUID().toString();
        UUID parent = folder(workspace, "parent", null);
        UUID target = folder(workspace, "target", null);
        String doc = document(workspace, "Report.md", parent);
        UUID collision = folder(workspace, "report.md", target);
        assertThatThrownBy(() -> jdbc.update("UPDATE documents SET folder_id = ? WHERE id = ?", target, doc))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT folder_id FROM documents WHERE id = ?", UUID.class, doc))
                .isEqualTo(parent);
        assertThatThrownBy(() -> jdbc.update("UPDATE folders SET parent_folder_id = ? WHERE id = ?", parent, collision))
                .isInstanceOf(DuplicateKeyException.class);
        jdbc.update("UPDATE documents SET folder_id = NULL WHERE id = ?", doc);
        folder(workspace, "report.md", parent);
        assertThatThrownBy(() -> jdbc.update("UPDATE folders SET parent_folder_id = NULL WHERE id = ?", collision))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void crossKindRenamesRestoreAndHardDeleteMaintainTheNamespace() {
        String workspace = UUID.randomUUID().toString();
        String doc = document(workspace, "Report.md", null);
        UUID folder = folder(workspace, "other", null);
        assertThatThrownBy(() -> jdbc.update("UPDATE folders SET name = 'report.md' WHERE id = ?", folder))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE documents SET filename = 'OTHER' WHERE id = ?", doc))
                .isInstanceOf(DuplicateKeyException.class);
        jdbc.update("UPDATE documents SET deleted_at = now() WHERE id = ?", doc);
        jdbc.update("UPDATE folders SET name = 'report.md' WHERE id = ?", folder);
        assertThatThrownBy(() -> jdbc.update("UPDATE documents SET deleted_at = NULL WHERE id = ?", doc))
                .isInstanceOf(DuplicateKeyException.class);
        jdbc.update("DELETE FROM folders WHERE id = ?", folder);
        jdbc.update("UPDATE documents SET deleted_at = NULL WHERE id = ?", doc);
        jdbc.update("DELETE FROM documents WHERE id = ?", doc);
        folder(workspace, "REPORT.MD", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_tree_names WHERE workspace_id = ?",
                Integer.class, workspace)).isEqualTo(1);
    }

    @Test
    void concurrentFileAndFolderCreatesCommitExactlyOneItem() throws Exception {
        String workspace = UUID.randomUUID().toString();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { document(workspace, "Report.md", null); return true; }
                catch (DuplicateKeyException expected) { return false; }
            });
            var second = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { folder(workspace, "report.MD", null); return true; }
                catch (DuplicateKeyException expected) { return false; }
            });
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_tree_names WHERE workspace_id = ?",
                Integer.class, workspace)).isEqualTo(1);
    }


    @Test
    void concurrentMovesIntoTheSameParentCommitOnlyOneItem() throws Exception {
        String workspace = UUID.randomUUID().toString();
        UUID a = folder(workspace, "a", null);
        UUID b = folder(workspace, "b", null);
        UUID target = folder(workspace, "target", null);
        String doc = document(workspace, "Report.md", a);
        UUID dir = folder(workspace, "report.MD", b);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { jdbc.update("UPDATE documents SET folder_id = ? WHERE id = ?", target, doc); return true; }
                catch (DuplicateKeyException expected) { return false; }
            });
            var second = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { jdbc.update("UPDATE folders SET parent_folder_id = ? WHERE id = ?", target, dir); return true; }
                catch (DuplicateKeyException expected) { return false; }
            });
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_tree_names WHERE workspace_id = ? AND parent_folder_id = ?",
                Integer.class, workspace, target)).isEqualTo(1);
    }

}
