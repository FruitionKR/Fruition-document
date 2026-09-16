package fruition.core.query.exception;

public class QueryRunNotFoundException extends RuntimeException {
    public QueryRunNotFoundException(String requestId) {
        super("Query run을 찾을 수 없습니다: " + requestId);
    }
}
