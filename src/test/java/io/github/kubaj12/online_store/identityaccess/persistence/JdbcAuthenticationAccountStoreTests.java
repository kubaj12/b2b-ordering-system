package io.github.kubaj12.online_store.identityaccess.persistence;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.kubaj12.online_store.identityaccess.application.AccountPrincipal;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlRepositoryTest;

import static org.assertj.core.api.Assertions.assertThat;

@PostgreSqlRepositoryTest
@Import(JdbcAuthenticationAccountStore.class)
class JdbcAuthenticationAccountStoreTests {
	private static final Instant CREATED = Instant.parse("2026-01-15T10:00:00.123456Z");
	private static final Instant LOGIN = Instant.parse("2026-01-15T10:15:30.123456Z");
	@Autowired private JdbcAuthenticationAccountStore store;
	@Autowired private JdbcTemplate jdbcTemplate;

	@Test
	void guardedSuccessfulLoginUpdatesLifecycleTimestampsAndPreservesIdentityFields() {
		UUID id = UUID.randomUUID();
		String hash = IdentityDatabaseFixture.TEST_PASSWORD_HASH;
		new IdentityDatabaseFixture(jdbcTemplate).insertUser(id, "real@example.test", hash,
				"CUSTOMER", "ACTIVE", 4, CREATED, CREATED, null);
		AccountPrincipal principal = new AccountPrincipal(id, "real@example.test", hash, "CUSTOMER", "ACTIVE", 4);
		assertThat(store.updateSuccessfulLogin(principal, LOGIN)).isTrue();
		var row = jdbcTemplate.queryForMap("SELECT password_hash, role, status, security_version, created_at, updated_at, last_login_at FROM identity_user WHERE id = ?", id);
		assertThat(row).containsEntry("password_hash", hash).containsEntry("role", "CUSTOMER")
				.containsEntry("status", "ACTIVE").containsEntry("security_version", 4L);
		assertThat(((java.sql.Timestamp) row.get("created_at")).toInstant()).isEqualTo(CREATED);
		assertThat(((java.sql.Timestamp) row.get("updated_at")).toInstant()).isEqualTo(LOGIN);
		assertThat(((java.sql.Timestamp) row.get("last_login_at")).toInstant()).isEqualTo(LOGIN);
	}

	@Test
	void stalePrincipalDoesNotUpdateLoginTimestamp() {
		UUID id = UUID.randomUUID();
		new IdentityDatabaseFixture(jdbcTemplate).insertUser(id, "stale@example.test", IdentityDatabaseFixture.TEST_PASSWORD_HASH,
				"CUSTOMER", "BLOCKED", 2, CREATED, CREATED, null);
		AccountPrincipal principal = new AccountPrincipal(id, "stale@example.test", IdentityDatabaseFixture.TEST_PASSWORD_HASH,
				"CUSTOMER", "BLOCKED", 2);
		assertThat(store.updateSuccessfulLogin(principal, LOGIN)).isFalse();
		java.sql.Timestamp lastLogin = jdbcTemplate.queryForObject("SELECT last_login_at FROM identity_user WHERE id = ?",
				(resultSet, row) -> resultSet.getTimestamp(1), id);
		assertThat(lastLogin).isNull();
	}

	@Test
	void successfulLoginTimestampDoesNotMoveBackward() {
		UUID id = UUID.randomUUID();
		new IdentityDatabaseFixture(jdbcTemplate).insertUser(id, "monotonic@example.test",
				IdentityDatabaseFixture.TEST_PASSWORD_HASH, "CUSTOMER", "ACTIVE", 0, CREATED, CREATED, LOGIN);
		AccountPrincipal principal = new AccountPrincipal(id, "monotonic@example.test",
				IdentityDatabaseFixture.TEST_PASSWORD_HASH, "CUSTOMER", "ACTIVE", 0);
		Instant future = LOGIN.plusSeconds(60);

		assertThat(store.updateSuccessfulLogin(principal, future)).isTrue();
		assertThat(store.updateSuccessfulLogin(principal, LOGIN)).isTrue();
		java.sql.Timestamp lastLogin = jdbcTemplate.queryForObject("SELECT last_login_at FROM identity_user WHERE id = ?",
				(resultSet, row) -> resultSet.getTimestamp(1), id);
		assertThat(lastLogin.toInstant()).isEqualTo(future);
	}
}
