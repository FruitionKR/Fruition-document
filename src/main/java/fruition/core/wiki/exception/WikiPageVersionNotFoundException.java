package fruition.core.wiki.exception;

public class WikiPageVersionNotFoundException extends RuntimeException {
    public WikiPageVersionNotFoundException(String pageId, long revision) {
        super("Wiki 페이지 버전을 찾을 수 없습니다: pageId=" + pageId + " revision=" + revision);
    }
}
