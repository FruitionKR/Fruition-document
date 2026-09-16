package fruition.core.document.service;

import com.github.difflib.DiffUtils;
import com.github.difflib.algorithm.myers.MyersDiffWithLinearSpace;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import fruition.core.document.dto.DocumentContentDiffResponse;
import fruition.core.document.dto.MarkdownDiff;
import fruition.core.document.exception.MarkdownDiffTooLargeException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class MarkdownDiffService {

    private static final int CONTEXT_LINES = 3;
    // 문서 두 개(before+after)를 합친 글자 수 상한. java-diff-utils의 선형 공간 Myers 구현은
    // 편집 거리와 무관하게 O(n+m) 메모리만 쓰므로, 여기서는 편집 거리가 아니라
    // 입력 자체의 크기로 "정말로 너무 큰 입력"만 막는다.
    private static final long MAX_INPUT_CHARS = 20_000_000L;

    /** 문서용 어댑터. 기존 응답 스키마를 그대로 유지한다. */
    public DocumentContentDiffResponse compare(
            String documentId, long fromVersion, String before, long toVersion, String after) {
        MarkdownDiff diff = diff(fromVersion, before, toVersion, after);
        return new DocumentContentDiffResponse(
                documentId, diff.fromVersion(), diff.toVersion(),
                diff.additions(), diff.deletions(), diff.hunks());
    }

    /** 리소스에 매이지 않는 계산. Wiki 페이지도 이것을 쓴다. */
    public MarkdownDiff diff(long fromVersion, String before, long toVersion, String after) {
        if (before.equals(after)) {
            return new MarkdownDiff(fromVersion, toVersion, 0, 0, List.of());
        }
        if ((long) before.length() + after.length() > MAX_INPUT_CHARS) {
            throw diffTooLarge();
        }
        List<String> oldLines = lines(before);
        List<String> newLines = lines(after);
        List<Edit> edits = computeEdits(oldLines, newLines);
        List<NumberedLine> numbered = number(edits);
        List<DocumentContentDiffResponse.Hunk> hunks = hunks(numbered);
        int additions = (int) edits.stream().filter(edit -> edit.type == Type.ADD).count();
        int deletions = (int) edits.stream().filter(edit -> edit.type == Type.DELETE).count();
        return new MarkdownDiff(fromVersion, toVersion, additions, deletions, hunks);
    }

    private List<String> lines(String markdown) {
        if (markdown.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(markdown.split("\\R", -1));
    }

    /** java-diff-utils(선형 공간 Myers)로 diff를 계산하고, 기존 CONTEXT/DELETE/ADD 흐름으로 펼친다. */
    private List<Edit> computeEdits(List<String> before, List<String> after) {
        Patch<String> patch = DiffUtils.diff(before, after, new MyersDiffWithLinearSpace<>());
        List<Edit> edits = new ArrayList<>();
        int oldIndex = 0;
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            int deltaStart = delta.getSource().getPosition();
            while (oldIndex < deltaStart) {
                edits.add(new Edit(Type.CONTEXT, before.get(oldIndex)));
                oldIndex++;
            }
            for (String line : delta.getSource().getLines()) {
                edits.add(new Edit(Type.DELETE, line));
            }
            for (String line : delta.getTarget().getLines()) {
                edits.add(new Edit(Type.ADD, line));
            }
            oldIndex = deltaStart + delta.getSource().size();
        }
        while (oldIndex < before.size()) {
            edits.add(new Edit(Type.CONTEXT, before.get(oldIndex)));
            oldIndex++;
        }
        return groupDeletesBeforeAdds(edits);
    }

    /**
     * java-diff-utils는 같은 변경 구간 안에서도 삭제/추가 순서를 문서 위치 기준으로만 매긴다.
     * GitHub 스타일 diff 관례(삭제를 먼저, 추가를 나중에 보여줌)에 맞추기 위해
     * CONTEXT로 끊기지 않는 연속 구간 안에서만 삭제를 앞으로 재배치한다.
     */
    private List<Edit> groupDeletesBeforeAdds(List<Edit> edits) {
        List<Edit> result = new ArrayList<>();
        int index = 0;
        while (index < edits.size()) {
            Edit edit = edits.get(index);
            if (edit.type == Type.CONTEXT) {
                result.add(edit);
                index++;
                continue;
            }
            int start = index;
            while (index < edits.size() && edits.get(index).type != Type.CONTEXT) {
                index++;
            }
            List<Edit> group = edits.subList(start, index);
            group.stream().filter(e -> e.type == Type.DELETE).forEach(result::add);
            group.stream().filter(e -> e.type == Type.ADD).forEach(result::add);
        }
        return result;
    }

    private MarkdownDiffTooLargeException diffTooLarge() {
        return new MarkdownDiffTooLargeException(
                "두 문서의 차이가 너무 커서 안전하게 비교할 수 없습니다.");
    }

    private List<NumberedLine> number(List<Edit> edits) {
        int oldLine = 1;
        int newLine = 1;
        List<NumberedLine> result = new ArrayList<>();
        for (Edit edit : edits) {
            Integer oldNumber = edit.type == Type.ADD ? null : oldLine++;
            Integer newNumber = edit.type == Type.DELETE ? null : newLine++;
            result.add(new NumberedLine(edit.type, oldNumber, newNumber, edit.content));
        }
        return result;
    }

    private List<DocumentContentDiffResponse.Hunk> hunks(List<NumberedLine> lines) {
        List<DocumentContentDiffResponse.Hunk> result = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            while (index < lines.size() && lines.get(index).type == Type.CONTEXT) {
                index++;
            }
            if (index == lines.size()) {
                break;
            }
            int start = Math.max(0, index - CONTEXT_LINES);
            int lastChange = index;
            int cursor = index + 1;
            while (cursor < lines.size()) {
                if (lines.get(cursor).type != Type.CONTEXT) {
                    lastChange = cursor;
                } else if (cursor - lastChange > CONTEXT_LINES * 2) {
                    break;
                }
                cursor++;
            }
            int end = Math.min(lines.size(), lastChange + CONTEXT_LINES + 1);
            result.add(toHunk(lines.subList(start, end)));
            index = end;
        }
        return result;
    }

    private DocumentContentDiffResponse.Hunk toHunk(List<NumberedLine> lines) {
        int oldStart = firstNumber(lines, true);
        int newStart = firstNumber(lines, false);
        int oldCount = (int) lines.stream().filter(line -> line.type != Type.ADD).count();
        int newCount = (int) lines.stream().filter(line -> line.type != Type.DELETE).count();
        List<DocumentContentDiffResponse.Line> responseLines = lines.stream()
                .map(line -> new DocumentContentDiffResponse.Line(
                        DocumentContentDiffResponse.Type.valueOf(line.type.name()),
                        line.oldLine, line.newLine, line.content))
                .toList();
        return new DocumentContentDiffResponse.Hunk(
                oldStart, oldCount, newStart, newCount, responseLines);
    }

    private int firstNumber(List<NumberedLine> lines, boolean old) {
        return lines.stream()
                .map(line -> old ? line.oldLine : line.newLine)
                .filter(number -> number != null)
                .findFirst()
                .orElse(0);
    }

    private enum Type {
        CONTEXT,
        DELETE,
        ADD
    }

    private record Edit(Type type, String content) {}

    private record NumberedLine(Type type, Integer oldLine, Integer newLine, String content) {}
}
