package io.github.kubaj12.online_store.identityaccess.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.kubaj12.online_store.identityaccess.application.LoginThrottleStore;
import io.github.kubaj12.online_store.identityaccess.domain.LoginAttemptKey;
import io.github.kubaj12.online_store.identityaccess.domain.LoginThrottleState;

@Repository
public class JdbcLoginThrottleStore implements LoginThrottleStore {
	private final JdbcTemplate jdbcTemplate;
	public JdbcLoginThrottleStore(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public int deleteExpiredBatch(Instant now, int limit) {
		if (limit <= 0) return 0;
		return jdbcTemplate.update("""
				WITH candidates AS (
					SELECT identity_hash, source_address FROM identity_login_throttle
					WHERE expires_at <= ? ORDER BY expires_at, identity_hash, source_address
					LIMIT ? FOR UPDATE SKIP LOCKED
				)
				DELETE FROM identity_login_throttle t USING candidates c
				WHERE t.identity_hash = c.identity_hash AND t.source_address = c.source_address
				""", utc(now), limit);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public LoginThrottleState lockOrCreate(LoginAttemptKey key, LoginThrottleState initialState) {
		jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'");
		return jdbcTemplate.query("""
				INSERT INTO identity_login_throttle
				(identity_hash, source_address, failed_attempts, window_started_at, last_failed_at,
				 blocked_until, created_at, updated_at, expires_at)
				VALUES (?, ?::INET, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (identity_hash, source_address) DO UPDATE
				SET identity_hash = identity_login_throttle.identity_hash
				RETURNING failed_attempts, window_started_at, last_failed_at, blocked_until,
				          created_at, updated_at, expires_at
				""", this::state, key.identityHash(), key.sourceAddress(), initialState.failedAttempts(), utc(initialState.windowStartedAt()),
				nullable(initialState.lastFailedAt()), nullable(initialState.blockedUntil()), utc(initialState.createdAt()),
				utc(initialState.updatedAt()), utc(initialState.expiresAt())).stream().findFirst()
				.orElseThrow(() -> new IllegalStateException("login throttle row disappeared while locked"));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void save(LoginAttemptKey key, LoginThrottleState state) {
		int count = jdbcTemplate.update("""
				UPDATE identity_login_throttle SET failed_attempts = ?, window_started_at = ?, last_failed_at = ?,
				blocked_until = ?, updated_at = ?, expires_at = ?
				WHERE identity_hash = ? AND source_address = ?::INET
				""", state.failedAttempts(), utc(state.windowStartedAt()), nullable(state.lastFailedAt()),
				nullable(state.blockedUntil()), utc(state.updatedAt()), utc(state.expiresAt()), key.identityHash(), key.sourceAddress());
		if (count != 1) throw new IllegalStateException("login throttle save updated an unexpected number of rows");
	}

	private LoginThrottleState state(ResultSet rs, int row) throws SQLException {
		return new LoginThrottleState(rs.getInt("failed_attempts"), instant(rs, "window_started_at"),
				instant(rs, "last_failed_at"), instant(rs, "blocked_until"), instant(rs, "created_at"),
				instant(rs, "updated_at"), instant(rs, "expires_at"));
	}
	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
	private static OffsetDateTime utc(Instant value) { return OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }
	private static OffsetDateTime nullable(Instant value) { return value == null ? null : utc(value); }
}
