package fruition.core.wiki.exception;

public class WikiPageNotFoundException extends RuntimeException {

    public WikiPageNotFoundException(String id) {
        super("Wiki 페이지를 찾을 수 없습니다: id=" + id);
    }
}
