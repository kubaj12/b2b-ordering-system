package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Instant;

import io.github.kubaj12.online_store.identityaccess.domain.LoginAttemptKey;
import io.github.kubaj12.online_store.identityaccess.domain.LoginThrottleState;

/** Transaction-bound persistence port for login attempt state. */
public interface LoginThrottleStore {
	int deleteExpiredBatch(Instant now, int limit);
	LoginThrottleState lockOrCreate(LoginAttemptKey key, LoginThrottleState initialState);
	void save(LoginAttemptKey key, LoginThrottleState state);
}
