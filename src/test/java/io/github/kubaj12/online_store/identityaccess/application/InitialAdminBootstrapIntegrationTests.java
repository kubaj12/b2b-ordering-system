package io.github.kubaj12.online_store.identityaccess.application;

import java.util.List;
import java.util.UUID;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import io.github.kubaj12.online_store.identityaccess.application.InitialAdminBootstrapService.Outcome;
import io.github.kubaj12.online_store.identityaccess.persistence.JdbcInitialAdminAccountStore;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class InitialAdminBootstrapIntegrationTests extends PostgreSqlServiceTestSupport {

	private static final String EMAIL = "seed@example.test";
	private static final String FIRST_PASSWORD = "first-bootstrap-password";
	private static final String SECOND_PASSWORD = "second-bootstrap-password";

	@Autowired
	private InitialAdminBootstrapService service;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JdbcInitialAdminAccountStore realStore;

	@Autowired
	private Clock clock;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Autowired
	private DataSource dataSource;

	@Test
	void secondInvocationPreservesTheCompleteCreatedAccount() {
		testClock().set(java.time.Instant.parse("2026-09-16T10:15:30.123456789Z"));
		assertThat(service.bootstrap(EMAIL, FIRST_PASSWORD)).isEqualTo(Outcome.CREATED);
		var before = jdbcTemplate.queryForMap("SELECT * FROM identity_user WHERE email = ?", EMAIL);

		testClock().advance(java.time.Duration.ofDays(1));
		assertThat(service.bootstrap(EMAIL, SECOND_PASSWORD)).isEqualTo(Outcome.ALREADY_EXISTS);
		var after = jdbcTemplate.queryForMap("SELECT * FROM identity_user WHERE email = ?", EMAIL);

		assertThat(after).isEqualTo(before);
		assertThat(passwordEncoder.matches(FIRST_PASSWORD, (String) before.get("password_hash"))).isTrue();
		assertThat(passwordEncoder.matches(SECOND_PASSWORD, (String) before.get("password_hash"))).isFalse();
	}

	@ParameterizedTest
	@CsvSource({"CUSTOMER,ACTIVE", "CUSTOMER,BLOCKED", "EMPLOYEE,ACTIVE", "EMPLOYEE,BLOCKED", "ADMIN,ACTIVE", "ADMIN,BLOCKED"})
	void collisionPreservesEveryExistingAccountColumn(String role, String status) {
		UUID id = UUID.randomUUID();
		java.time.Instant now = java.time.Instant.parse("2026-01-01T10:00:00Z");
		new IdentityDatabaseFixture(jdbcTemplate).insertUser(id, EMAIL, role, status, 8,
				now.minusSeconds(100), now.minusSeconds(10), now.minusSeconds(5));
		var before = jdbcTemplate.queryForMap("SELECT * FROM identity_user WHERE id = ?", id);

		assertThat(service.bootstrap(" SEED@EXAMPLE.TEST ", SECOND_PASSWORD)).isEqualTo(Outcome.ALREADY_EXISTS);
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM identity_user WHERE id = ?", id)).isEqualTo(before);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM identity_user WHERE email = ?", Integer.class, EMAIL))
				.isOne();
	}

	@Test
	void concurrentCallsCreateExactlyOneAccount() throws Exception {
		LookupGate gate = new LookupGate(realStore, 2);
		InitialAdminBootstrapService firstService = service(gate);
		InitialAdminBootstrapService secondService = service(gate);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<Outcome>> results = List.of(
				executor.submit(() -> firstService.bootstrap(EMAIL, FIRST_PASSWORD)),
				executor.submit(() -> secondService.bootstrap(EMAIL, SECOND_PASSWORD))
			);
			assertThat(gate.lookups.await(30, TimeUnit.SECONDS)).isTrue();
			gate.release.countDown();
			Outcome first = results.get(0).get(30, TimeUnit.SECONDS);
			Outcome second = results.get(1).get(30, TimeUnit.SECONDS);
			assertThat(List.of(first, second)).containsExactlyInAnyOrder(Outcome.CREATED, Outcome.ALREADY_EXISTS);
			assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM identity_user WHERE email = ?", Integer.class, EMAIL))
					.isOne();
			String storedHash = jdbcTemplate.queryForObject("SELECT password_hash FROM identity_user WHERE email = ?", String.class, EMAIL);
			assertThat(passwordEncoder.matches(first == Outcome.CREATED ? FIRST_PASSWORD : SECOND_PASSWORD, storedHash)).isTrue();
			assertThat(passwordEncoder.matches(first == Outcome.CREATED ? SECOND_PASSWORD : FIRST_PASSWORD, storedHash)).isFalse();
		}
		finally {
			gate.release.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void committedOrdinaryCollisionWinsWhileBootstrapInsertWaits() throws Exception {
		assertCompetingInsertOutcome(true, Outcome.ALREADY_EXISTS, "CUSTOMER", "BLOCKED");
	}

	@Test
	void rolledBackOrdinaryCollisionAllowsBootstrapToCreate() throws Exception {
		assertCompetingInsertOutcome(false, Outcome.CREATED, "EMPLOYEE", "ACTIVE");
	}

	private void assertCompetingInsertOutcome(boolean commit, Outcome expected, String role, String status)
			throws Exception {
		String email = "competing@example.test";
		Instant now = Instant.parse("2026-09-16T10:00:00Z");
		Connection connection = dataSource.getConnection();
		connection.setAutoCommit(false);
		try {
			try (var statement = connection.prepareStatement("""
					INSERT INTO identity_user (
						id, email, password_hash, role, status, security_version,
						last_login_at, created_at, updated_at
					) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
					""")) {
				statement.setObject(1, UUID.randomUUID());
				statement.setString(2, email);
				statement.setString(3, IdentityDatabaseFixture.TEST_PASSWORD_HASH);
				statement.setString(4, role);
				statement.setString(5, status);
				statement.setLong(6, 3);
				statement.setObject(7, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
				statement.setObject(8, OffsetDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC));
				statement.setObject(9, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
				statement.executeUpdate();
			}

			LookupGate gate = new LookupGate(realStore, 1);
			ExecutorService executor = Executors.newSingleThreadExecutor();
			try {
				Future<Outcome> result = executor.submit(() -> service(gate).bootstrap(email, FIRST_PASSWORD));
				assertThat(gate.lookups.await(30, TimeUnit.SECONDS)).isTrue();
				gate.release.countDown();
				assertThat(gate.insertStarted.await(30, TimeUnit.SECONDS)).isTrue();
				if (commit) {
					connection.commit();
				}
				else {
					connection.rollback();
				}
				assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(expected);
				assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM identity_user WHERE email = ?", Integer.class, email))
						.isOne();
			}
			finally {
				gate.release.countDown();
				executor.shutdownNow();
				assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
			}
		}
		finally {
			if (!connection.getAutoCommit()) {
				connection.rollback();
			}
			connection.close();
		}
	}

	private InitialAdminBootstrapService service(InitialAdminAccountStore store) {
		return new InitialAdminBootstrapService(store, passwordEncoder, clock, transactionTemplate);
	}

	private static final class LookupGate implements InitialAdminAccountStore {

		private final InitialAdminAccountStore delegate;
		private final CountDownLatch lookups;
		private final CountDownLatch release = new CountDownLatch(1);
		private final CountDownLatch insertStarted = new CountDownLatch(1);

		private LookupGate(InitialAdminAccountStore delegate, int expectedLookups) {
			this.delegate = delegate;
			this.lookups = new CountDownLatch(expectedLookups);
		}

		@Override
		public boolean existsByEmail(io.github.kubaj12.online_store.identityaccess.domain.NormalizedEmail email) {
			boolean exists = delegate.existsByEmail(email);
			if (!exists) {
				lookups.countDown();
				try {
					if (!release.await(30, TimeUnit.SECONDS)) {
						throw new IllegalStateException("bootstrap lookup gate timed out");
					}
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("bootstrap lookup gate interrupted", exception);
				}
			}
			return exists;
		}

		@Override
		public boolean insertIfAbsent(UUID id, io.github.kubaj12.online_store.identityaccess.domain.NormalizedEmail email,
				String passwordHash, Instant now) {
			insertStarted.countDown();
			return delegate.insertIfAbsent(id, email, passwordHash, now);
		}
	}

}
