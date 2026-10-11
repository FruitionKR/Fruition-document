package fruition.core.wiki.service;

import fruition.TestcontainersConfiguration;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.Folder;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WikiSourceTitleResolverIntegrationTest {

    @Autowired WikiSourceTitleResolver resolver;
    @Autowired DocumentRepository documentRepository;
    @Autowired FolderRepository folderRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    String ws;

    @BeforeEach
    void setUp() {
        ws = "ws_" + UUID.randomUUID();
    }

    @Test
    void uniqueNameIsPlainAndRenameIsReflectedWithoutReingest() {
        Document doc = doc("old.md", null);
        doc.rename("회의록.md");
        documentRepository.save(doc);

        assertThat(resolver.titles(ws, List.of(doc))).containsEntry(doc.getId(), "회의록");
    }

    @Test
    void sameNameInDifferentFoldersGetsFolderPathAndRootLabel() {
        UUID projects = folder("프로젝트", null);
        UUID year = folder("2026", projects);
        Document root = documentRepository.save(doc("회의록.md", null));
        Document nested = documentRepository.save(doc("회의록.md", year));
        Document other = documentRepository.save(doc("기획.md", null));

        Map<String, String> titles = resolver.titles(ws, List.of(root, nested, other));

        assertThat(titles.get(root.getId())).isEqualTo("회의록 · 최상위");
        assertThat(titles.get(nested.getId())).isEqualTo("회의록 · 프로젝트/2026");
        assertThat(titles.get(other.getId())).isEqualTo("기획");
    }

    @Test
    void deletedDuplicateDoesNotCountAndDeletedOrForeignDocumentIsSkipped() {
        UUID folder = folder("보관", null);
        Document kept = documentRepository.save(doc("회의록.md", null));
        Document gone = documentRepository.save(doc("회의록.md", folder));
        jdbcTemplate.update("UPDATE documents SET deleted_at = now() WHERE id = ?", gone.getId());
        Document goneReloaded = documentRepository.findById(gone.getId()).orElseThrow();

        Map<String, String> titles = resolver.titles(ws, List.of(kept, goneReloaded));

        assertThat(titles).containsOnlyKeys(kept.getId()).containsEntry(kept.getId(), "회의록");
        assertThat(resolver.titles("ws_other", List.of(kept))).isEmpty();
    }

    @Test
    void duplicateInOtherWorkspaceDoesNotCount() {
        Document mine = documentRepository.save(doc("회의록.md", null));
        Document theirs = new Document("doc_" + UUID.randomUUID(), "ws_other_" + UUID.randomUUID(), "u", "회의록.md",
                "text/markdown", 1, null, "h");
        documentRepository.save(theirs);

        assertThat(resolver.titles(ws, List.of(mine))).containsEntry(mine.getId(), "회의록");
    }

    @Test
    void pdfOriginalAndSkillReferenceWithSameNameDoNotCount() {
        // PDF 변환본(회의록.md)은 원본(회의록.pdf)과 표시 이름이 같다. 원본은 편입 대상이 아니라 겹침으로 보지 않는다.
        Document converted = documentRepository.save(doc("회의록.md", null));
        Document original = new Document("doc_" + UUID.randomUUID(), ws, "user_1", "회의록.pdf",
                "application/pdf", 1, null, "h");
        original.place(null, 1);
        documentRepository.save(original);
        Document reference = documentRepository.save(doc("회의록.md", folder("참고", null)));
        jdbcTemplate.update("UPDATE documents SET origin = 'skill_reference' WHERE id = ?", reference.getId());

        assertThat(resolver.titles(ws, List.of(converted))).containsEntry(converted.getId(), "회의록");
    }

    @Test
    void folderCycleStopsAtDepthLimit() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        folderRepository.save(new Folder(a, ws, null, "A", 0));
        folderRepository.save(new Folder(b, ws, a, "B", 0));
        jdbcTemplate.update("UPDATE folders SET parent_folder_id = ? WHERE id = ?", b, a);
        Document d1 = documentRepository.save(doc("x.md", b));
        Document d2 = documentRepository.save(doc("x.md", a));

        assertThat(resolver.titles(ws, List.of(d1, d2))).hasSize(2);
    }

    private UUID folder(String name, UUID parent) {
        UUID id = UUID.randomUUID();
        folderRepository.save(new Folder(id, ws, parent, name, 0));
        return id;
    }

    private Document doc(String filename, UUID folderId) {
        Document d = new Document("doc_" + UUID.randomUUID(), ws, "user_1", filename, "text/markdown", 1, null, "h");
        d.place(folderId, 0);
        return d;
    }
}
