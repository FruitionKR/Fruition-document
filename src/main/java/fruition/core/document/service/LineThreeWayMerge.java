package fruition.core.document.service;

import com.github.difflib.DiffUtils;
import com.github.difflib.algorithm.myers.MyersDiffWithLinearSpace;
import com.github.difflib.patch.AbstractDelta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 줄 단위 3-way merge(diff3). 기준 본(base)에서 서버 본(theirs)과 요청 본(mine)이 각각 바꾼 구간을 구해,
 * 두 쪽 변경이 겹치지 않으면 둘 다 반영한다.
 *
 * <p>겹침 판정: 두 변경이 기준 본의 같은 줄을 건드리거나, 같은 위치에서 시작하면(같은 자리에 끼워 넣기 포함) 겹친다.
 * 바로 이웃한 줄을 각각 바꾼 것은 겹치지 않는다. 겹쳐도 두 쪽 결과가 같으면 충돌이 아니다.
 * 충돌이 하나라도 있으면 병합본을 만들지 않고 {@link Result#conflicted()}만 알린다(호출자가 last-writer-wins로 처리).
 */
final class LineThreeWayMerge {

    record Result(String markdown, boolean conflicted) {}

    /** 기준 본의 [start, end) 줄을 lines로 바꾸는 변경. start == end면 끼워 넣기다. */
    private record Hunk(int start, int end, List<String> lines) {}

    private LineThreeWayMerge() {}

    static Result merge(String base, String theirs, String mine) {
        if (mine.equals(theirs) || base.equals(theirs)) {
            return new Result(mine, false);
        }
        if (base.equals(mine)) {
            return new Result(theirs, false);
        }
        List<String> baseLines = lines(base);
        List<Hunk> theirHunks = hunks(baseLines, lines(theirs));
        List<Hunk> myHunks = hunks(baseLines, lines(mine));

        List<String> out = new ArrayList<>();
        int copied = 0;
        int t = 0;
        int m = 0;
        while (t < theirHunks.size() || m < myHunks.size()) {
            boolean takeTheirs = m >= myHunks.size()
                    || (t < theirHunks.size() && theirHunks.get(t).start() <= myHunks.get(m).start());
            Hunk first = takeTheirs ? theirHunks.get(t++) : myHunks.get(m++);
            List<Hunk> theirGroup = new ArrayList<>();
            List<Hunk> myGroup = new ArrayList<>();
            (takeTheirs ? theirGroup : myGroup).add(first);
            int groupStart = first.start();
            int groupEnd = first.end();
            boolean grew = true;
            while (grew) {
                grew = false;
                if (t < theirHunks.size() && overlaps(theirHunks.get(t), groupStart, groupEnd)) {
                    Hunk next = theirHunks.get(t++);
                    theirGroup.add(next);
                    groupEnd = Math.max(groupEnd, next.end());
                    grew = true;
                }
                if (m < myHunks.size() && overlaps(myHunks.get(m), groupStart, groupEnd)) {
                    Hunk next = myHunks.get(m++);
                    myGroup.add(next);
                    groupEnd = Math.max(groupEnd, next.end());
                    grew = true;
                }
            }

            List<String> region;
            if (myGroup.isEmpty()) {
                region = apply(baseLines, groupStart, groupEnd, theirGroup);
            } else if (theirGroup.isEmpty()) {
                region = apply(baseLines, groupStart, groupEnd, myGroup);
            } else {
                List<String> theirRegion = apply(baseLines, groupStart, groupEnd, theirGroup);
                List<String> myRegion = apply(baseLines, groupStart, groupEnd, myGroup);
                if (!theirRegion.equals(myRegion)) {
                    return new Result(mine, true);
                }
                region = myRegion;
            }
            out.addAll(baseLines.subList(copied, groupStart));
            out.addAll(region);
            copied = groupEnd;
        }
        out.addAll(baseLines.subList(copied, baseLines.size()));
        return new Result(String.join("\n", out), false);
    }

    /** hunk 시작이 그룹 범위 안이거나 그룹과 같은 위치에서 시작하면 겹친다. hunk는 시작 위치 순으로 온다. */
    private static boolean overlaps(Hunk hunk, int groupStart, int groupEnd) {
        return hunk.start() < groupEnd || hunk.start() == groupStart;
    }

    /** 기준 본 [start, end) 구간에 한쪽 변경들을 적용한 결과. */
    private static List<String> apply(List<String> base, int start, int end, List<Hunk> hunks) {
        List<String> region = new ArrayList<>();
        int copied = start;
        for (Hunk hunk : hunks) {
            region.addAll(base.subList(copied, hunk.start()));
            region.addAll(hunk.lines());
            copied = hunk.end();
        }
        region.addAll(base.subList(copied, end));
        return region;
    }

    private static List<Hunk> hunks(List<String> base, List<String> revised) {
        List<Hunk> hunks = new ArrayList<>();
        for (AbstractDelta<String> delta : DiffUtils.diff(base, revised, new MyersDiffWithLinearSpace<>()).getDeltas()) {
            int start = delta.getSource().getPosition();
            hunks.add(new Hunk(start, start + delta.getSource().size(), List.copyOf(delta.getTarget().getLines())));
        }
        hunks.sort(Comparator.comparingInt(Hunk::start));
        return hunks;
    }

    /** 마지막 줄바꿈 뒤의 빈 줄까지 남겨 join("\n")으로 원문을 그대로 되살린다. */
    private static List<String> lines(String text) {
        return List.of(text.split("\n", -1));
    }
}
