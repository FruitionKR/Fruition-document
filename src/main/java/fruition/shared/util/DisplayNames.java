package fruition.shared.util;

/**
 * 사용자 표시명(displayName) 결정 규칙 공용 유틸.
 *
 * <p>우선 이름이 있으면 trim해서 쓰고, 없거나 공백이면 이메일 앞 3글자를 쓴다.
 * 결과는 최대 {@link #MAX_LENGTH}자로 자른다(회원가입 요청의 {@code @Size(max=50)}와 일치,
 * OAuth provider 닉네임처럼 검증을 거치지 않는 경로의 과도한 길이 방지).
 */
public final class DisplayNames {

    public static final int MAX_LENGTH = 50;
    private static final int EMAIL_PREFIX_LENGTH = 3;

    private DisplayNames() {
    }

    public static String resolve(String preferredName, String email) {
        String base = isPresent(preferredName)
                ? preferredName.trim()
                : email.substring(0, Math.min(EMAIL_PREFIX_LENGTH, email.length()));
        return base.length() > MAX_LENGTH ? base.substring(0, MAX_LENGTH) : base;
    }

    public static boolean isPresent(String name) {
        return name != null && !name.isBlank();
    }
}
