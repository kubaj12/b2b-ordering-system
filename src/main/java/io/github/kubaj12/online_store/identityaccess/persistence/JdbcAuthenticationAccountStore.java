package io.github.kubaj12.online_store.identityaccess.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.kubaj12.online_store.identityaccess.application.AuthenticationAccountStore;
import io.github.kubaj12.online_store.identityaccess.application.AccountPrincipal;
import io.github.kubaj12.online_store.identityaccess.domain.NormalizedEmail;

@Repository
public class JdbcAuthenticationAccountStore implements AuthenticationAccountStore {

	private final JdbcTemplate jdbcTemplate;

	public JdbcAuthenticationAccountStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public Optional<Credentials> findCredentialsByEmail(NormalizedEmail email) {
		return jdbcTemplate.query("""
				SELECT id, email, password_hash, role, status, security_version
				FROM identity_user WHERE email = ?
				""", this::credentials, email.value()).stream().findFirst();
	}

	@Override
	public Optional<AccessSnapshot> findAccessById(UUID accountId) {
		return jdbcTemplate.query("""
				SELECT id, email, role, status, security_version
				FROM identity_user WHERE id = ?
				""", this::access, accountId).stream().findFirst();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean updateSuccessfulLogin(AccountPrincipal principal, Instant now) {
		OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
		int count = jdbcTemplate.update("""
				UPDATE identity_user
				SET last_login_at = GREATEST(?, created_at, updated_at, last_login_at),
					updated_at = GREATEST(?, created_at, updated_at, last_login_at)
				WHERE id = ? AND email = ? AND role = ? AND status = 'ACTIVE' AND security_version = ?
				""", timestamp, timestamp, principal.accountId(), principal.email(), principal.role(), principal.securityVersion());
		if (count == 0) return false;
		if (count == 1) return true;
		throw new IllegalStateException("successful login update returned an unexpected update count");
	}

	private Credentials credentials(ResultSet resultSet, int row) throws SQLException {
		return new Credentials(resultSet.getObject("id", UUID.class), resultSet.getString("email"),
				resultSet.getString("password_hash"), resultSet.getString("role"), resultSet.getString("status"),
				resultSet.getLong("security_version"));
	}

	private AccessSnapshot access(ResultSet resultSet, int row) throws SQLException {
		return new AccessSnapshot(resultSet.getObject("id", UUID.class), resultSet.getString("email"),
				resultSet.getString("role"), resultSet.getString("status"), resultSet.getLong("security_version"));
	}
}
