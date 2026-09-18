package io.github.kubaj12.online_store.identityaccess.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Pure fixed-window login-throttle transitions. */
public final class LoginThrottlePolicy {
	private final int maxFailures;
	private final Duration window;
	private final Duration blockDuration;
	private final Duration retention;

	public LoginThrottlePolicy(int maxFailures, Duration window, Duration blockDuration, Duration retention) {
		if (maxFailures <= 0 || invalid(window) || invalid(blockDuration) || invalid(retention)
				|| retention.compareTo(window) < 0 || retention.compareTo(blockDuration) < 0)
			throw new IllegalArgumentException("invalid login throttle policy");
		this.maxFailures = maxFailures; this.window = window; this.blockDuration = blockDuration; this.retention = retention;
	}
	private static boolean invalid(Duration value) { return value == null || value.isNegative() || value.isZero()
			|| value.compareTo(Duration.ofNanos(1_000)) < 0 || value.toNanos() % 1_000 != 0; }
	public int maxFailures() { return maxFailures; }
	public Duration window() { return window; }
	public Duration blockDuration() { return blockDuration; }
	public Duration retention() { return retention; }
	public Instant effectiveNow(Instant now, LoginThrottleState state) { return max(now, state.updatedAt()).truncatedTo(ChronoUnit.MICROS); }
	public LoginThrottleState empty(Instant now) { now = micros(now); return LoginThrottleState.empty(now, now.plus(retention)); }
	public boolean blocked(LoginThrottleState state, Instant now) { return state.blockedUntil() != null && state.blockedUntil().isAfter(effectiveNow(now, state)); }
	public LoginThrottleState normalize(LoginThrottleState state, Instant now) {
		now = effectiveNow(now, state);
		if (blocked(state, now)) return state;
		if (!state.expiresAt().isAfter(now) || !state.windowStartedAt().plus(window).isAfter(now)
				|| state.blockedUntil() != null && !state.blockedUntil().isAfter(now))
			return new LoginThrottleState(0, now, null, null, state.createdAt(), now, now.plus(retention));
		return state;
	}
	public LoginThrottleState recordFailure(LoginThrottleState state, Instant now) {
		now = effectiveNow(now, state);
		int failures = state.failedAttempts() + 1;
		Instant blocked = failures >= maxFailures ? now.plus(blockDuration) : null;
		Instant expiry = max(now.plus(retention), blocked == null ? now.plus(retention) : blocked);
		return new LoginThrottleState(failures, state.windowStartedAt(), now, blocked, state.createdAt(), now, expiry);
	}
	public LoginThrottleState reset(LoginThrottleState state, Instant now) {
		now = effectiveNow(now, state);
		return new LoginThrottleState(0, now, null, null, state.createdAt(), now, now.plus(retention));
	}
	private static Instant micros(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
	private static Instant max(Instant a, Instant b) { return a.compareTo(b) >= 0 ? a : b; }
}
