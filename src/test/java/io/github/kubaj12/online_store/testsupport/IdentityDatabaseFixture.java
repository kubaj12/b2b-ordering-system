package io.github.kubaj12.online_store.testsupport;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC helpers for identity rows needed before identity services exist. */
public final class IdentityDatabaseFixture {

	public static final String TEST_PASSWORD_HASH =
			"$2a$10$0QnK7v6cc3ltQwvMNnzwFeEcTdmYDnBQmAm9q20oR3VbPNYzHvr56";

	private final JdbcTemplate jdbcTemplate;

	public IdentityDatabaseFixture(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void insertActiveUser(UUID id, String email, String role, Instant now) {
		insertUser(id, email, role, "ACTIVE", 0L, now, now, null);
	}

	public void insertUser(
			UUID id,
			String email,
			String role,
			String status,
			long securityVersion,
			Instant createdAt,
			Instant updatedAt,
			Instant lastLoginAt
	) {
		insertUser(id, email, TEST_PASSWORD_HASH, role, status, securityVersion, createdAt, updatedAt, lastLoginAt);
	}

	public void insertUser(
			UUID id,
			String email,
			String passwordHash,
			String role,
			String status,
			long securityVersion,
			Instant createdAt,
			Instant updatedAt,
			Instant lastLoginAt
	) {
		jdbcTemplate.update("""
				INSERT INTO identity_user (
					id,
					email,
					password_hash,
					role,
					status,
					security_version,
					last_login_at,
					created_at,
					updated_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				id,
				email,
				passwordHash,
				role,
				status,
				securityVersion,
				offset(lastLoginAt),
				offset(createdAt),
				offset(updatedAt)
		);
	}

	private static OffsetDateTime offset(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

}
