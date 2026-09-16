package fruition.core.wiki.exception;

public class WikiPageSlugConflictException extends RuntimeException {
    public WikiPageSlugConflictException(String slug) {
        super("이미 사용 중인 slug입니다: " + slug);
    }
}
