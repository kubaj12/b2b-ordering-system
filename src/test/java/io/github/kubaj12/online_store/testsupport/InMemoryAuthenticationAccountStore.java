package io.github.kubaj12.online_store.testsupport;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.kubaj12.online_store.identityaccess.application.AuthenticationAccountStore;
import io.github.kubaj12.online_store.identityaccess.domain.NormalizedEmail;

public final class InMemoryAuthenticationAccountStore implements AuthenticationAccountStore {

	public static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	public static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
	public static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
	private final Map<UUID, Credentials> accounts = new ConcurrentHashMap<>();
	private final AtomicInteger credentialsLookups = new AtomicInteger();
	private final AtomicInteger accessLookups = new AtomicInteger();
	private final Map<UUID, Instant> lastLogins = new ConcurrentHashMap<>();
	private final AtomicInteger successfulLoginWrites = new AtomicInteger();
	private volatile RuntimeException nextFailure;
	private volatile RuntimeException nextSuccessfulLoginFailure;
	private Runnable beforeNextSuccessfulLogin;

	public InMemoryAuthenticationAccountStore(org.springframework.security.crypto.password.PasswordEncoder encoder) {
		reset(encoder);
	}

	public void reset(org.springframework.security.crypto.password.PasswordEncoder encoder) {
		accounts.clear();
		String hash = encoder.encode("CorrectHorseBattery12");
		put(new Credentials(CUSTOMER_ID, "customer@example.test", hash, "CUSTOMER", "ACTIVE", 0));
		put(new Credentials(EMPLOYEE_ID, "employee@example.test", hash, "EMPLOYEE", "ACTIVE", 0));
		put(new Credentials(ADMIN_ID, "admin@example.test", hash, "ADMIN", "ACTIVE", 0));
		credentialsLookups.set(0);
		accessLookups.set(0);
		lastLogins.clear();
		successfulLoginWrites.set(0);
		nextFailure = null;
		nextSuccessfulLoginFailure = null;
		beforeNextSuccessfulLogin = null;
	}

	public void put(Credentials credentials) { accounts.put(credentials.id(), credentials); }
	public void remove(UUID id) { accounts.remove(id); }
	public void failNextLookup(RuntimeException exception) { nextFailure = exception; }
	public int credentialsLookups() { return credentialsLookups.get(); }
	public int accessLookups() { return accessLookups.get(); }
	public int successfulLoginWrites() { return successfulLoginWrites.get(); }
	public Instant lastLoginAt(UUID id) { return lastLogins.get(id); }
	public void failNextSuccessfulLogin(RuntimeException exception) { nextSuccessfulLoginFailure = exception; }
	/** Runs after credential verification, immediately before the completion eligibility check. */
	public void beforeNextSuccessfulLogin(Runnable action) { beforeNextSuccessfulLogin = action; }

	@Override
	public Optional<Credentials> findCredentialsByEmail(NormalizedEmail email) {
		credentialsLookups.incrementAndGet();
		throwIfNeeded();
		return accounts.values().stream().filter(account -> account.email().equals(email.value())).findFirst();
	}

	@Override
	public Optional<AccessSnapshot> findAccessById(UUID accountId) {
		accessLookups.incrementAndGet();
		throwIfNeeded();
		return Optional.ofNullable(accounts.get(accountId)).map(account ->
				new AccessSnapshot(account.id(), account.email(), account.role(), account.status(), account.securityVersion()));
	}

	@Override
	public boolean updateSuccessfulLogin(io.github.kubaj12.online_store.identityaccess.application.AccountPrincipal principal,
			Instant now) {
		Runnable action = beforeNextSuccessfulLogin;
		beforeNextSuccessfulLogin = null;
		if (action != null) action.run();
		RuntimeException failure = nextSuccessfulLoginFailure;
		if (failure != null) { nextSuccessfulLoginFailure = null; throw failure; }
		Credentials account = accounts.get(principal.accountId());
		if (account == null || !account.email().equals(principal.email()) || !account.role().equals(principal.role())
				|| !"ACTIVE".equals(account.status()) || account.securityVersion() != principal.securityVersion()) return false;
		lastLogins.merge(account.id(), now, (oldValue, newValue) -> oldValue.compareTo(newValue) >= 0 ? oldValue : newValue);
		successfulLoginWrites.incrementAndGet();
		return true;
	}

	private void throwIfNeeded() {
		RuntimeException failure = nextFailure;
		if (failure != null) {
			nextFailure = null;
			throw failure;
		}
	}
}
