package io.github.kubaj12.online_store.identityaccess.persistence;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.kubaj12.online_store.identityaccess.domain.NormalizedEmail;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlRepositoryTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@PostgreSqlRepositoryTest
@Import(JdbcInitialAdminAccountStore.class)
class JdbcInitialAdminAccountStoreTests {

	private static final Instant NOW = Instant.parse("2026-09-16T10:15:30.123456Z");

	@Autowired
	private JdbcInitialAdminAccountStore store;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Test
	void insertsCanonicalActiveAdminWithUtcTimestamps() {
		UUID id = UUID.randomUUID();
		NormalizedEmail email = NormalizedEmail.of(" Admin@Example.com ");

		assertThat(store.existsByEmail(email)).isFalse();
		assertThat(store.insertIfAbsent(id, email, "$2b$12$runtime-hash", NOW)).isTrue();

		var row = jdbcTemplate.queryForMap("SELECT * FROM identity_user WHERE id = ?", id);
		assertThat(row).containsEntry("email", "admin@example.com")
				.containsEntry("password_hash", "$2b$12$runtime-hash")
				.containsEntry("role", "ADMIN")
				.containsEntry("status", "ACTIVE")
				.containsEntry("security_version", 0L)
				.containsEntry("last_login_at", null);
		Instant createdAt = jdbcTemplate.queryForObject("SELECT created_at FROM identity_user WHERE id = ?",
				(resultSet, rowNumber) -> resultSet.getTimestamp("created_at").toInstant(), id);
		Instant updatedAt = jdbcTemplate.queryForObject("SELECT updated_at FROM identity_user WHERE id = ?",
				(resultSet, rowNumber) -> resultSet.getTimestamp("updated_at").toInstant(), id);
		assertThat(createdAt).isEqualTo(NOW);
		assertThat(updatedAt).isEqualTo(NOW);
		assertThat(store.existsByEmail(email)).isTrue();
	}

	@Test
	void namedEmailConflictPreservesExistingRow() {
		UUID id = UUID.randomUUID();
		IdentityDatabaseFixture fixture = new IdentityDatabaseFixture(jdbcTemplate);
		fixture.insertUser(id, "existing@example.test", "CUSTOMER", "BLOCKED", 7,
				NOW.minusSeconds(3 * 86_400L), NOW.minusSeconds(86_400L), NOW.minusSeconds(7_200L));

		assertThat(store.insertIfAbsent(UUID.randomUUID(), NormalizedEmail.of("existing@example.test"),
				"different-hash", NOW)).isFalse();
		var row = jdbcTemplate.queryForMap("SELECT * FROM identity_user WHERE id = ?", id);
		assertThat(row.get("role")).isEqualTo("CUSTOMER");
		assertThat(row.get("status")).isEqualTo("BLOCKED");
		assertThat(row.get("security_version")).isEqualTo(7L);
		assertThat(row.get("password_hash")).isEqualTo(IdentityDatabaseFixture.TEST_PASSWORD_HASH);
	}

	@Test
	void mandatoryInsertCannotRunWithoutAnActiveTransaction() {
		TransactionTemplate nonTransactional = new TransactionTemplate(transactionTemplate.getTransactionManager());
		nonTransactional.setPropagationBehavior(TransactionTemplate.PROPAGATION_NOT_SUPPORTED);

		assertThatThrownBy(() -> nonTransactional.execute(status -> store.insertIfAbsent(
				UUID.randomUUID(), NormalizedEmail.of("mandatory@example.test"), "hash", NOW)))
				.hasMessageContaining("existing transaction");
		assertThat(store.existsByEmail(NormalizedEmail.of("mandatory@example.test"))).isFalse();
	}

}
