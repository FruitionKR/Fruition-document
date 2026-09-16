package fruition.shared.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretMaskerTest {

    private final SecretMasker masker = new SecretMasker();

    @Test
    @DisplayName("key=value 형태의 비밀값을 마스킹한다")
    void masksKeyValueSecrets() {
        assertThat(masker.mask("api_key=abcdef123456")).contains("api_key=[REDACTED]");
        assertThat(masker.mask("\"token\": \"abcdef123456\"")).contains("[REDACTED]").doesNotContain("abcdef123456");
        assertThat(masker.mask("password: supersecret")).contains("[REDACTED]").doesNotContain("supersecret");
    }

    @Test
    @DisplayName("프로바이더 키 프리픽스와 Bearer 토큰을 마스킹한다")
    void masksProviderKeysAndBearer() {
        assertThat(masker.mask("키는 sk-ABCDEFGHIJKLMNOP12 입니다")).contains("[REDACTED]").doesNotContain("sk-ABCDEFGHIJKLMNOP12");
        assertThat(masker.mask("Authorization: Bearer abcdefghij123456"))
                .contains("Bearer [REDACTED]").doesNotContain("abcdefghij123456");
    }

    @Test
    @DisplayName("PRIVATE KEY 블록 전체를 마스킹한다")
    void masksPrivateKeyBlock() {
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIBOwIBAAJB\n-----END RSA PRIVATE KEY-----";
        assertThat(masker.mask(pem)).isEqualTo("[REDACTED]");
    }

    @Test
    @DisplayName("비밀값이 아닌 일반 텍스트와 대화 메타데이터는 그대로 둔다")
    void leavesNormalTextUntouched() {
        String text = "- workspace_id: workspace-1\n- conversation_id: session_abc\nLangSmith 설정은 어디서 봐?";
        assertThat(masker.mask(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("null/빈 문자열을 안전하게 처리한다")
    void handlesNullAndEmpty() {
        assertThat(masker.mask(null)).isNull();
        assertThat(masker.mask("")).isEmpty();
    }
}
