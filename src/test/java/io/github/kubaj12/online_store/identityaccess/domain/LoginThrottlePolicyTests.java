package io.github.kubaj12.online_store.identityaccess.domain;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginThrottlePolicyTests {
	private static final Instant NOW = Instant.parse("2026-01-15T10:15:30Z");
	private final LoginThrottlePolicy policy = new LoginThrottlePolicy(5, Duration.ofMinutes(15),
			Duration.ofMinutes(15), Duration.ofHours(24));

	@Test
	void blocksOnFifthFailureAndAllowsAtExactExpiry() {
		LoginThrottleState state = policy.empty(NOW);
		for (int i = 0; i < 5; i++) state = policy.recordFailure(policy.normalize(state, NOW), NOW);
		assertThat(policy.blocked(state, NOW.plus(Duration.ofMinutes(15)).minusNanos(1))).isTrue();
		assertThat(policy.blocked(state, NOW.plus(Duration.ofMinutes(15)))).isFalse();
	}

	@Test
	void successfulResetIsScopedAndClockCannotMoveStateBackward() {
		LoginThrottleState state = policy.recordFailure(policy.empty(NOW), NOW);
		LoginThrottleState reset = policy.reset(state, NOW.minusSeconds(1));
		assertThat(reset.failedAttempts()).isZero();
		assertThat(reset.updatedAt()).isEqualTo(NOW);
	}

	@Test
	void rejectsInvalidConfiguration() {
		assertThatThrownBy(() -> new LoginThrottlePolicy(0, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoginThrottlePolicy(1, Duration.ofNanos(1), Duration.ofMinutes(1), Duration.ofMinutes(1)))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
