package io.github.kubaj12.online_store.identityaccess.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import io.github.kubaj12.online_store.identityaccess.application.AuthenticationAccountStore;
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
