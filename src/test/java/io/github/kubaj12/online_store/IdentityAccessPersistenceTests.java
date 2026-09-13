package io.github.kubaj12.online_store;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityAccessPersistenceTests extends PostgreSqlServiceTestSupport {

	private static final Instant NOW = Instant.parse("2026-09-07T08:15:30.123456Z");
	private static final UUID ADMIN_ID = UUID.fromString("0198e913-7dd8-7ee6-80c8-d86326174674");
	private static final UUID EMPLOYEE_ID = UUID.fromString("0198e913-7dd8-7ee6-80c8-d86326174675");
	private static final UUID CUSTOMER_ID = UUID.fromString("0198e913-7dd8-7ee6-80c8-d86326174676");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private IdentityDatabaseFixture users;

	@BeforeEach
	void insertUsers() {
		users = new IdentityDatabaseFixture(jdbcTemplate);
		users.insertActiveUser(ADMIN_ID, "admin@example.test", "ADMIN", NOW);
		users.insertActiveUser(EMPLOYEE_ID, "employee@example.test", "EMPLOYEE", NOW);
		users.insertActiveUser(CUSTOMER_ID, "customer@example.test", "CUSTOMER", NOW);
	}

	@Test
	void accountRulesRequireCanonicalUniqueCompleteRows() {
		insertUser(UUID.randomUUID(), "buyer@example.test", "CUSTOMER", "ACTIVE", 0, NOW, NOW, null);

		assertSqlFailure(
				() -> insertUser(UUID.randomUUID(), "buyer@example.test", "CUSTOMER", "ACTIVE", 0, NOW, NOW, null),
				"23505",
				"identity_user_email_uq"
		);
		assertSqlFailure(
				() -> insertUser(UUID.randomUUID(), "Buyer@example.test", "CUSTOMER", "ACTIVE", 0, NOW, NOW, null),
				"23514",
				"identity_user_email_canonical"
		);
		assertSqlFailure(
				() -> insertUser(UUID.randomUUID(), " buyer@example.test ", "CUSTOMER", "ACTIVE", 0, NOW, NOW, null),
				"23514",
				"identity_user_email_canonical"
		);
		assertSqlFailure(
				() -> insertUser(UUID.randomUUID(), "", "CUSTOMER", "ACTIVE", 0, NOW, NOW, null),
				"23514",
				"identity_user_email_canonical"
		);
		assertSqlFailure(
				() -> insertUser(UUID.randomUUID(), "bad user@example.test", "CUSTOMER", "ACTIVE", 0, NOW, NOW, null),
				"23514",
				"identity_user_email_canonical"
		);
		assertSqlFailure(
				() -> insertUser(UUID.randomUUID(), "role@example.test", "OWNER", "ACTIVE", 0, NOW, NOW, null),
				"23514",
				"identity_user_role_valid"
		);
		assertSqlFailure(
				() -> insertUser(UUID.randomUUID(), "status@example.test", "CUSTOMER", "INVITED", 0, NOW, NOW, null),
				"23514",
				"identity_user_status_valid"
		);
		assertSqlFailure(
				() -> insertUser(UUID.randomUUID(), "version@example.test", "CUSTOMER", "ACTIVE", -1, NOW, NOW, null),
				"23514",
				"identity_user_security_version_nonnegative"
		);
		assertSqlFailure(
				() -> insertUser(UUID.randomUUID(), "updated@example.test", "CUSTOMER", "ACTIVE", 0, NOW, NOW.minusSeconds(1), null),
				"23514",
				"identity_user_updated_at_chronological"
		);
		assertSqlFailure(
				() -> insertUser(UUID.randomUUID(), "login@example.test", "CUSTOMER", "ACTIVE", 0, NOW, NOW, NOW.minusSeconds(1)),
				"23514",
				"identity_user_last_login_at_chronological"
		);
		assertSqlFailure(
				() -> jdbcTemplate.update("""
						INSERT INTO identity_user (id, email, password_hash, role, status, created_at, updated_at)
						VALUES (?, ?, ?, ?, ?, ?, ?)
						""",
						UUID.randomUUID(),
						"missing@example.test",
						IdentityDatabaseFixture.TEST_PASSWORD_HASH,
						"CUSTOMER",
						null,
						offset(NOW),
						offset(NOW)),
				"23502",
				"status"
		);
		assertSqlFailure(
				() -> jdbcTemplate.update("""
						INSERT INTO identity_user (id, email, password_hash, role, status, created_at, updated_at)
						VALUES (?, ?, ?, ?, ?, ?, ?)
						""",
						UUID.randomUUID(),
						"blankhash@example.test",
						"   ",
						"CUSTOMER",
						"ACTIVE",
						offset(NOW),
						offset(NOW)),
				"23514",
				"identity_user_password_hash_nonblank"
		);
	}

	@Test
	void sessionsStoreHashedIdentifiersAndAccountVersionSnapshots() {
		UUID firstSession = UUID.randomUUID();
		UUID secondSession = UUID.randomUUID();
		insertSession(firstSession, bytes(1), CUSTOMER_ID, 0, NOW, NOW, NOW, NOW.plusSeconds(3600), null);
		insertSession(secondSession, bytes(2), CUSTOMER_ID, 0, NOW, NOW.plusSeconds(30), NOW.plusSeconds(30), NOW.plusSeconds(3600), null);

		jdbcTemplate.update(
				"UPDATE identity_user SET security_version = 1, updated_at = ? WHERE id = ?",
				offset(NOW.plusSeconds(60)),
				CUSTOMER_ID
		);

		Long storedVersionSum = jdbcTemplate.queryForObject(
				"SELECT SUM(security_version) FROM identity_session WHERE user_id = ?",
				Long.class,
				CUSTOMER_ID
		);
		assertThat(storedVersionSum).isZero();
		assertThat(activeSessionIds(NOW.plusSeconds(120))).isEmpty();

		insertSession(UUID.randomUUID(), bytes(3), CUSTOMER_ID, 1, NOW, NOW, NOW, NOW.plusSeconds(3600), null);
		assertThat(activeSessionIds(NOW.plusSeconds(120))).containsExactly(3);

		jdbcTemplate.update("UPDATE identity_user SET status = 'BLOCKED', security_version = 2, updated_at = ? WHERE id = ?",
				offset(NOW.plusSeconds(180)), CUSTOMER_ID);
		assertThat(activeSessionIds(NOW.plusSeconds(240))).isEmpty();

		jdbcTemplate.update("UPDATE identity_user SET status = 'ACTIVE', updated_at = ? WHERE id = ?",
				offset(NOW.plusSeconds(300)), CUSTOMER_ID);
		assertThat(activeSessionIds(NOW.plusSeconds(360))).isEmpty();

		assertSqlFailure(
				() -> insertSession(UUID.randomUUID(), bytes(1), CUSTOMER_ID, 0, NOW, NOW, NOW, NOW.plusSeconds(3600), null),
				"23505",
				"identity_session_session_id_hash_uq"
		);
		assertSqlFailure(
				() -> insertSession(UUID.randomUUID(), bytes(4, 31), CUSTOMER_ID, 0, NOW, NOW, NOW, NOW.plusSeconds(3600), null),
				"23514",
				"identity_session_session_id_hash_length"
		);
		assertSqlFailure(
				() -> insertSession(UUID.randomUUID(), bytes(4), UUID.randomUUID(), 0, NOW, NOW, NOW, NOW.plusSeconds(3600), null),
				"23503",
				"identity_session_user_id_fk"
		);
		assertSqlFailure(
				() -> insertSession(UUID.randomUUID(), bytes(5), CUSTOMER_ID, -1, NOW, NOW, NOW, NOW.plusSeconds(3600), null),
				"23514",
				"identity_session_security_version_nonnegative"
		);
		assertSqlFailure(
				() -> insertSession(UUID.randomUUID(), bytes(6), CUSTOMER_ID, 0, NOW, NOW.minusSeconds(1), NOW, NOW.plusSeconds(3600), null),
				"23514",
				"identity_session_updated_at_chronological"
		);
		assertSqlFailure(
				() -> insertSession(UUID.randomUUID(), bytes(7), CUSTOMER_ID, 0, NOW, NOW, NOW.minusSeconds(1), NOW.plusSeconds(3600), null),
				"23514",
				"identity_session_last_seen_at_chronological"
		);
		assertSqlFailure(
				() -> insertSession(UUID.randomUUID(), bytes(71), CUSTOMER_ID, 0, NOW, NOW, NOW.plusSeconds(1), NOW.plusSeconds(3600), null),
				"23514",
				"identity_session_lifecycle_timestamps_not_after_updated_at"
		);
		assertSqlFailure(
				() -> insertSession(UUID.randomUUID(), bytes(8), CUSTOMER_ID, 0, NOW, NOW, NOW, NOW, null),
				"23514",
				"identity_session_expires_at_chronological"
		);
		assertSqlFailure(
				() -> insertSession(UUID.randomUUID(), bytes(9), CUSTOMER_ID, 0, NOW, NOW, NOW, NOW.plusSeconds(3600), NOW.minusSeconds(1)),
				"23514",
				"identity_session_revoked_at_chronological"
		);
		assertSqlFailure(
				() -> insertSession(UUID.randomUUID(), bytes(72), CUSTOMER_ID, 0, NOW, NOW, NOW, NOW.plusSeconds(3600), NOW.plusSeconds(1)),
				"23514",
				"identity_session_lifecycle_timestamps_not_after_updated_at"
		);
	}

	@Test
	void invitationRulesSupportOnePendingInvitationPerEmailAndTerminalHistory() {
		insertInvitation(UUID.randomUUID(), "new@example.test", "CUSTOMER", "PENDING", bytes(10), EMPLOYEE_ID, null,
				NOW, NOW, NOW.plusSeconds(604800), null, null);

		assertSqlFailure(
				() -> insertInvitation(UUID.randomUUID(), "new@example.test", "EMPLOYEE", "PENDING", bytes(11), ADMIN_ID, null,
						NOW, NOW, NOW.plusSeconds(604800), null, null),
				"23505",
				"identity_invitation_pending_email_uq"
		);

		jdbcTemplate.update(
				"UPDATE identity_invitation SET status = 'REVOKED', revoked_at = ?, updated_at = ? WHERE email = ?",
				offset(NOW.plusSeconds(30)),
				offset(NOW.plusSeconds(30)),
				"new@example.test"
		);
		insertInvitation(UUID.randomUUID(), "new@example.test", "EMPLOYEE", "PENDING", bytes(12), ADMIN_ID, null,
				NOW.plusSeconds(60), NOW.plusSeconds(60), NOW.plusSeconds(604860), null, null);

		insertInvitation(UUID.randomUUID(), "accepted@example.test", "CUSTOMER", "ACCEPTED", bytes(13), EMPLOYEE_ID, CUSTOMER_ID,
				NOW, NOW.plusSeconds(100), NOW.plusSeconds(604800), NOW.plusSeconds(100), null);
		insertInvitation(UUID.randomUUID(), "accepted@example.test", "CUSTOMER", "PENDING", bytes(14), EMPLOYEE_ID, null,
				NOW.plusSeconds(200), NOW.plusSeconds(200), NOW.plusSeconds(605000), null, null);
		insertInvitation(UUID.randomUUID(), "expired@example.test", "CUSTOMER", "EXPIRED", bytes(15), EMPLOYEE_ID, null,
				NOW, NOW, NOW.plusSeconds(604800), null, null);
		insertInvitation(UUID.randomUUID(), "expired@example.test", "CUSTOMER", "PENDING", bytes(16), EMPLOYEE_ID, null,
				NOW.plusSeconds(200), NOW.plusSeconds(200), NOW.plusSeconds(605000), null, null);

		assertSqlFailure(
				() -> insertInvitation(UUID.randomUUID(), "Admin@example.test", "CUSTOMER", "PENDING", bytes(17), EMPLOYEE_ID, null,
						NOW, NOW, NOW.plusSeconds(604800), null, null),
				"23514",
				"identity_invitation_email_canonical"
		);
		assertSqlFailure(
				() -> insertInvitation(UUID.randomUUID(), "admininvite@example.test", "ADMIN", "PENDING", bytes(18), ADMIN_ID, null,
						NOW, NOW, NOW.plusSeconds(604800), null, null),
				"23514",
				"identity_invitation_role_valid"
		);
		assertSqlFailure(
				() -> insertInvitation(UUID.randomUUID(), "statusinvite@example.test", "CUSTOMER", "SENT", bytes(19), EMPLOYEE_ID, null,
						NOW, NOW, NOW.plusSeconds(604800), null, null),
				"23514",
				"identity_invitation_status_valid"
		);
		assertSqlFailure(
				() -> insertInvitation(UUID.randomUUID(), "hashinvite@example.test", "CUSTOMER", "PENDING", bytes(10), EMPLOYEE_ID, null,
						NOW, NOW, NOW.plusSeconds(604800), null, null),
				"23505",
				"identity_invitation_token_hash_uq"
		);
		assertSqlFailure(
				() -> insertInvitation(UUID.randomUUID(), "shortinvite@example.test", "CUSTOMER", "PENDING", bytes(20, 31), EMPLOYEE_ID, null,
						NOW, NOW, NOW.plusSeconds(604800), null, null),
				"23514",
				"identity_invitation_token_hash_length"
		);
		assertSqlFailure(
				() -> insertInvitation(UUID.randomUUID(), "missingactor@example.test", "CUSTOMER", "PENDING", bytes(21), UUID.randomUUID(), null,
						NOW, NOW, NOW.plusSeconds(604800), null, null),
				"23503",
				"identity_invitation_invited_by_user_id_fk"
		);
		assertSqlFailure(
				() -> insertInvitation(UUID.randomUUID(), "missingaccepted@example.test", "CUSTOMER", "ACCEPTED", bytes(22), EMPLOYEE_ID, UUID.randomUUID(),
						NOW, NOW.plusSeconds(1), NOW.plusSeconds(604800), NOW.plusSeconds(1), null),
				"23503",
				"identity_invitation_accepted_user_id_fk"
		);
		assertSqlFailure(
				() -> insertInvitation(UUID.randomUUID(), "badaccepted@example.test", "CUSTOMER", "ACCEPTED", bytes(23), EMPLOYEE_ID, CUSTOMER_ID,
						NOW, NOW.plusSeconds(604800), NOW.plusSeconds(604800), NOW.plusSeconds(604800), null),
				"23514",
				"identity_invitation_lifecycle_consistent"
		);
		assertSqlFailure(
				() -> insertInvitation(UUID.randomUUID(), "lateaccepted@example.test", "CUSTOMER", "ACCEPTED", bytes(25), EMPLOYEE_ID, CUSTOMER_ID,
						NOW, NOW, NOW.plusSeconds(604800), NOW.plusSeconds(1), null),
				"23514",
				"identity_invitation_lifecycle_timestamps_not_after_updated_at"
		);
		assertSqlFailure(
				() -> insertInvitation(UUID.randomUUID(), "badrevoked@example.test", "CUSTOMER", "REVOKED", bytes(24), EMPLOYEE_ID, null,
						NOW, NOW, NOW.plusSeconds(604800), null, null),
				"23514",
				"identity_invitation_lifecycle_consistent"
		);
		assertSqlFailure(
				() -> insertInvitation(UUID.randomUUID(), "laterevoked@example.test", "CUSTOMER", "REVOKED", bytes(26), EMPLOYEE_ID, null,
						NOW, NOW, NOW.plusSeconds(604800), null, NOW.plusSeconds(1)),
				"23514",
				"identity_invitation_lifecycle_timestamps_not_after_updated_at"
		);
	}

	@Test
	void invitationResendRollbackPreservesOriginalPendingInvitation() {
		UUID invitationId = UUID.randomUUID();
		byte[] duplicateToken = bytes(30);
		insertInvitation(invitationId, "rollback@example.test", "CUSTOMER", "PENDING", duplicateToken, EMPLOYEE_ID, null,
				NOW, NOW, NOW.plusSeconds(604800), null, null);

		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
			jdbcTemplate.update("""
					UPDATE identity_invitation
					SET status = 'REVOKED', revoked_at = ?, updated_at = ?
					WHERE id = ? AND status = 'PENDING'
					""", offset(NOW.plusSeconds(10)), offset(NOW.plusSeconds(10)), invitationId);
			insertInvitation(UUID.randomUUID(), "rollback@example.test", "CUSTOMER", "PENDING", duplicateToken, EMPLOYEE_ID, null,
					NOW.plusSeconds(10), NOW.plusSeconds(10), NOW.plusSeconds(604810), null, null);
		})).isInstanceOf(DataIntegrityViolationException.class);

		String status = jdbcTemplate.queryForObject(
				"SELECT status FROM identity_invitation WHERE id = ?",
				String.class,
				invitationId
		);
		assertThat(status).isEqualTo("PENDING");
	}

	@Test
	void conditionalInvitationAcceptanceSucceedsOnceAndRequiresStrictFutureExpiry() {
		UUID acceptedUserId = UUID.randomUUID();
		users.insertActiveUser(acceptedUserId, "accepted-user@example.test", "CUSTOMER", NOW);
		UUID invitationId = UUID.randomUUID();
		insertInvitation(invitationId, "accept-flow@example.test", "CUSTOMER", "PENDING", bytes(40), EMPLOYEE_ID, null,
				NOW, NOW, NOW.plusSeconds(100), null, null);

		assertThat(acceptInvitation(invitationId, acceptedUserId, NOW.plusSeconds(99), NOW.plusSeconds(99))).isOne();
		assertThat(acceptInvitation(invitationId, acceptedUserId, NOW.plusSeconds(99), NOW.plusSeconds(99))).isZero();

		UUID boundaryInvitationId = UUID.randomUUID();
		insertInvitation(boundaryInvitationId, "boundary@example.test", "CUSTOMER", "PENDING", bytes(41), EMPLOYEE_ID, null,
				NOW, NOW, NOW.plusSeconds(100), null, null);
		assertThat(acceptInvitation(boundaryInvitationId, acceptedUserId, NOW.plusSeconds(100), NOW.plusSeconds(100))).isZero();
	}

	@Test
	void passwordResetTokensAreSingleUseAndChronological() {
		UUID usableToken = UUID.randomUUID();
		insertResetToken(usableToken, CUSTOMER_ID, bytes(50), NOW, NOW, NOW.plusSeconds(3600), null, null);
		insertResetToken(UUID.randomUUID(), CUSTOMER_ID, bytes(51), NOW, NOW, NOW.plusSeconds(3600), null, null);

		assertThat(consumeResetToken(usableToken, NOW.plusSeconds(120), NOW.plusSeconds(120))).isOne();
		assertThat(consumeResetToken(usableToken, NOW.plusSeconds(121), NOW.plusSeconds(121))).isZero();

		UUID expiredToken = UUID.randomUUID();
		insertResetToken(expiredToken, CUSTOMER_ID, bytes(52), NOW, NOW, NOW.plusSeconds(60), null, null);
		assertThat(consumeResetToken(expiredToken, NOW.plusSeconds(60), NOW.plusSeconds(60))).isZero();

		UUID revokedToken = UUID.randomUUID();
		insertResetToken(revokedToken, CUSTOMER_ID, bytes(53), NOW, NOW.plusSeconds(20), NOW.plusSeconds(3600), null, NOW.plusSeconds(20));
		assertThat(consumeResetToken(revokedToken, NOW.plusSeconds(120), NOW.plusSeconds(120))).isZero();

		assertSqlFailure(
				() -> insertResetToken(UUID.randomUUID(), UUID.randomUUID(), bytes(54), NOW, NOW, NOW.plusSeconds(3600), null, null),
				"23503",
				"identity_password_reset_token_user_id_fk"
		);
		assertSqlFailure(
				() -> insertResetToken(UUID.randomUUID(), CUSTOMER_ID, bytes(50), NOW, NOW, NOW.plusSeconds(3600), null, null),
				"23505",
				"identity_password_reset_token_token_hash_uq"
		);
		assertSqlFailure(
				() -> insertResetToken(UUID.randomUUID(), CUSTOMER_ID, bytes(55, 31), NOW, NOW, NOW.plusSeconds(3600), null, null),
				"23514",
				"identity_password_reset_token_token_hash_length"
		);
		assertSqlFailure(
				() -> insertResetToken(UUID.randomUUID(), CUSTOMER_ID, bytes(56), NOW, NOW.minusSeconds(1), NOW.plusSeconds(3600), null, null),
				"23514",
				"identity_password_reset_token_updated_at_chronological"
		);
		assertSqlFailure(
				() -> insertResetToken(UUID.randomUUID(), CUSTOMER_ID, bytes(57), NOW, NOW, NOW, null, null),
				"23514",
				"identity_password_reset_token_expires_at_chronological"
		);
		assertSqlFailure(
				() -> insertResetToken(UUID.randomUUID(), CUSTOMER_ID, bytes(58), NOW, NOW.plusSeconds(11), NOW.plusSeconds(3600), NOW.plusSeconds(10), NOW.plusSeconds(11)),
				"23514",
				"identity_password_reset_token_lifecycle_consistent"
		);
		assertSqlFailure(
				() -> insertResetToken(UUID.randomUUID(), CUSTOMER_ID, bytes(59), NOW, NOW, NOW.plusSeconds(3600), NOW.plusSeconds(10), null),
				"23514",
				"identity_reset_token_lifecycle_not_after_updated_at"
		);
		assertSqlFailure(
				() -> insertResetToken(UUID.randomUUID(), CUSTOMER_ID, bytes(73), NOW, NOW, NOW.plusSeconds(3600), null, NOW.plusSeconds(10)),
				"23514",
				"identity_reset_token_lifecycle_not_after_updated_at"
		);
	}

	@Test
	void loginThrottleSupportsNonexistentIdentitiesAndHostSourcesOnly() {
		insertThrottle(bytes(60), "192.0.2.15", 1, NOW, NOW, NOW, NOW.plusSeconds(60), null);
		insertThrottle(bytes(60), "2001:db8::1", 1, NOW, NOW, NOW, NOW.plusSeconds(60), null);
		insertThrottle(bytes(61), "192.0.2.15", 1, NOW, NOW, NOW, NOW.plusSeconds(60), null);

		assertSqlFailure(
				() -> insertThrottle(bytes(60), "192.0.2.15", 1, NOW, NOW, NOW, NOW.plusSeconds(60), null),
				"23505",
				"identity_login_throttle_pkey"
		);
		assertSqlFailure(
				() -> insertThrottle(bytes(62, 31), "192.0.2.16", 1, NOW, NOW, NOW, NOW.plusSeconds(60), null),
				"23514",
				"identity_login_throttle_identity_hash_length"
		);
		assertSqlFailure(
				() -> insertThrottle(bytes(63), "192.0.2.0/24", 1, NOW, NOW, NOW, NOW.plusSeconds(60), null),
				"23514",
				"identity_login_throttle_source_address_host"
		);
		assertSqlFailure(
				() -> insertThrottle(bytes(64), "2001:db8::/64", 1, NOW, NOW, NOW, NOW.plusSeconds(60), null),
				"23514",
				"identity_login_throttle_source_address_host"
		);
		assertSqlFailure(
				() -> insertThrottle(bytes(65), "192.0.2.17", -1, NOW, NOW, NOW, NOW.plusSeconds(60), null),
				"23514",
				"identity_login_throttle_failed_attempts_nonnegative"
		);
		assertSqlFailure(
				() -> insertThrottle(bytes(74), "192.0.2.23", 0, NOW, NOW, NOW, NOW.plusSeconds(60), null, NOW),
				"23514",
				"identity_login_throttle_failure_state_consistent"
		);
		assertSqlFailure(
				() -> insertThrottle(bytes(75), "192.0.2.24", 1, NOW, NOW, NOW, NOW.plusSeconds(60), null, null),
				"23514",
				"identity_login_throttle_failure_state_consistent"
		);
		assertSqlFailure(
				() -> insertThrottle(bytes(66), "192.0.2.18", 1, NOW, NOW.minusSeconds(1), NOW, NOW.plusSeconds(60), null),
				"23514",
				"identity_login_throttle_updated_at_chronological"
		);
		assertSqlFailure(
				() -> insertThrottle(bytes(67), "192.0.2.19", 1, NOW, NOW, NOW.minusSeconds(1), NOW.plusSeconds(60), null),
				"23514",
				"identity_login_throttle_window_started_at_chronological"
		);
		assertSqlFailure(
				() -> jdbcTemplate.update("""
						INSERT INTO identity_login_throttle (
							identity_hash,
							source_address,
							failed_attempts,
							window_started_at,
							last_failed_at,
							created_at,
							updated_at,
							expires_at
						) VALUES (?, ?::INET, ?, ?, ?, ?, ?, ?)
						""",
						bytes(68),
						"192.0.2.20",
						1,
						offset(NOW),
						offset(NOW.minusSeconds(1)),
						offset(NOW),
						offset(NOW),
						offset(NOW.plusSeconds(60))),
				"23514",
				"identity_login_throttle_last_failed_at_chronological"
		);
		assertSqlFailure(
				() -> insertThrottle(bytes(76), "192.0.2.25", 1, NOW, NOW, NOW.plusSeconds(1), NOW.plusSeconds(60), null),
				"23514",
				"identity_login_throttle_lifecycle_not_after_updated_at"
		);
		assertSqlFailure(
				() -> insertThrottle(bytes(77), "192.0.2.26", 1, NOW, NOW, NOW, NOW.plusSeconds(60), null, NOW.plusSeconds(1)),
				"23514",
				"identity_login_throttle_lifecycle_not_after_updated_at"
		);
		assertSqlFailure(
				() -> insertThrottle(bytes(69), "192.0.2.21", 1, NOW, NOW, NOW, NOW, null),
				"23514",
				"identity_login_throttle_expires_at_chronological"
		);
		assertSqlFailure(
				() -> insertThrottle(bytes(70), "192.0.2.22", 1, NOW, NOW, NOW, NOW.plusSeconds(60), NOW.plusSeconds(120)),
				"23514",
				"identity_login_throttle_blocked_until_chronological"
		);
	}

	@Test
	void indexesMatchTheAccessPatterns() {
		assertIndex("identity_session_user_id_idx", false, List.of("user_id"), null);
		assertIndex("identity_session_expires_at_idx", false, List.of("expires_at"), null);
		assertIndex("identity_invitation_pending_email_uq", true, List.of("email"), "PENDING");
		assertIndex("identity_invitation_expires_at_idx", false, List.of("expires_at"), null);
		assertIndex("identity_invitation_invited_by_user_id_idx", false, List.of("invited_by_user_id"), null);
		assertIndex("identity_invitation_accepted_user_id_idx", false, List.of("accepted_user_id"), null);
		assertIndex("identity_password_reset_token_user_id_idx", false, List.of("user_id"), null);
		assertIndex("identity_password_reset_token_expires_at_idx", false, List.of("expires_at"), null);
		assertIndex("identity_login_throttle_expires_at_idx", false, List.of("expires_at"), null);
	}

	@Test
	void nonUtcInputOffsetsRoundTripToTheSameInstantWithMicrosecondPrecision() {
		OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-07T10:15:30.123456+02:00");
		UUID userId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO identity_user (
					id, email, password_hash, role, status, created_at, updated_at
				) VALUES (?, ?, ?, ?, ?, ?, ?)
				""",
				userId,
				"offset@example.test",
				IdentityDatabaseFixture.TEST_PASSWORD_HASH,
				"CUSTOMER",
				"ACTIVE",
				createdAt,
				createdAt
		);

		OffsetDateTime stored = jdbcTemplate.queryForObject(
				"SELECT created_at FROM identity_user WHERE id = ?",
				(resultSet, rowNumber) -> resultSet.getObject("created_at", OffsetDateTime.class),
				userId
		);

		assertThat(stored).isNotNull();
		assertThat(stored.toInstant()).isEqualTo(createdAt.toInstant());
		assertThat(stored.getOffset()).isEqualTo(ZoneOffset.UTC);
		assertThat(stored.getNano()).isEqualTo(123456000);
	}

	@Test
	void concurrentAccountEmailInsertsAllowExactlyOneCommit() throws Exception {
		List<Boolean> results = race(
				() -> insertAccountInIndependentTransaction("race@example.test", bytes(80)),
				() -> insertAccountInIndependentTransaction("race@example.test", bytes(81))
		);

		assertThat(results).containsExactlyInAnyOrder(true, false);
	}

	@Test
	void concurrentPendingInvitationInsertsAllowExactlyOneCommit() throws Exception {
		List<Boolean> results = race(
				() -> insertInvitationInIndependentTransaction("invite-race@example.test", bytes(82)),
				() -> insertInvitationInIndependentTransaction("invite-race@example.test", bytes(83))
		);

		assertThat(results).containsExactlyInAnyOrder(true, false);
	}

	private void insertUser(
			UUID id,
			String email,
			String role,
			String status,
			long securityVersion,
			Instant createdAt,
			Instant updatedAt,
			Instant lastLoginAt
	) {
		users.insertUser(id, email, role, status, securityVersion, createdAt, updatedAt, lastLoginAt);
	}

	private void insertSession(
			UUID id,
			byte[] sessionHash,
			UUID userId,
			long securityVersion,
			Instant createdAt,
			Instant updatedAt,
			Instant lastSeenAt,
			Instant expiresAt,
			Instant revokedAt
	) {
		jdbcTemplate.update("""
				INSERT INTO identity_session (
					id, session_id_hash, user_id, security_version, created_at, updated_at, last_seen_at, expires_at, revoked_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				id,
				sessionHash,
				userId,
				securityVersion,
				offset(createdAt),
				offset(updatedAt),
				offset(lastSeenAt),
				offset(expiresAt),
				offset(revokedAt)
		);
	}

	private void insertInvitation(
			UUID id,
			String email,
			String role,
			String status,
			byte[] tokenHash,
			UUID invitedByUserId,
			UUID acceptedUserId,
			Instant createdAt,
			Instant updatedAt,
			Instant expiresAt,
			Instant acceptedAt,
			Instant revokedAt
	) {
		jdbcTemplate.update("""
				INSERT INTO identity_invitation (
					id,
					email,
					role,
					status,
					token_hash,
					invited_by_user_id,
					accepted_user_id,
					created_at,
					updated_at,
					expires_at,
					accepted_at,
					revoked_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				id,
				email,
				role,
				status,
				tokenHash,
				invitedByUserId,
				acceptedUserId,
				offset(createdAt),
				offset(updatedAt),
				offset(expiresAt),
				offset(acceptedAt),
				offset(revokedAt)
		);
	}

	private int acceptInvitation(UUID invitationId, UUID acceptedUserId, Instant acceptedAt, Instant now) {
		return jdbcTemplate.update("""
				UPDATE identity_invitation
				SET status = 'ACCEPTED',
					accepted_user_id = ?,
					accepted_at = ?,
					updated_at = ?
				WHERE id = ?
				  AND status = 'PENDING'
				  AND expires_at > ?
				""",
				acceptedUserId,
				offset(acceptedAt),
				offset(acceptedAt),
				invitationId,
				offset(now)
		);
	}

	private void insertResetToken(
			UUID id,
			UUID userId,
			byte[] tokenHash,
			Instant createdAt,
			Instant updatedAt,
			Instant expiresAt,
			Instant consumedAt,
			Instant revokedAt
	) {
		jdbcTemplate.update("""
				INSERT INTO identity_password_reset_token (
					id, user_id, token_hash, created_at, updated_at, expires_at, consumed_at, revoked_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""",
				id,
				userId,
				tokenHash,
				offset(createdAt),
				offset(updatedAt),
				offset(expiresAt),
				offset(consumedAt),
				offset(revokedAt)
		);
	}

	private int consumeResetToken(UUID id, Instant consumedAt, Instant now) {
		return jdbcTemplate.update("""
				UPDATE identity_password_reset_token
				SET consumed_at = ?, updated_at = ?
				WHERE id = ?
				  AND consumed_at IS NULL
				  AND revoked_at IS NULL
				  AND expires_at > ?
				""",
				offset(consumedAt),
				offset(consumedAt),
				id,
				offset(now)
		);
	}

	private void insertThrottle(
			byte[] identityHash,
			String sourceAddress,
			int failedAttempts,
			Instant createdAt,
			Instant updatedAt,
			Instant windowStartedAt,
			Instant expiresAt,
			Instant blockedUntil
	) {
		insertThrottle(
				identityHash,
				sourceAddress,
				failedAttempts,
				createdAt,
				updatedAt,
				windowStartedAt,
				expiresAt,
				blockedUntil,
				blockedUntil == null ? windowStartedAt : blockedUntil
		);
	}

	private void insertThrottle(
			byte[] identityHash,
			String sourceAddress,
			int failedAttempts,
			Instant createdAt,
			Instant updatedAt,
			Instant windowStartedAt,
			Instant expiresAt,
			Instant blockedUntil,
			Instant lastFailedAt
	) {
		jdbcTemplate.update("""
				INSERT INTO identity_login_throttle (
					identity_hash,
					source_address,
					failed_attempts,
					window_started_at,
					last_failed_at,
					blocked_until,
					created_at,
					updated_at,
					expires_at
				) VALUES (?, ?::INET, ?, ?, ?, ?, ?, ?, ?)
				""",
				identityHash,
				sourceAddress,
				failedAttempts,
				offset(windowStartedAt),
				offset(lastFailedAt),
				offset(blockedUntil),
				offset(createdAt),
				offset(updatedAt),
				offset(expiresAt)
		);
	}

	private List<Integer> activeSessionIds(Instant now) {
		return jdbcTemplate.queryForList("""
				SELECT get_byte(s.session_id_hash, 0)
				FROM identity_session s
				JOIN identity_user u ON u.id = s.user_id
				WHERE u.status = 'ACTIVE'
				  AND s.security_version = u.security_version
				  AND s.revoked_at IS NULL
				  AND s.expires_at > ?
				ORDER BY get_byte(s.session_id_hash, 0)
				""", Integer.class, offset(now));
	}

	private void assertIndex(String indexName, boolean unique, List<String> columns, String predicate) {
		IndexDefinition index = jdbcTemplate.queryForObject("""
				SELECT
					i.indisunique,
					pg_get_indexdef(i.indexrelid) AS definition,
					pg_get_expr(i.indpred, i.indrelid) AS predicate
				FROM pg_index i
				JOIN pg_class c ON c.oid = i.indexrelid
				JOIN pg_namespace n ON n.oid = c.relnamespace
				WHERE n.nspname = 'public'
				  AND c.relname = ?
				""", (resultSet, rowNumber) -> new IndexDefinition(
				resultSet.getBoolean("indisunique"),
				resultSet.getString("definition"),
				resultSet.getString("predicate")
		), indexName);

		assertThat(index.unique()).isEqualTo(unique);
		for (String column : columns) {
			assertThat(index.definition()).contains("(" + column).contains(column);
		}
		if (predicate == null) {
			assertThat(index.predicate()).isNull();
		}
		else {
			assertThat(index.predicate()).contains(predicate);
		}
	}

	private boolean insertAccountInIndependentTransaction(String email, byte[] salt) throws Exception {
		try (var connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (var statement = connection.prepareStatement("""
					INSERT INTO identity_user (
						id, email, password_hash, role, status, created_at, updated_at
					) VALUES (?, ?, ?, ?, ?, ?, ?)
					""")) {
				statement.setObject(1, UUID.nameUUIDFromBytes(salt));
				statement.setString(2, email);
				statement.setString(3, IdentityDatabaseFixture.TEST_PASSWORD_HASH);
				statement.setString(4, "CUSTOMER");
				statement.setString(5, "ACTIVE");
				statement.setObject(6, offset(NOW));
				statement.setObject(7, offset(NOW));
				statement.executeUpdate();
				connection.commit();
				return true;
			}
			catch (SQLException exception) {
				connection.rollback();
				if (isExpectedRaceLoser(exception, "identity_user_email_uq")) {
					return false;
				}
				throw exception;
			}
		}
	}

	private boolean insertInvitationInIndependentTransaction(String email, byte[] tokenHash) throws Exception {
		try (var connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (var statement = connection.prepareStatement("""
					INSERT INTO identity_invitation (
						id, email, role, status, token_hash, invited_by_user_id, created_at, updated_at, expires_at
					) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
					""")) {
				statement.setObject(1, UUID.nameUUIDFromBytes(tokenHash));
				statement.setString(2, email);
				statement.setString(3, "CUSTOMER");
				statement.setString(4, "PENDING");
				statement.setBytes(5, tokenHash);
				statement.setObject(6, EMPLOYEE_ID);
				statement.setObject(7, offset(NOW));
				statement.setObject(8, offset(NOW));
				statement.setObject(9, offset(NOW.plusSeconds(604800)));
				statement.executeUpdate();
				connection.commit();
				return true;
			}
			catch (SQLException exception) {
				connection.rollback();
				if (isExpectedRaceLoser(exception, "identity_invitation_pending_email_uq")) {
					return false;
				}
				throw exception;
			}
		}
	}

	private static boolean isExpectedRaceLoser(SQLException exception, String constraintName) {
		return "23505".equals(exception.getSQLState()) && exception.getMessage().contains(constraintName);
	}

	private List<Boolean> race(Callable<Boolean> firstWorker, Callable<Boolean> secondWorker) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			var first = executor.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return firstWorker.call();
			});
			var second = executor.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return secondWorker.call();
			});
			start.countDown();
			boolean firstResult = first.get(10, TimeUnit.SECONDS);
			boolean secondResult = second.get(10, TimeUnit.SECONDS);
			return List.of(firstResult, secondResult);
		}
		finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private void assertSqlFailure(SqlOperation operation, String sqlState, String constraintOrColumn) {
		assertThatThrownBy(operation::run)
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(exception -> assertThat(sqlState(exception)).isEqualTo(sqlState))
				.hasMessageContaining(constraintOrColumn);
	}

	private static String sqlState(Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof SQLException sqlException) {
				return sqlException.getSQLState();
			}
			current = current.getCause();
		}
		return null;
	}

	private static OffsetDateTime offset(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static byte[] bytes(int seed) {
		return bytes(seed, 32);
	}

	private static byte[] bytes(int seed, int length) {
		byte[] bytes = new byte[length];
		for (int index = 0; index < length; index++) {
			bytes[index] = (byte) (seed + index);
		}
		return bytes;
	}

	private record IndexDefinition(boolean unique, String definition, String predicate) {
	}

	@FunctionalInterface
	private interface SqlOperation {
		void run();
	}

}
