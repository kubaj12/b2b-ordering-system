package io.github.kubaj12.online_store;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.kubaj12.online_store.testsupport.MigrationFixture;
import io.github.kubaj12.online_store.testsupport.MigrationFixture.MigrationSchema;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("migration")
class DatabaseMigrationTests extends PostgreSqlServiceTestSupport {

	@Autowired
	private PostgreSQLContainer container;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private MigrationFixture migrations;

	@BeforeEach
	void createMigrationFixture() {
		migrations = new MigrationFixture(container, jdbcTemplate);
	}

	@Test
	void migratesAnEmptySchemaToLatestAndValidatesItIdempotently() {
		MigrationSchema schema = migrations.newSchema();
		List<MigrationVersion> resolvedVersions = pendingVersions(schema);

		var firstMigration = schema.flyway().migrate();

		assertThat(resolvedVersions).isNotEmpty();
		assertThat(firstMigration.success).isTrue();
		assertThat(firstMigration.migrationsExecuted).isEqualTo(resolvedVersions.size());
		assertThat(schema.flyway().info().current().getVersion()).isEqualTo(resolvedVersions.getLast());
		assertThat(schema.flyway().info().pending()).isEmpty();
		assertThat(schema.flyway().validateWithResult().validationSuccessful).isTrue();
		assertThat(schema.flyway().migrate().migrationsExecuted).isZero();

		Integer successfulHistoryRows = schema.jdbcTemplate().queryForObject("""
				SELECT COUNT(*)
				FROM flyway_schema_history
				WHERE success
				""", Integer.class);
		assertThat(successfulHistoryRows).isEqualTo(resolvedVersions.size());
	}

	@Test
	void appliesEveryVersionForwardInOrder() {
		MigrationSchema schema = migrations.newSchema();
		List<MigrationVersion> resolvedVersions = pendingVersions(schema);

		for (MigrationVersion version : resolvedVersions) {
			var result = schema.flywayTo(version).migrate();
			assertThat(result.success).isTrue();
			assertThat(result.migrationsExecuted).isOne();
			assertThat(schema.flywayTo(version).info().current().getVersion()).isEqualTo(version);
		}

		assertThat(schema.flyway().info().pending()).isEmpty();
		assertThat(schema.flyway().validateWithResult().validationSuccessful).isTrue();
		List<String> installedVersions = schema.jdbcTemplate().queryForList("""
				SELECT version
				FROM flyway_schema_history
				WHERE type = 'SQL'
				ORDER BY installed_rank
				""", String.class);
		assertThat(installedVersions)
				.containsExactlyElementsOf(resolvedVersions.stream().map(MigrationVersion::getVersion).toList());
	}

	@Test
	void keepsCleanDisabledEvenForDisposableMigrationSchemas() {
		MigrationSchema schema = migrations.newSchema();

		assertThatThrownBy(() -> schema.flyway().clean())
				.isInstanceOf(FlywayException.class)
				.hasMessageContaining("disabled");
	}

