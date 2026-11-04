package fruition.core.skill.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.DocumentEditStateInitializer;
import fruition.core.skill.exception.SkillReferenceDocumentTooLargeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillReferenceServiceTest {

    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock DocumentRepository documentRepository;
    @Mock DocumentEditStateRepository editStateRepository;
    @Mock DocumentEditStateInitializer editStateInitializer;
    @Mock DocumentAccessPolicy accessPolicy;
    @Mock DocumentAccessPolicy.Viewer viewer;
    @Mock Document document;
    private SkillReferenceService service;

    @BeforeEach
    void setUp() {
        service = new SkillReferenceService(
                workspaceAccessGuard, documentRepository, editStateRepository, editStateInitializer, accessPolicy);
        lenient().when(accessPolicy.viewer("ws_1", "user_1")).thenReturn(viewer);
        lenient().when(viewer.canRead(document)).thenReturn(true);
    }

    @Test
    void read_doesNotReturnDocumentUserCannotRead() {
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));
        when(viewer.canRead(document)).thenReturn(false);

        assertThatThrownBy(() -> service.read("ws_1", "user_1", "doc_1", null))
                .isInstanceOf(DocumentNotFoundException.class);
        verifyNoInteractions(editStateInitializer, editStateRepository);
    }

    @Test
    void read_returnsLatestPostgresMarkdown() {
        when(document.getId()).thenReturn("doc_1");
        when(document.getDocumentRole()).thenReturn(DocumentRole.EDITABLE);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById("doc_1"))
                .thenReturn(Optional.of(new DocumentEditState("doc_1", "# 최신 본문", "hash", 1)));

        var response = service.read("ws_1", "user_1", "doc_1", null);

        assertThat(response.documentRole()).isEqualTo("EDITABLE");
        assertThat(response.markdown()).isEqualTo("# 최신 본문");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(editStateInitializer, editStateRepository);
        order.verify(editStateInitializer).initializeIfNeeded(document);
        order.verify(editStateRepository).findById("doc_1");
    }

    @Test
    void read_rejectsEditableCanonicalMarkdownOverThirtyThousandCharacters() {
        when(document.getId()).thenReturn("doc_1");
        when(document.getDocumentRole()).thenReturn(DocumentRole.EDITABLE);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById("doc_1"))
                .thenReturn(Optional.of(new DocumentEditState("doc_1", "가".repeat(30001), "hash", 1)));

        assertThatThrownBy(() -> service.read("ws_1", "user_1", "doc_1", null))
                .isInstanceOf(SkillReferenceDocumentTooLargeException.class);
    }

    @Test
    void read_returnsOriginalRoleWithoutReadingCoreMarkdown() {
        when(document.getDocumentRole()).thenReturn(DocumentRole.ORIGINAL);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));

        var response = service.read("ws_1", "user_1", "doc_1", null);

        assertThat(response.documentRole()).isEqualTo("ORIGINAL");
        assertThat(response.markdown()).isNull();
        verifyNoInteractions(editStateInitializer, editStateRepository);
    }

    @Test
    void read_usesDocumentWorkspaceBoundaryBeforeStateLookup() {
        when(document.getId()).thenReturn("doc_1");
        when(document.getDocumentRole()).thenReturn(DocumentRole.EDITABLE);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById("doc_1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.read("ws_1", "user_1", "doc_1", null))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void read_withoutMaxCharsKeepsResponseShapeAndAllowsExactLimit() {
        givenBody("가".repeat(30000));

        var response = service.read("ws_1", "user_1", "doc_1", null);

        assertThat(response.markdown()).hasSize(30000);
        assertThat(response.truncated()).isNull();
        assertThat(response.totalChars()).isNull();
    }

    @Test
    void read_withMaxCharsReturnsWholeBodyAtExactLimit() {
        givenBody("가나다");

        var response = service.read("ws_1", "user_1", "doc_1", 3);

        assertThat(response.markdown()).isEqualTo("가나다");
        assertThat(response.truncated()).isFalse();
        assertThat(response.totalChars()).isEqualTo(3);
    }

    @Test
    void read_withMaxCharsCutsOneCharacterOverLimit() {
        givenBody("가나다");

        var response = service.read("ws_1", "user_1", "doc_1", 2);

        assertThat(response.markdown()).isEqualTo("가나");
        assertThat(response.truncated()).isTrue();
        assertThat(response.totalChars()).isEqualTo(3);
    }

    /** 413 대신 잘라 준다. 30000자 상한은 max_chars가 없을 때만 적용한다. */
    @Test
    void read_withMaxCharsDoesNotRejectBodyOverThirtyThousandCharacters() {
        givenBody("가".repeat(30001));

        var response = service.read("ws_1", "user_1", "doc_1", 30000);

        assertThat(response.markdown()).hasSize(30000);
        assertThat(response.truncated()).isTrue();
        assertThat(response.totalChars()).isEqualTo(30001);
    }

    @Test
    void read_withLargeMaxCharsReturnsBodyOverThirtyThousandCharactersWhole() {
        givenBody("가".repeat(30001));

        var response = service.read("ws_1", "user_1", "doc_1", 40000);

        assertThat(response.markdown()).hasSize(30001);
        assertThat(response.truncated()).isFalse();
    }

    /** 글자 수는 코드 포인트다. AI(Python)의 len과 같아 이모지가 있어도 max_chars·total_chars가 어긋나지 않는다. */
    @Test
    void read_countsSurrogatePairAsOneCharacterAndNeverSplitsIt() {
        givenBody("가\uD83D\uDE00나");

        var response = service.read("ws_1", "user_1", "doc_1", 2);

        assertThat(response.markdown()).isEqualTo("가\uD83D\uDE00");
        assertThat(response.truncated()).isTrue();
        assertThat(response.totalChars()).isEqualTo(3);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            // 결합 문자: e + 결합 악센트를 떼지 않는다
            "'a\u0065\u0301x', 2, 'a'",
            "'a\u0065\u0301x', 3, 'a\u0065\u0301'",
            // NFD 한글: 초성·중성·종성을 한 글자로 둔다
            "'\u1112\u1161\u11AB\u1100', 2, ''",
            "'\u1112\u1161\u11AB\u1100', 3, '\u1112\u1161\u11AB'",
            // 국기: 지역 표시 기호 두 개가 한 글자다
            "'\uD83C\uDDF0\uD83C\uDDF7\uD83C\uDDF0\uD83C\uDDF7', 3, '\uD83C\uDDF0\uD83C\uDDF7'",
            // ZWJ 이모지: 이어 붙인 조각을 떼지 않는다
            "'a\uD83D\uDC69\u200D\uD83D\uDCBB', 2, 'a'",
            // 줄바꿈 CRLF를 가르지 않는다
            "'a\r\nb', 2, 'a'",
    })
    void read_backsOffToPrecedingCharacterBoundary(String body, int maxChars, String expected) {
        givenBody(body);

        var response = service.read("ws_1", "user_1", "doc_1", maxChars);

        assertThat(response.markdown()).isEqualTo(expected);
        assertThat(response.truncated()).isTrue();
        assertThat(response.totalChars()).isEqualTo(body.codePointCount(0, body.length()));
    }

    @Test
    void read_withMaxCharsStillChecksDocumentPermission() {
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));
        when(viewer.canRead(document)).thenReturn(false);

        assertThatThrownBy(() -> service.read("ws_1", "user_1", "doc_1", 10))
                .isInstanceOf(DocumentNotFoundException.class);
        verifyNoInteractions(editStateInitializer, editStateRepository);
    }

    @Test
    void read_withMaxCharsStillFailsClosedForNonMember() {
        doThrow(new WorkspaceNotFoundException("ws_1"))
                .when(workspaceAccessGuard).requireMember("ws_1", "user_2");

        assertThatThrownBy(() -> service.read("ws_1", "user_2", "doc_1", 10))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verifyNoInteractions(documentRepository, editStateRepository);
    }

    /** 원본 문서는 여기서 본문을 주지 않으므로 잘림 정보도 없다. */
    @Test
    void read_withMaxCharsLeavesOriginalRoleWithoutBodyOrTruncationInfo() {
        when(document.getDocumentRole()).thenReturn(DocumentRole.ORIGINAL);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));

        var response = service.read("ws_1", "user_1", "doc_1", 10);

        assertThat(response.documentRole()).isEqualTo("ORIGINAL");
        assertThat(response.markdown()).isNull();
        assertThat(response.truncated()).isNull();
        assertThat(response.totalChars()).isNull();
    }

    private void givenBody(String markdown) {
        when(document.getId()).thenReturn("doc_1");
        when(document.getDocumentRole()).thenReturn(DocumentRole.EDITABLE);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById("doc_1"))
                .thenReturn(Optional.of(new DocumentEditState("doc_1", markdown, "hash", 1)));
    }

    @Test
    void read_failsClosedBeforeDocumentAccess() {
        doThrow(new WorkspaceNotFoundException("ws_1"))
                .when(workspaceAccessGuard).requireMember("ws_1", "user_2");

        assertThatThrownBy(() -> service.read("ws_1", "user_2", "doc_1", null))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verifyNoInteractions(documentRepository, editStateRepository);
    }
}
