package io.github.kubaj12.online_store.identityaccess.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InvitationTokenTests {
    @Test void randomTokensAreCanonicalHashedAndRedacted() {
        var first = InvitationToken.generate();
        var second = InvitationToken.generate();
        assertThat(first.value()).hasSize(43).isNotEqualTo(second.value());
        assertThat(first.hash()).hasSize(32).isNotEqualTo(second.hash());
        assertThat(InvitationToken.parse(first.value()).hash()).isEqualTo(first.hash());
        assertThat(first.toString()).doesNotContain(first.value());
    }
    @Test void rejectsMalformedAndNoncanonicalTokensWithoutEchoingSecrets() {
        for (String value : new String[] {null, "", "secret", "a".repeat(44), "a".repeat(42) + "b"}) {
            assertThatThrownBy(() -> InvitationToken.parse(value)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("invalid invitation token");
        }
    }
}
