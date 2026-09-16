package io.github.kubaj12.online_store.identityaccess.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.kubaj12.online_store.identityaccess.application.InitialAdminAccountStore;
import io.github.kubaj12.online_store.identityaccess.domain.NormalizedEmail;

@Repository
public class JdbcInitialAdminAccountStore implements InitialAdminAccountStore {

	private final JdbcTemplate jdbcTemplate;

	public JdbcInitialAdminAccountStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public boolean existsByEmail(NormalizedEmail email) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM identity_user WHERE email = ?)",
				Boolean.class,
				email.value()));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean insertIfAbsent(UUID id, NormalizedEmail email, String passwordHash, Instant now) {
		int count = jdbcTemplate.update("""
				INSERT INTO identity_user (
					id, email, password_hash, role, status, security_version,
					last_login_at, created_at, updated_at
				) VALUES (?, ?, ?, 'ADMIN', 'ACTIVE', 0, NULL, ?, ?)
				ON CONFLICT ON CONSTRAINT identity_user_email_uq DO NOTHING
				""", id, email.value(), passwordHash, utc(now), utc(now));
		if (count == 1) {
			return true;
		}
		if (count == 0) {
			return false;
		}
		throw new IllegalStateException("initial administrator insert returned an unexpected update count");
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

}
