package fruition.core.aihistory.service;

import fruition.core.aihistory.dto.DocumentRestorePlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 미리보기와 실행이 다른 Pod로 가도 토큰이 맞아야 한다. 각 인스턴스는 같은 설정으로 뜬 Pod를 흉내 낸다. */
class PreviewTokenSignerTest {

    private static final DocumentRestorePlan PLAN = new DocumentRestorePlan("doc_A", 6, 5);

    @Test
    @DisplayName("전용 키가 없어도 같은 내부 토큰으로 뜬 다른 인스턴스가 발급한 토큰을 받아들인다")
    void acceptsTokenFromAnotherInstanceSharingInternalToken() {
        String issued = new PreviewTokenSigner("", "shared-internal").sign("op_1", PLAN);

        assertThat(new PreviewTokenSigner("", "shared-internal").matches(issued, "op_1", PLAN)).isTrue();
        assertThat(new PreviewTokenSigner("", "other-internal").matches(issued, "op_1", PLAN)).isFalse();
    }

    @Test
    @DisplayName("전용 키가 있으면 그 키로 서명한다")
    void configuredSecretWins() {
        String issued = new PreviewTokenSigner("restore-key", "internal-a").sign("op_1", PLAN);

        assertThat(new PreviewTokenSigner("restore-key", "internal-b").matches(issued, "op_1", PLAN)).isTrue();
    }
}
