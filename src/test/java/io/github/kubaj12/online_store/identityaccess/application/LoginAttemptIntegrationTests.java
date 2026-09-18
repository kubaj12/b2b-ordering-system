package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.kubaj12.online_store.identityaccess.domain.LoginAttemptKey;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class LoginAttemptIntegrationTests extends PostgreSqlServiceTestSupport {
	private static final Instant NOW = Instant.parse("2026-01-15T10:15:30.123456Z");
	private static final String EMAIL = "integration@example.test";
	private static final String SOURCE = "192.0.2.40";

	@Autowired private LoginAttemptService service;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private TransactionTemplate transactionTemplate;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private AuthenticationAccountStore accountStore;
	@Autowired private LoginThrottleStore throttleStore;
	@Autowired private io.github.kubaj12.online_store.identityaccess.domain.LoginThrottlePolicy policy;

	@Test
	void rejectedAuthenticationCommitsItsFailureOutsideAnOuterRollback() {
		transactionTemplate.executeWithoutResult(status -> {
			assertThatThrownBy(() -> rejected()).isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
			status.setRollbackOnly();
		});

		assertThat(failures(EMAIL, SOURCE)).isOne();
	}

	@Test
	void successfulCompletionUpdatesTheAccountAndResetsOnlyItsPair() {
		insertUser();
		testClock().set(NOW);
		failures(EMAIL, SOURCE, 2);
		failures(EMAIL, "192.0.2.41", 2);
		AccountPrincipal principal = principal();

		assertThat(service.completeSuccessfulLogin(principal, SOURCE)).isTrue();
		assertThat(lastLogin()).isEqualTo(NOW);
		assertThat(failures(EMAIL, SOURCE)).isZero();
		assertThat(failures(EMAIL, "192.0.2.41")).isEqualTo(2);
	}

	@Test
	void failedResetRollsBackTheTimestampAndThrottleStateTogether() {
		insertUser();
		Instant before = NOW.minusSeconds(60);
		jdbcTemplate.update("UPDATE identity_user SET created_at = ?, last_login_at = ?, updated_at = ? WHERE email = ?",
				java.sql.Timestamp.from(before), java.sql.Timestamp.from(before), java.sql.Timestamp.from(before), EMAIL);
		testClock().set(NOW.plusSeconds(60));
		failures(EMAIL, SOURCE, 2);
		LoginAttemptService failingService = serviceWithFailingSave();

		assertThatThrownBy(() -> failingService.completeSuccessfulLogin(principal(), SOURCE))
				.isInstanceOf(org.springframework.security.authentication.AuthenticationServiceException.class)
				.hasNoCause();

		assertThat(lastLogin()).isEqualTo(before);
		assertThat(updatedAt()).isEqualTo(before);
		assertThat(failures(EMAIL, SOURCE)).isEqualTo(2);
	}

	@Test
	void credentialAdmissionDoesNotWriteLifecycleTimestampsOrResetExistingFailures() {
		insertUser();
		testClock().set(NOW);
		failures(EMAIL, SOURCE, 2);

		assertThat(service.authenticate(EMAIL, SOURCE,
				() -> new org.springframework.security.authentication.TestingAuthenticationToken("verified", "credentials")))
				.isNotNull();
		assertThat(lastLogin()).isNull();
		assertThat(failures(EMAIL, SOURCE)).isEqualTo(2);
	}

	@Test
	void expiredLockedRowIsSkippedByCleanupAndReclaimedAfterRelease() throws Exception {
		LoginAttemptKey target = LoginAttemptKey.of(EMAIL, SOURCE);
		LoginAttemptKey other = LoginAttemptKey.of("other@example.test", SOURCE);
		Instant expired = NOW.minus(java.time.Duration.ofDays(2));
		transactionTemplate.executeWithoutResult(status -> {
			throttleStore.lockOrCreate(target, policy.empty(expired));
			throttleStore.lockOrCreate(other, policy.empty(expired));
		});
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
				throttleStore.lockOrCreate(target, policy.empty(expired));
				locked.countDown();
				await(release);
			}));
			assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
			var cleanup = executor.submit(() -> transactionTemplate.execute(status -> throttleStore.deleteExpiredBatch(NOW, 10)));
			assertThat(cleanup.get(5, TimeUnit.SECONDS)).isEqualTo(1);
			assertThat(failures(EMAIL, SOURCE)).isZero();
			release.countDown();
			holder.get(5, TimeUnit.SECONDS);
		}
		Integer reclaimed = transactionTemplate.execute(status -> throttleStore.deleteExpiredBatch(NOW, 10));
		assertThat(reclaimed).isOne();
	}

	@Test
	void stalePrincipalCannotCompleteOrResetTheThrottlePair() {
		insertUser();
		failures(EMAIL, SOURCE, 2);
		AccountPrincipal principal = principal();
		jdbcTemplate.update("UPDATE identity_user SET security_version = 1 WHERE email = ?", EMAIL);

		assertThat(service.completeSuccessfulLogin(principal, SOURCE)).isFalse();
		assertThat(lastLogin()).isNull();
		assertThat(failures(EMAIL, SOURCE)).isEqualTo(3);
	}

	@Test
	void changedEmailRoleOrDeletedAccountCannotCompleteOrResetTheThrottlePair() {
		for (String mutation : java.util.List.of(
				"UPDATE identity_user SET email = 'changed@example.test' WHERE email = 'integration@example.test'",
				"UPDATE identity_user SET role = 'EMPLOYEE' WHERE email = 'integration@example.test'",
				"DELETE FROM identity_user WHERE email = 'integration@example.test'")) {
			insertUser();
			failures(EMAIL, SOURCE, 2);
			AccountPrincipal principal = principal();
			jdbcTemplate.update(mutation);
			assertThat(service.completeSuccessfulLogin(principal, SOURCE)).isFalse();
			assertThat(failures(EMAIL, SOURCE)).isEqualTo(3);
			jdbcTemplate.update("DELETE FROM identity_user WHERE id = ?", principal.accountId());
			jdbcTemplate.update("DELETE FROM identity_login_throttle");
		}
	}

	@Test
	void guardedAccountUpdateReevaluatesEligibilityAfterWaitingForConcurrentChange() throws Exception {
		insertUser();
		Instant before = NOW.minusSeconds(60);
		jdbcTemplate.update("UPDATE identity_user SET created_at = ?, last_login_at = ?, updated_at = ? WHERE email = ?",
				java.sql.Timestamp.from(before), java.sql.Timestamp.from(before), java.sql.Timestamp.from(before), EMAIL);
		failures(EMAIL, SOURCE, 2);
		AccountPrincipal principal = principal();
		CountDownLatch changed = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicReference<Integer> changerPid = new AtomicReference<>();
		try (var executor = Executors.newFixedThreadPool(2)) {
			var changer = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
				changerPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
				jdbcTemplate.update("UPDATE identity_user SET status = 'BLOCKED', security_version = security_version + 1 WHERE email = ?", EMAIL);
				changed.countDown();
				await(release);
			}));
			assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
			var completion = executor.submit(() -> service.completeSuccessfulLogin(principal, SOURCE));
			try {
				assertThat(awaitAccountUpdateBlockedBy(changerPid.get())).isTrue();
			}
			finally {
				release.countDown();
			}
			assertThat(completion.get(5, TimeUnit.SECONDS)).isFalse();
			changer.get(5, TimeUnit.SECONDS);
		}
		assertThat(lastLogin()).isEqualTo(before);
		assertThat(updatedAt()).isEqualTo(before);
		assertThat(failures(EMAIL, SOURCE)).isEqualTo(3);
	}

	@Test
	void concurrentFailuresStopCredentialVerificationAtTheConfiguredThreshold() throws Exception {
		int callers = 10;
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger verifications = new AtomicInteger();
		try (var executor = Executors.newFixedThreadPool(callers)) {
			var futures = java.util.stream.IntStream.range(0, callers).mapToObj(ignored -> executor.submit(() -> {
				if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start latch timed out");
				assertThatThrownBy(() -> service.authenticate(EMAIL, SOURCE, () -> {
					verifications.incrementAndGet();
					throw new org.springframework.security.authentication.BadCredentialsException("wrong");
				})).isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
				return null;
			})).toList();
			start.countDown();
			for (var future : futures) future.get(5, TimeUnit.SECONDS);
		}

		assertThat(verifications).hasValue(5);
		assertThat(failures(EMAIL, SOURCE)).isEqualTo(5);
	}

	private void rejected() {
		service.authenticate(EMAIL, SOURCE, () -> {
			throw new org.springframework.security.authentication.BadCredentialsException("wrong");
		});
	}

	private void failures(String email, String source, int count) {
		for (int i = 0; i < count; i++) {
			assertThatThrownBy(() -> service.authenticate(email, source, () -> {
				throw new org.springframework.security.authentication.BadCredentialsException("wrong");
			})).isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
		}
	}

	private int failures(String email, String source) {
		return jdbcTemplate.query("""
				SELECT failed_attempts FROM identity_login_throttle
				WHERE identity_hash = ? AND source_address = ?::inet
				""", resultSet -> resultSet.next() ? resultSet.getInt(1) : 0,
				LoginAttemptKey.of(email, source).identityHash(), source);
	}

	private void insertUser() {
		new IdentityDatabaseFixture(jdbcTemplate).insertUser(UUID.randomUUID(), EMAIL,
				IdentityDatabaseFixture.TEST_PASSWORD_HASH, "CUSTOMER", "ACTIVE", 0, NOW, NOW, null);
	}

	private AccountPrincipal principal() {
		UUID id = jdbcTemplate.queryForObject("SELECT id FROM identity_user WHERE email = ?", UUID.class, EMAIL);
		return new AccountPrincipal(id, EMAIL, IdentityDatabaseFixture.TEST_PASSWORD_HASH, "CUSTOMER", "ACTIVE", 0);
	}

	private Instant lastLogin() {
		java.sql.Timestamp timestamp = jdbcTemplate.queryForObject("SELECT last_login_at FROM identity_user WHERE email = ?",
				(resultSet, row) -> resultSet.getTimestamp(1), EMAIL);
		return timestamp == null ? null : timestamp.toInstant();
	}

	private Instant updatedAt() {
		return jdbcTemplate.queryForObject("SELECT updated_at FROM identity_user WHERE email = ?",
				(resultSet, row) -> resultSet.getTimestamp(1).toInstant(), EMAIL);
	}

	private LoginAttemptService serviceWithFailingSave() {
		LoginThrottleStore failingStore = new LoginThrottleStore() {
			@Override public int deleteExpiredBatch(Instant now, int limit) { return throttleStore.deleteExpiredBatch(now, limit); }
			@Override public io.github.kubaj12.online_store.identityaccess.domain.LoginThrottleState lockOrCreate(LoginAttemptKey key,
					io.github.kubaj12.online_store.identityaccess.domain.LoginThrottleState initialState) {
				return throttleStore.lockOrCreate(key, initialState);
			}
			@Override public void save(LoginAttemptKey key, io.github.kubaj12.online_store.identityaccess.domain.LoginThrottleState state) {
				throw new IllegalStateException("synthetic reset failure");
			}
		};
		return new LoginAttemptService(failingStore, accountStore, policy, testClock(), new TransactionTemplate(transactionManager));
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	/** Observes PostgreSQL's actual lock graph rather than assuming a submitted worker has reached UPDATE. */
	private boolean awaitAccountUpdateBlockedBy(int blockerPid) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			Integer waiting = jdbcTemplate.queryForObject("""
					SELECT count(*) FROM pg_stat_activity
					WHERE wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid))
					""", Integer.class, blockerPid);
			if (waiting != null && waiting > 0) return true;
			try { Thread.sleep(10); }
			catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
		}
		return false;
	}
}
