package fruition.shared.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoggableUriTest {

    @Test
    void masksInvitationToken() {
        assertThat(LoggableUri.mask("/api/invitations/AbC-123_xyz"))
                .isEqualTo("/api/invitations/***");
    }

    @Test
    void keepsSubPathAfterToken() {
        assertThat(LoggableUri.mask("/api/invitations/AbC-123_xyz/accept"))
                .isEqualTo("/api/invitations/***/accept");
    }

    @Test
    void leavesOtherPathsUntouched() {
        assertThat(LoggableUri.mask("/api/workspaces/ws_1/members")).isEqualTo("/api/workspaces/ws_1/members");
        assertThat(LoggableUri.mask("/api/invitations")).isEqualTo("/api/invitations");
        assertThat(LoggableUri.mask(null)).isNull();
    }
}
