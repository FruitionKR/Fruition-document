package fruition.core.document.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LineThreeWayMergeTest {

    private static final String BASE = "# 제목\n\n첫 줄\n둘째 줄\n셋째 줄\n\n끝 줄\n";

    @Test
    void changesOnDifferentLinesAreBothKept() {
        String theirs = BASE.replace("첫 줄", "첫 줄(서버)");
        String mine = BASE.replace("끝 줄", "끝 줄(나)");

        LineThreeWayMerge.Result result = LineThreeWayMerge.merge(BASE, theirs, mine);

        assertThat(result.conflicted()).isFalse();
        assertThat(result.markdown()).isEqualTo("# 제목\n\n첫 줄(서버)\n둘째 줄\n셋째 줄\n\n끝 줄(나)\n");
    }

    @Test
    void adjacentLineChangesMerge() {
        String theirs = BASE.replace("둘째 줄", "둘째 줄(서버)");
        String mine = BASE.replace("셋째 줄", "셋째 줄(나)");

        LineThreeWayMerge.Result result = LineThreeWayMerge.merge(BASE, theirs, mine);

        assertThat(result.conflicted()).isFalse();
        assertThat(result.markdown()).contains("둘째 줄(서버)\n셋째 줄(나)");
    }

    @Test
    void insertionsAndDeletionsOnBothSidesMerge() {
        String theirs = "# 제목\n\n서버가 넣은 줄\n첫 줄\n둘째 줄\n셋째 줄\n\n끝 줄\n";
        String mine = "# 제목\n\n첫 줄\n둘째 줄\n\n끝 줄\n내가 붙인 줄\n";

        LineThreeWayMerge.Result result = LineThreeWayMerge.merge(BASE, theirs, mine);

        assertThat(result.conflicted()).isFalse();
        assertThat(result.markdown()).isEqualTo("# 제목\n\n서버가 넣은 줄\n첫 줄\n둘째 줄\n\n끝 줄\n내가 붙인 줄\n");
    }

    @Test
    void sameLineChangedDifferentlyConflicts() {
        String theirs = BASE.replace("둘째 줄", "둘째 줄(서버)");
        String mine = BASE.replace("둘째 줄", "둘째 줄(나)");

        LineThreeWayMerge.Result result = LineThreeWayMerge.merge(BASE, theirs, mine);

        assertThat(result.conflicted()).isTrue();
        assertThat(result.markdown()).isEqualTo(mine);
    }

    @Test
    void insertionsAtSamePlaceConflict() {
        String theirs = BASE.replace("첫 줄\n", "첫 줄\n서버 줄\n");
        String mine = BASE.replace("첫 줄\n", "첫 줄\n내 줄\n");

        assertThat(LineThreeWayMerge.merge(BASE, theirs, mine).conflicted()).isTrue();
    }

    @Test
    void identicalChangesOnBothSidesAreNotConflicts() {
        String theirs = BASE.replace("둘째 줄", "같은 수정").replace("끝 줄", "끝 줄(서버)");
        String mine = BASE.replace("둘째 줄", "같은 수정");

        LineThreeWayMerge.Result result = LineThreeWayMerge.merge(BASE, theirs, mine);

        assertThat(result.conflicted()).isFalse();
        assertThat(result.markdown()).isEqualTo(theirs);
    }

    @Test
    void oneSideUnchangedTakesTheOther() {
        String changed = BASE.replace("첫 줄", "바뀐 줄");

        assertThat(LineThreeWayMerge.merge(BASE, BASE, changed)).isEqualTo(new LineThreeWayMerge.Result(changed, false));
        assertThat(LineThreeWayMerge.merge(BASE, changed, BASE)).isEqualTo(new LineThreeWayMerge.Result(changed, false));
    }

    @Test
    void trailingNewlineDifferenceIsPreserved() {
        String base = "a\nb\nc";
        String theirs = "A\nb\nc";
        String mine = "a\nb\nc\n";

        LineThreeWayMerge.Result result = LineThreeWayMerge.merge(base, theirs, mine);

        assertThat(result.conflicted()).isFalse();
        assertThat(result.markdown()).isEqualTo("A\nb\nc\n");
    }

    @Test
    void appendOnTopOfServerChangeTakesAppendedBody() {
        // 회의록 덧붙이기: 요청 본은 서버 현재 본 끝에 붙인 본이라 서버 변경을 모두 포함한다.
        String theirs = BASE.replace("끝 줄", "끝 줄(서버)");
        String mine = theirs + "\n# 회의록\n- 결정\n";

        LineThreeWayMerge.Result result = LineThreeWayMerge.merge(BASE, theirs, mine);

        assertThat(result.markdown()).isEqualTo(mine);
    }
}
