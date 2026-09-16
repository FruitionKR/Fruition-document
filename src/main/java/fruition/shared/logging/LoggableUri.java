package fruition.shared.logging;

/**
 * 로그에 남길 요청 URI를 만든다.
 *
 * <p>일부 경로는 secret을 path segment로 받는다. 그대로 남기면 로그 열람 권한만으로
 * 유효한 secret을 얻을 수 있어, 저장할 때 해시만 두는 설계가 상쇄된다.
 */
public final class LoggableUri {

    /** 초대 토큰: {@code /api/invitations/{token}[/accept]} */
    private static final String INVITATION_PREFIX = "/api/invitations/";
    private static final String MASK = "***";

    private LoggableUri() {}

    public static String mask(String uri) {
        if (uri == null || !uri.startsWith(INVITATION_PREFIX)) {
            return uri;
        }
        String rest = uri.substring(INVITATION_PREFIX.length());
        int nextSlash = rest.indexOf('/');
        // 토큰 뒤의 하위 경로(/accept)는 진단에 필요하므로 남기고 토큰만 가린다.
        return nextSlash < 0
                ? INVITATION_PREFIX + MASK
                : INVITATION_PREFIX + MASK + rest.substring(nextSlash);
    }
}
