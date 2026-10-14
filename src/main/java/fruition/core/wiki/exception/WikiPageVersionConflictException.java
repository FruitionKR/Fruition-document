package fruition.core.wiki.exception;

/** Wiki 본문 저장의 base_revision이 서버 현재 revision과 다르다. */
public class WikiPageVersionConflictException extends RuntimeException {

    private final long currentRevision;

    public WikiPageVersionConflictException(long currentRevision) {
        super("Wiki 페이지가 이미 변경되었습니다. 최신 본문을 다시 불러와 주세요.");
        this.currentRevision = currentRevision;
    }

    public long getCurrentRevision() {
        return currentRevision;
    }
}