	@Test
	void upgradesPopulatedV004DataWithoutDiscardingLegacyCustomerValues() {
		MigrationSchema schema = migrations.newSchema();
		schema.flywayTo(MigrationVersion.fromVersion("004")).migrate();
		UUID employeeId = UUID.fromString("01998e62-e700-7000-8000-000000000101");
		UUID customerId = UUID.fromString("01998e62-e700-7000-8000-000000000102");
		UUID invitationId = UUID.fromString("01998e62-e700-7000-8000-000000000103");

		schema.jdbcTemplate().update("""
				INSERT INTO identity_user (
					id, email, password_hash, role, status, created_at, updated_at
				) VALUES
					(?, 'employee@example.test', 'hash', 'EMPLOYEE', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
					(?, 'customer@example.test', 'hash', 'CUSTOMER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", employeeId, customerId);
		schema.jdbcTemplate().update("""
				INSERT INTO customer_profile (
					user_id, company_name, nip, billing_street, billing_building_number,
					billing_postal_code, billing_city, phone, created_at, updated_at
				) VALUES (?, 'Firma', '1234567890', 'Prosta', '1', '00-001', 'Warszawa',
					'+48 600 700 800', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", customerId);
		schema.jdbcTemplate().update("""
				INSERT INTO identity_invitation (
					id, email, role, status, token_hash, invited_by_user_id,
					created_at, updated_at, expires_at
				) VALUES (?, 'new@example.test', 'CUSTOMER', 'PENDING', ?, ?,
					CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '7 days')
				""", invitationId, new byte[32], employeeId);
		schema.jdbcTemplate().update("""
				INSERT INTO customer_invitation_data (
					invitation_id, company_name, nip, billing_street, billing_building_number,
					billing_postal_code, billing_city, phone, created_at, updated_at
				) VALUES (?, 'Nowa Firma', '5260250995', 'Długa', '2', '80-001', 'Gdańsk',
					'extension 12', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", invitationId);

		assertThat(schema.flyway().migrate().success).isTrue();
		assertThat(schema.jdbcTemplate().queryForObject(
				"SELECT phone FROM customer_profile WHERE user_id = ?", String.class, customerId
		)).isEqualTo("+48600700800");
		assertThat(schema.jdbcTemplate().queryForObject(
				"SELECT phone FROM customer_invitation_data WHERE invitation_id = ?", String.class, invitationId
		)).isEqualTo("extension 12");
		assertThat(constraintValidated(schema, "customer_profile_nip_checksum")).isFalse();
		assertThat(constraintValidated(schema, "customer_profile_phone_polish")).isTrue();
		assertThat(constraintValidated(schema, "customer_invitation_data_nip_checksum")).isTrue();
		assertThat(constraintValidated(schema, "customer_invitation_data_phone_polish")).isFalse();
	}

	@Test
	void validatesAuditActorForeignKeyWhenV002SchemaHasNoUnmatchedActors() {
		MigrationSchema schema = migrations.newSchema();
		schema.flywayTo(MigrationVersion.fromVersion("002")).migrate();

		schema.flyway().migrate();

		Boolean validated = schema.jdbcTemplate().queryForObject("""
				SELECT convalidated
				FROM pg_constraint constraint_def
				JOIN pg_namespace namespace ON namespace.oid = constraint_def.connamespace
				WHERE constraint_def.conname = 'audit_event_acting_user_id_fk'
				  AND namespace.nspname = ?
				""", Boolean.class, schema.name());

		assertThat(validated).isTrue();
	}

	@Test
	void preservesUnmatchedV002AuditActorsAndRejectsNewUnmatchedActors() {
		MigrationSchema schema = migrations.newSchema();
		schema.flywayTo(MigrationVersion.fromVersion("002")).migrate();
		UUID historicalActorId = UUID.fromString("fe853426-1ad0-4b0c-b8ca-59d8e84d9ab5");

		schema.jdbcTemplate().update("""
				INSERT INTO audit_event (
					event_type,
					target_type,
					target_id,
					acting_user_id,
					occurred_at,
					change_metadata
				) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, '{}'::JSONB)
				""",
				"identity.account.blocked",
				"user",
				"42",
				historicalActorId
		);

		schema.flyway().migrate();

		Boolean validated = schema.jdbcTemplate().queryForObject("""
				SELECT convalidated
				FROM pg_constraint constraint_def
				JOIN pg_namespace namespace ON namespace.oid = constraint_def.connamespace
				WHERE constraint_def.conname = 'audit_event_acting_user_id_fk'
				  AND namespace.nspname = ?
				""", Boolean.class, schema.name());
		Integer historicalRows = schema.jdbcTemplate().queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE acting_user_id = ?",
				Integer.class,
				historicalActorId
		);

		assertThat(validated).isFalse();
		assertThat(historicalRows).isOne();
		assertThatThrownBy(() -> schema.jdbcTemplate().update("""
				INSERT INTO audit_event (
					event_type,
					target_type,
					target_id,
					acting_user_id,
					occurred_at,
					change_metadata
				) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, '{}'::JSONB)
				""",
				"identity.account.blocked",
				"user",
				"43",
				UUID.fromString("cd6d7422-cff9-4b40-9b85-94f1dabd8f24")
		))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("audit_event_acting_user_id_fk");
	}

	private static List<MigrationVersion> pendingVersions(MigrationSchema schema) {
		return Arrays.stream(schema.flyway().info().pending())
				.map(MigrationInfo::getVersion)
				.filter(Objects::nonNull)
				.toList();
	}

	private static Boolean constraintValidated(MigrationSchema schema, String constraintName) {
		return schema.jdbcTemplate().queryForObject("""
				SELECT convalidated
				FROM pg_constraint constraint_def
				JOIN pg_namespace namespace ON namespace.oid = constraint_def.connamespace
				WHERE constraint_def.conname = ? AND namespace.nspname = ?
				""", Boolean.class, constraintName, schema.name());
	}

}
