package io.github.kubaj12.online_store.testsupport;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.kubaj12.online_store.identityaccess.application.LoginThrottleStore;
import io.github.kubaj12.online_store.identityaccess.domain.LoginAttemptKey;
import io.github.kubaj12.online_store.identityaccess.domain.LoginThrottleState;

/** Small MVC fixture for the application port; it is not a concurrency substitute for PostgreSQL. */
public final class InMemoryLoginThrottleStore implements LoginThrottleStore {
	private final Map<LoginAttemptKey, LoginThrottleState> states = new ConcurrentHashMap<>();
	private final AtomicInteger lockCalls = new AtomicInteger();
	private volatile RuntimeException nextFailure;
	private volatile RuntimeException nextSaveFailure;
	public void reset() { states.clear(); lockCalls.set(0); nextFailure = null; nextSaveFailure = null; }
	public int lockCalls() { return lockCalls.get(); }
	public LoginThrottleState state(LoginAttemptKey key) { return states.get(key); }
	public void failNext(RuntimeException failure) { nextFailure = failure; }
	public void failNextSave(RuntimeException failure) { nextSaveFailure = failure; }
	@Override public int deleteExpiredBatch(Instant now, int limit) {
		throwIfNeeded(); int removed = 0;
		for (var entry : states.entrySet()) if (removed < limit && !entry.getValue().expiresAt().isAfter(now)
				&& states.remove(entry.getKey(), entry.getValue())) removed++;
		return removed;
	}
	@Override public synchronized LoginThrottleState lockOrCreate(LoginAttemptKey key, LoginThrottleState initialState) {
		throwIfNeeded(); lockCalls.incrementAndGet(); return states.computeIfAbsent(key, ignored -> initialState);
	}
	@Override public void save(LoginAttemptKey key, LoginThrottleState state) {
		throwIfNeeded();
		RuntimeException failure = nextSaveFailure;
		if (failure != null) { nextSaveFailure = null; throw failure; }
		states.put(key, state);
	}
	private void throwIfNeeded() { RuntimeException failure = nextFailure; if (failure != null) { nextFailure = null; throw failure; } }
}
