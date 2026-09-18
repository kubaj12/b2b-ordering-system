package io.github.kubaj12.online_store.identityaccess.domain;

import java.time.Instant;
import java.util.Objects;

/** Immutable persisted state for one identity/source pair. */
public final class LoginThrottleState {
	private final int failedAttempts;
	private final Instant windowStartedAt;
	private final Instant lastFailedAt;
	private final Instant blockedUntil;
	private final Instant createdAt;
	private final Instant updatedAt;
	private final Instant expiresAt;

	public LoginThrottleState(int failedAttempts, Instant windowStartedAt, Instant lastFailedAt, Instant blockedUntil,
			Instant createdAt, Instant updatedAt, Instant expiresAt) {
		if (failedAttempts < 0 || (failedAttempts == 0 && lastFailedAt != null)
				|| (failedAttempts > 0 && lastFailedAt == null)) throw new IllegalArgumentException("invalid throttle state");
		this.failedAttempts = failedAttempts;
		this.windowStartedAt = Objects.requireNonNull(windowStartedAt);
		this.lastFailedAt = lastFailedAt;
		this.blockedUntil = blockedUntil;
		this.createdAt = Objects.requireNonNull(createdAt);
		this.updatedAt = Objects.requireNonNull(updatedAt);
		this.expiresAt = Objects.requireNonNull(expiresAt);
	}

	public static LoginThrottleState empty(Instant now, Instant expiresAt) {
		return new LoginThrottleState(0, now, null, null, now, now, expiresAt);
	}
	public int failedAttempts() { return failedAttempts; }
	public Instant windowStartedAt() { return windowStartedAt; }
	public Instant lastFailedAt() { return lastFailedAt; }
	public Instant blockedUntil() { return blockedUntil; }
	public Instant createdAt() { return createdAt; }
	public Instant updatedAt() { return updatedAt; }
	public Instant expiresAt() { return expiresAt; }
	@Override public String toString() { return "LoginThrottleState[<redacted>]"; }
}
