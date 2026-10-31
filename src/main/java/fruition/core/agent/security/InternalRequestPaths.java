package fruition.core.agent.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 내부 토큰 필터가 보호 대상인지 판정할 때 쓰는 경로.
 *
 * <p>getRequestURI()는 디코딩 전 원문이라 /references/%72ead처럼 한 글자만 인코딩해도 문자열 비교를
 * 비켜 가지만, 핸들러는 디코딩된 경로로 고른다. 컨테이너가 디코딩·정규화한 servletPath+pathInfo로
 * 비교해야 핸들러 매핑과 같은 경로를 본다.
 */
public final class InternalRequestPaths {

    private InternalRequestPaths() {
    }

    public static String canonicalPath(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return request.getServletPath() + (pathInfo == null ? "" : pathInfo);
    }
}
