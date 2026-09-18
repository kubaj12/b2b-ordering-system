package io.github.kubaj12.online_store.identityaccess.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptKeyTests {
	@Test
	void normalizesIdentityAndEquivalentLiteralSourcesWithoutRetainingSecrets() {
		LoginAttemptKey first = LoginAttemptKey.of("  USER@EXAMPLE.TEST ", "127.0.0.1");
		LoginAttemptKey second = LoginAttemptKey.of("user@example.test", "::ffff:127.0.0.1");
		assertThat(first).isEqualTo(second);
		assertThat(first.identityHash()).hasSize(32);
		assertThat(first.toString()).doesNotContain("user@example", "127.0.0.1");
	}

	@Test
	void canonicalizesIpv6AndRejectsNonLiteralSources() {
		assertThat(LoginAttemptKey.of(null, "::1")).isEqualTo(LoginAttemptKey.of("", "0:0:0:0:0:0:0:1"));
		assertThat(LoginAttemptKey.of("user", "::ffff:192.0.2.1")).isEqualTo(LoginAttemptKey.of("user", "192.0.2.1"));
		assertThatThrownBy(() -> LoginAttemptKey.of("user", "example.test")).isInstanceOf(IllegalArgumentException.class)
				.hasMessage("login source must be a literal IP address");
	}
}
