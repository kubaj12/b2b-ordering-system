package io.github.kubaj12.online_store;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerPersistenceTests extends PostgreSqlServiceTestSupport {

	private static final Instant NOW = Instant.parse("2026-09-24T08:15:30.123456Z");
	private static final UUID EMPLOYEE_ID = UUID.fromString("01998e62-e700-7000-8000-000000000001");
	private static final UUID CUSTOMER_ID = UUID.fromString("01998e62-e700-7000-8000-000000000002");
	private static final UUID OTHER_CUSTOMER_ID = UUID.fromString("01998e62-e700-7000-8000-000000000003");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void insertAccounts() {
		var users = new IdentityDatabaseFixture(jdbcTemplate);
		users.insertActiveUser(EMPLOYEE_ID, "employee@example.test", "EMPLOYEE", NOW);
		users.insertActiveUser(CUSTOMER_ID, "customer@example.test", "CUSTOMER", NOW);
		users.insertActiveUser(OTHER_CUSTOMER_ID, "other@example.test", "CUSTOMER", NOW);
	}

	@Test
	void profileIsOneToOneWithACustomerAndNipIsCanonicalAndUnique() {
		insertProfile(CUSTOMER_ID, "1234567890", null, null, "PL", "00-001", NOW, NOW);

		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM customer_profile WHERE user_id = ? AND user_role = 'CUSTOMER'",
				Integer.class,
				CUSTOMER_ID
		)).isOne();
		assertFailure(
				() -> insertProfile(CUSTOMER_ID, "1234567891", "2A", "+48 600 700 800", "PL", "00-001", NOW, NOW),
				"23505", "customer_profile_pkey"
		);
		assertFailure(
				() -> insertProfile(OTHER_CUSTOMER_ID, "1234567890", null, null, "PL", "00-001", NOW, NOW),
				"23505", "customer_profile_nip_uq"
		);
		assertFailure(
				() -> insertProfile(OTHER_CUSTOMER_ID, "123-456-78-90", null, null, "PL", "00-001", NOW, NOW),
				"23514", "customer_profile_nip_canonical"
		);
		assertFailure(
				() -> insertProfile(EMPLOYEE_ID, "1234567891", null, null, "PL", "00-001", NOW, NOW),
				"23503", "customer_profile_user_fk"
		);
	}

	@Test
	void profileRequiresCompletePolishBillingDataAndChronologicalTimestamps() {
		assertFailure(
				() -> insertProfile(CUSTOMER_ID, "1234567890", null, null, "DE", "00-001", NOW, NOW),
				"23514", "customer_profile_billing_country_poland"
		);
		assertFailure(
				() -> insertProfile(CUSTOMER_ID, "1234567890", null, null, "PL", "00001", NOW, NOW),
				"23514", "customer_profile_billing_postal_code_polish"
		);
		assertFailure(
				() -> insertProfile(CUSTOMER_ID, "1234567890", "   ", null, "PL", "00-001", NOW, NOW),
				"23514", "customer_profile_billing_unit_number_nonblank"
		);
		assertFailure(
				() -> insertProfile(CUSTOMER_ID, "1234567890", null, " ", "PL", "00-001", NOW, NOW),
				"23514", "customer_profile_phone_nonblank"
		);
		assertFailure(
				() -> insertProfile(CUSTOMER_ID, "1234567890", null, null, "PL", "00-001", NOW, NOW.minusSeconds(1)),
				"23514", "customer_profile_updated_at_chronological"
		);
		assertFailure(
				() -> jdbcTemplate.update("""
						INSERT INTO customer_profile (
							user_id, company_name, nip, billing_street, billing_building_number,
							billing_postal_code, billing_city, billing_country, created_at, updated_at
						) VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)
						""", CUSTOMER_ID, "1234567890", "Prosta", "1", "00-001", "Warszawa", "PL", offset(NOW), offset(NOW)),
				"23502", "company_name"
		);
	}

	@Test
	void customerInvitationDataIsOneToOneUniquePayloadForCustomerInvitations() {
		UUID firstInvitation = insertInvitation("first@example.test", "CUSTOMER", 1);
		UUID replacementInvitation = insertInvitation("replacement@example.test", "CUSTOMER", 2);
		UUID employeeInvitation = insertInvitation("worker@example.test", "EMPLOYEE", 3);

		insertInvitationData(firstInvitation, "1234567890", null, null, "PL", "00-001", NOW, NOW);

		assertFailure(
				() -> insertInvitationData(replacementInvitation, "1234567890", "4", "+48 600 700 800", "PL", "00-001", NOW, NOW),
				"23505", "customer_invitation_data_nip_uq"
		);
		assertFailure(
				() -> insertInvitationData(firstInvitation, "1234567891", null, null, "PL", "00-001", NOW, NOW),
				"23505", "customer_invitation_data_pkey"
		);
		jdbcTemplate.update("DELETE FROM customer_invitation_data WHERE invitation_id = ?", firstInvitation);
		insertInvitationData(replacementInvitation, "1234567890", "4", "+48 600 700 800", "PL", "00-001", NOW, NOW);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM customer_invitation_data WHERE nip = ?",
				Integer.class,
				"1234567890"
		)).isOne();
		assertFailure(
				() -> insertInvitationData(employeeInvitation, "1234567891", null, null, "PL", "00-001", NOW, NOW),
				"23503", "customer_invitation_data_invitation_fk"
		);
	}

	@Test
	void customerInvitationDataRequiresCanonicalCompletePolishBillingData() {
		UUID invitation = insertInvitation("new@example.test", "CUSTOMER", 4);

		assertFailure(
				() -> insertInvitationData(invitation, "123 456 7890", null, null, "PL", "00-001", NOW, NOW),
				"23514", "customer_invitation_data_nip_canonical"
		);
		assertFailure(
				() -> insertInvitationData(invitation, "1234567890", null, null, "CZ", "00-001", NOW, NOW),
				"23514", "customer_invitation_data_billing_country_poland"
		);
		assertFailure(
				() -> insertInvitationData(invitation, "1234567890", null, null, "PL", "001-01", NOW, NOW),
				"23514", "customer_invitation_data_billing_postal_code_polish"
		);
		assertFailure(
				() -> insertInvitationData(invitation, "1234567890", null, null, "PL", "00-001", NOW, NOW.minusSeconds(1)),
				"23514", "customer_invitation_data_updated_at_chronological"
		);
	}

	private void insertProfile(
			UUID userId,
			String nip,
			String unitNumber,
			String phone,
			String country,
			String postalCode,
			Instant createdAt,
			Instant updatedAt
	) {
		jdbcTemplate.update("""
				INSERT INTO customer_profile (
					user_id, company_name, nip, billing_street, billing_building_number,
					billing_unit_number, billing_postal_code, billing_city, billing_country,
					phone, created_at, updated_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", userId, "Przykładowa Sp. z o.o.", nip, "Prosta", "1", unitNumber,
				postalCode, "Warszawa", country, phone, offset(createdAt), offset(updatedAt));
	}

	private UUID insertInvitation(String email, String role, int hashSeed) {
		UUID invitationId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO identity_invitation (
					id, email, role, status, token_hash, invited_by_user_id,
					created_at, updated_at, expires_at
				) VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?)
				""", invitationId, email, role, tokenHash(hashSeed), EMPLOYEE_ID,
				offset(NOW), offset(NOW), offset(NOW.plusSeconds(604800)));
		return invitationId;
	}

	private void insertInvitationData(
			UUID invitationId,
			String nip,
			String unitNumber,
			String phone,
			String country,
			String postalCode,
			Instant createdAt,
			Instant updatedAt
	) {
		jdbcTemplate.update("""
				INSERT INTO customer_invitation_data (
					invitation_id, company_name, nip, billing_street, billing_building_number,
					billing_unit_number, billing_postal_code, billing_city, billing_country,
					phone, created_at, updated_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", invitationId, "Nowa Firma Sp. z o.o.", nip, "Długa", "2", unitNumber,
				postalCode, "Gdańsk", country, phone, offset(createdAt), offset(updatedAt));
	}

	private void assertFailure(SqlOperation operation, String sqlState, String constraintOrColumn) {
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
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static byte[] tokenHash(int seed) {
		byte[] hash = new byte[32];
		for (int index = 0; index < hash.length; index++) {
			hash[index] = (byte) (seed + index);
		}
		return hash;
	}

	@FunctionalInterface
	private interface SqlOperation {
		void run();
	}

}
