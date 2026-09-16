package fruition.shared.util;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 채팅 원문을 Wiki page화 전에 저장할 때, 눈에 띄는 비밀값을 best-effort로 마스킹한다.
 * (docs/backlog/spec/chat-to-wiki-contract.md §12.4)
 *
 * 정규식 기반 best-effort이므로 모든 비밀값을 보장하지 못한다. 명백한 패턴만 {@code [REDACTED]}로 치환한다.
 */
@Component
public class SecretMasker {

    private static final String MASK = "[REDACTED]";

    /** -----BEGIN ... PRIVATE KEY----- ... -----END ... PRIVATE KEY----- */
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----",
            Pattern.DOTALL);

    /** 흔한 프로바이더 키 프리픽스 (OpenAI sk-, AWS AKIA, GitHub ghp_/gho_, Slack xox_) */
    private static final Pattern PROVIDER_KEY = Pattern.compile(
            "\\b(sk-[A-Za-z0-9]{16,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9-]{10,})\\b");

    /** Authorization: Bearer &lt;token&gt; */
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(bearer\\s+)[A-Za-z0-9._\\-]{10,}");

    /** key/secret/token/password 류 라벨 뒤의 값 ("api_key": "...", token=... 등) */
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(\"?(?:api[_-]?key|secret(?:[_-]?key)?|access[_-]?key|token|password|passwd|pwd|credential)\"?\\s*[:=]\\s*\"?)"
                    + "([^\"\\s,}]{6,})");

    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = PRIVATE_KEY.matcher(text).replaceAll(MASK);
        out = PROVIDER_KEY.matcher(out).replaceAll(MASK);
        out = BEARER.matcher(out).replaceAll("$1" + MASK);
        out = KEY_VALUE.matcher(out).replaceAll("$1" + MASK);
        return out;
    }
}
