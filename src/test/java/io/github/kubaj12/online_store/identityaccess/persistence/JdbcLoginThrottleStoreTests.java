package io.github.kubaj12.online_store.identityaccess.persistence;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.kubaj12.online_store.identityaccess.domain.LoginAttemptKey;
import io.github.kubaj12.online_store.identityaccess.domain.LoginThrottlePolicy;
import io.github.kubaj12.online_store.identityaccess.domain.LoginThrottleState;
import io.github.kubaj12.online_store.testsupport.PostgreSqlRepositoryTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@PostgreSqlRepositoryTest
@Import(JdbcLoginThrottleStore.class)
class JdbcLoginThrottleStoreTests {
	private static final Instant NOW = Instant.parse("2026-01-15T10:15:30.123456Z");
	@Autowired private JdbcLoginThrottleStore store;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private TransactionTemplate transactionTemplate;

	@Test
	void conflictingUpsertLocksAndPreservesTheExistingLifecycleState() {
		LoginAttemptKey key = LoginAttemptKey.of("USER@example.test", "192.0.2.1");
		LoginThrottlePolicy policy = new LoginThrottlePolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofHours(24));
		LoginThrottleState first = policy.recordFailure(policy.empty(NOW), NOW);
		store.lockOrCreate(key, first);
		LoginThrottleState existing = store.lockOrCreate(key, policy.empty(NOW.plusSeconds(1)));
		assertThat(existing.failedAttempts()).isOne();
		assertThat(existing.createdAt()).isEqualTo(NOW);
		assertThat(existing.lastFailedAt()).isEqualTo(NOW);
		assertThat(existing.windowStartedAt()).isEqualTo(NOW);
	}

	@Test
	void cleanupUsesExpiryAndRespectsBatchLimit() {
		LoginThrottlePolicy policy = new LoginThrottlePolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofHours(24));
		for (int i = 0; i < 3; i++) {
			LoginAttemptKey key = LoginAttemptKey.of("expired" + i + "@example.test", "192.0.2.1");
			LoginThrottleState state = policy.empty(NOW.minus(Duration.ofHours(25)));
			store.lockOrCreate(key, state);
		}
		assertThat(store.deleteExpiredBatch(NOW, 2)).isEqualTo(2);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM identity_login_throttle", Integer.class)).isEqualTo(1);
	}

	@Test
	void methodsRequireAnActiveTransaction() {
		TransactionTemplate nonTransactional = new TransactionTemplate(transactionTemplate.getTransactionManager());
		nonTransactional.setPropagationBehavior(TransactionTemplate.PROPAGATION_NOT_SUPPORTED);
		assertThatThrownBy(() -> nonTransactional.executeWithoutResult(status -> store.deleteExpiredBatch(NOW, 1)))
				.hasMessageContaining("existing transaction");
		LoginAttemptKey key = LoginAttemptKey.of("mandatory@example.test", "192.0.2.2");
		LoginThrottleState state = LoginThrottleState.empty(NOW, NOW.plus(Duration.ofHours(1)));
		assertThatThrownBy(() -> nonTransactional.executeWithoutResult(status -> store.lockOrCreate(key, state)))
				.hasMessageContaining("existing transaction");
		assertThatThrownBy(() -> nonTransactional.executeWithoutResult(status -> store.save(key, state)))
				.hasMessageContaining("existing transaction");
	}

	@Test
	void roundTripsBlockedStateAndIpv6SourceWithoutCrossPairing() {
		LoginAttemptKey key = LoginAttemptKey.of("blocked@example.test", "2001:db8::42");
		LoginThrottlePolicy policy = new LoginThrottlePolicy(2, Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofHours(24));
		LoginThrottleState blocked = policy.recordFailure(policy.recordFailure(policy.empty(NOW), NOW), NOW);

		store.lockOrCreate(key, blocked);
		LoginThrottleState loaded = store.lockOrCreate(key, policy.empty(NOW.plusSeconds(1)));

		assertThat(loaded.failedAttempts()).isEqualTo(2);
		assertThat(loaded.lastFailedAt()).isEqualTo(NOW);
		assertThat(loaded.blockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
		assertThat(loaded.expiresAt()).isEqualTo(blocked.expiresAt());
	}
}
