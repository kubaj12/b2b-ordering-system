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
	void enforcesCatalogAndPricingIntegrityInPostgreSql() {
		MigrationSchema schema = migrations.newSchema();
		schema.flyway().migrate();
		JdbcTemplate jdbc = schema.jdbcTemplate();
		UUID customerId = UUID.fromString("01998e62-e700-7000-8000-000000000201");
		UUID productId = UUID.fromString("01998e62-e700-7000-8000-000000000202");
		UUID skuId = UUID.fromString("01998e62-e700-7000-8000-000000000203");
		UUID secondSkuId = UUID.fromString("01998e62-e700-7000-8000-000000000204");
		UUID thirdSkuId = UUID.fromString("01998e62-e700-7000-8000-000000000205");
		UUID secondProductId = UUID.fromString("01998e62-e700-7000-8000-000000000206");
		UUID definitionId = UUID.fromString("01998e62-e700-7000-8000-000000000207");
		UUID secondDefinitionId = UUID.fromString("01998e62-e700-7000-8000-000000000208");
		UUID valueId = UUID.fromString("01998e62-e700-7000-8000-000000000209");
		UUID secondValueId = UUID.fromString("01998e62-e700-7000-8000-000000000210");
		UUID otherDefinitionValueId = UUID.fromString("01998e62-e700-7000-8000-000000000211");
		UUID priceListId = UUID.fromString("01998e62-e700-7000-8000-000000000212");

		jdbc.update("""
				INSERT INTO identity_user (id, email, password_hash, role, status, created_at, updated_at)
				VALUES (?, 'catalog-customer@example.test', 'hash', 'CUSTOMER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", customerId);
		jdbc.update("""
				INSERT INTO customer_profile (
					user_id, company_name, nip, billing_street, billing_building_number,
					billing_postal_code, billing_city, created_at, updated_at
				) VALUES (?, 'Firma', '5260250995', 'Prosta', '1', '00-001', 'Warszawa', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", customerId);
		insertCatalogProduct(jdbc, productId, "Produkt");
		insertCatalogProduct(jdbc, secondProductId, "Drugi produkt");
		insertCatalogSku(jdbc, skuId, productId, "SKU-1", "10.239", "23.00", 4, "PLN");
		insertCatalogSku(jdbc, secondSkuId, productId, "SKU-2", "12.30", "8.125", 0, "PLN");
		insertCatalogSku(jdbc, thirdSkuId, secondProductId, "SKU-3", "5.00", "5.00", 2, "PLN");
		assertThat(jdbc.queryForObject(
				"SELECT base_net_price FROM catalog_sku WHERE id = ?", java.math.BigDecimal.class, skuId
		)).isEqualByComparingTo("10.24");
		assertThat(jdbc.queryForObject(
				"SELECT vat_rate FROM catalog_sku WHERE id = ?", java.math.BigDecimal.class, secondSkuId
		)).isEqualByComparingTo("8.13");

		assertThatThrownBy(() -> insertCatalogSku(jdbc, UUID.randomUUID(), productId, "SKU-1", "1.00", "23.00", 1, "PLN"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertCatalogSku(jdbc, UUID.randomUUID(), productId, "SKU-NEG", "1.00", "23.00", -1, "PLN"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertCatalogSku(jdbc, UUID.randomUUID(), productId, "SKU-EUR", "1.00", "23.00", 1, "EUR"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertCatalogSku(jdbc, UUID.randomUUID(), productId, "SKU-VAT", "1.00", "100.01", 1, "PLN"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertCatalogSku(jdbc, UUID.randomUUID(), productId, "SKU-PRICE", "-0.01", "23.00", 1, "PLN"))
				.isInstanceOf(DataIntegrityViolationException.class);

		jdbc.update("INSERT INTO catalog_attribute_definition (id, name, created_at, updated_at) VALUES (?, 'Kolor', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", definitionId);
		jdbc.update("INSERT INTO catalog_attribute_definition (id, name, created_at, updated_at) VALUES (?, 'Rozmiar', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", secondDefinitionId);
		jdbc.update("INSERT INTO catalog_attribute_value (id, definition_id, value, created_at, updated_at) VALUES (?, ?, 'Czerwony', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", valueId, definitionId);
		jdbc.update("INSERT INTO catalog_attribute_value (id, definition_id, value, created_at, updated_at) VALUES (?, ?, 'Niebieski', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", secondValueId, definitionId);
		jdbc.update("INSERT INTO catalog_attribute_value (id, definition_id, value, created_at, updated_at) VALUES (?, ?, 'M', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", otherDefinitionValueId, secondDefinitionId);
		jdbc.update("INSERT INTO catalog_sku_attribute_assignment (sku_id, attribute_definition_id, attribute_value_id, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)", skuId, definitionId, valueId);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO catalog_sku_attribute_assignment (sku_id, attribute_definition_id, attribute_value_id, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
				skuId, definitionId, secondValueId
		)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO catalog_sku_attribute_assignment (sku_id, attribute_definition_id, attribute_value_id, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
				secondSkuId, definitionId, otherDefinitionValueId
		)).isInstanceOf(DataIntegrityViolationException.class);

		jdbc.update("INSERT INTO catalog_price_list (id, name, created_at, updated_at) VALUES (?, 'Cennik', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", priceListId);
		jdbc.update("INSERT INTO catalog_price_list_item (price_list_id, sku_id, net_price, created_at, updated_at) VALUES (?, ?, 8.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", priceListId, skuId);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO catalog_price_list_item (price_list_id, sku_id, net_price, created_at, updated_at) VALUES (?, ?, 8.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
				priceListId, skuId
		)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO catalog_price_list_item (price_list_id, sku_id, net_price, currency, created_at, updated_at) VALUES (?, ?, 8.50, 'EUR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
				priceListId, secondSkuId
		)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO catalog_price_list_item (price_list_id, sku_id, net_price, created_at, updated_at) VALUES (?, ?, -1.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
				priceListId, thirdSkuId
		)).isInstanceOf(DataIntegrityViolationException.class);

		jdbc.update("INSERT INTO catalog_customer_price_list_assignment (customer_id, price_list_id, created_at, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", customerId, priceListId);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO catalog_customer_price_list_assignment (customer_id, price_list_id, created_at, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
				customerId, priceListId
		)).isInstanceOf(DataIntegrityViolationException.class);
		jdbc.update("INSERT INTO catalog_customer_specific_price (customer_id, sku_id, net_price, created_at, updated_at) VALUES (?, ?, 7.25, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", customerId, skuId);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO catalog_customer_specific_price (customer_id, sku_id, net_price, created_at, updated_at) VALUES (?, ?, 7.25, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
				customerId, skuId
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void upgradesPopulatedV008InventoryAndEnforcesInventoryChangeIntegrity() {
		MigrationSchema schema = migrations.newSchema();
		schema.flywayTo(MigrationVersion.fromVersion("008")).migrate();
		JdbcTemplate jdbc = schema.jdbcTemplate();
		UUID employeeId = UUID.fromString("01998e62-e700-7000-8000-000000000301");
		UUID productId = UUID.fromString("01998e62-e700-7000-8000-000000000302");
		UUID skuId = UUID.fromString("01998e62-e700-7000-8000-000000000303");
		UUID changeId = UUID.fromString("01998e62-e700-7000-8000-000000000304");
		UUID missingSkuId = UUID.fromString("01998e62-e700-7000-8000-000000000305");
		UUID missingActorId = UUID.fromString("01998e62-e700-7000-8000-000000000306");

		jdbc.update("""
				INSERT INTO identity_user (id, email, password_hash, role, status, created_at, updated_at)
				VALUES (?, 'inventory-employee@example.test', 'hash', 'EMPLOYEE', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", employeeId);
		insertCatalogProduct(jdbc, productId, "Produkt magazynowy");
		insertCatalogSku(jdbc, skuId, productId, "SKU-STOCK", "10.00", "23.00", 7, "PLN");

		schema.flyway().migrate();
		assertThat(jdbc.queryForObject("SELECT inventory_version FROM catalog_sku WHERE id = ?", Long.class, skuId))
				.isZero();

		jdbc.update("""
				INSERT INTO inventory_change (id, sku_id, previous_quantity, new_quantity, acting_user_id, changed_at)
				VALUES (?, ?, 7, 9, ?, TIMESTAMPTZ '2026-09-30 12:34:56.123456+00')
				""", changeId, skuId, employeeId);
		assertThat(jdbc.queryForObject("SELECT previous_quantity FROM inventory_change WHERE id = ?", Integer.class, changeId))
				.isEqualTo(7);
		assertThat(jdbc.queryForObject("SELECT new_quantity FROM inventory_change WHERE id = ?", Integer.class, changeId))
				.isEqualTo(9);
		assertThat(jdbc.queryForObject("SELECT changed_at AT TIME ZONE 'UTC' FROM inventory_change WHERE id = ?", java.time.LocalDateTime.class, changeId))
				.isEqualTo(java.time.LocalDateTime.parse("2026-09-30T12:34:56.123456"));

		assertThatThrownBy(() -> jdbc.update(
				"UPDATE catalog_sku SET inventory_version = -1 WHERE id = ?", skuId))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO inventory_change (id, sku_id, previous_quantity, new_quantity, acting_user_id, changed_at)
				VALUES (?, ?, -1, 1, ?, CURRENT_TIMESTAMP)
				""", UUID.randomUUID(), skuId, employeeId))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO inventory_change (id, sku_id, previous_quantity, new_quantity, acting_user_id, changed_at)
				VALUES (?, ?, 1, -1, ?, CURRENT_TIMESTAMP)
				""", UUID.randomUUID(), skuId, employeeId))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO inventory_change (id, sku_id, previous_quantity, new_quantity, acting_user_id, changed_at)
				VALUES (?, ?, 1, 2, ?, CURRENT_TIMESTAMP)
				""", UUID.randomUUID(), missingSkuId, employeeId))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO inventory_change (id, sku_id, previous_quantity, new_quantity, acting_user_id, changed_at)
				VALUES (?, ?, 1, 2, ?, CURRENT_TIMESTAMP)
				""", UUID.randomUUID(), skuId, missingActorId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void backfillsInventoryHistoryVersionsInDeterministicPerSkuOrder() {
		MigrationSchema schema = migrations.newSchema();
		schema.flywayTo(MigrationVersion.fromVersion("009")).migrate();
		JdbcTemplate jdbc = schema.jdbcTemplate();
		UUID employeeId = UUID.fromString("01998e62-e700-7000-8000-000000000311");
		UUID productId = UUID.fromString("01998e62-e700-7000-8000-000000000312");
		UUID skuId = UUID.fromString("01998e62-e700-7000-8000-000000000313");
		UUID earlierId = UUID.fromString("01998e62-e700-7000-8000-000000000314");
		UUID laterId = UUID.fromString("01998e62-e700-7000-8000-000000000315");

		jdbc.update("""
				INSERT INTO identity_user (id, email, password_hash, role, status, created_at, updated_at)
				VALUES (?, 'inventory-backfill@example.test', 'hash', 'EMPLOYEE', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", employeeId);
		insertCatalogProduct(jdbc, productId, "Produkt do migracji historii");
		insertCatalogSku(jdbc, skuId, productId, "SKU-HISTORY", "10.00", "23.00", 7, "PLN");
		jdbc.update("UPDATE catalog_sku SET inventory_version=4 WHERE id=?", skuId);
		jdbc.update("""
				INSERT INTO inventory_change (id,sku_id,previous_quantity,new_quantity,acting_user_id,changed_at)
				VALUES (?, ?, 7, 8, ?, TIMESTAMPTZ '2026-09-29 10:00:00+00'),
				       (?, ?, 8, 9, ?, TIMESTAMPTZ '2026-09-29 11:00:00+00')
				""", earlierId, skuId, employeeId, laterId, skuId, employeeId);

		schema.flyway().migrate();

		List<Long> versions = jdbc.queryForList(
				"SELECT inventory_version FROM inventory_change WHERE sku_id=? ORDER BY inventory_version",
				Long.class, skuId);
		assertThat(versions).containsExactly(1L, 2L);
		assertThat(jdbc.queryForObject("SELECT inventory_version FROM catalog_sku WHERE id=?", Long.class, skuId))
				.isEqualTo(4L);
		assertThat(jdbc.queryForObject("""
				SELECT id FROM inventory_change WHERE sku_id=?
				ORDER BY inventory_version DESC, changed_at DESC, id DESC LIMIT 1
				""", UUID.class, skuId)).isEqualTo(laterId);
	}

	@Test
	void advancesSkuInventoryVersionForEveryQuantityWriteIncludingNoOpsAndReturnedValues() {
		MigrationSchema schema = migrations.newSchema();
		JdbcTemplate jdbc = schema.jdbcTemplate();
		schema.flyway().migrate();
		UUID productId = UUID.fromString("01998e62-e700-7000-8000-000000000321");
		UUID skuId = UUID.fromString("01998e62-e700-7000-8000-000000000322");
		insertCatalogProduct(jdbc, productId, "Produkt wersjonowany");
		insertCatalogSku(jdbc, skuId, productId, "SKU-VERSION", "10.00", "23.00", 7, "PLN");

		// Two forms may both have displayed version zero. Even a successful no-op submission
		// consumes that version, so the other form cannot also be accepted with its stale token.
		jdbc.update("UPDATE catalog_sku SET available_quantity=available_quantity WHERE id=?", skuId);
		assertThat(jdbc.queryForObject("SELECT inventory_version FROM catalog_sku WHERE id=?", Long.class, skuId))
				.isEqualTo(1L);
		jdbc.update("UPDATE catalog_sku SET available_quantity=9 WHERE id=?", skuId);
		jdbc.update("UPDATE catalog_sku SET available_quantity=7 WHERE id=?", skuId);
		assertThat(jdbc.queryForObject("SELECT available_quantity FROM catalog_sku WHERE id=?", Integer.class, skuId))
				.isEqualTo(7);
		assertThat(jdbc.queryForObject("SELECT inventory_version FROM catalog_sku WHERE id=?", Long.class, skuId))
				.isEqualTo(3L);
	}

	private static void insertCatalogProduct(JdbcTemplate jdbc, UUID id, String name) {
		jdbc.update("""
				INSERT INTO catalog_product (id, name, category, created_at, updated_at)
				VALUES (?, ?, 'Category', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", id, name);
	}

	private static void insertCatalogSku(
			JdbcTemplate jdbc, UUID id, UUID productId, String code,
			String netPrice, String vatRate, int quantity, String currency
	) {
		jdbc.update("""
				INSERT INTO catalog_sku (
					id, product_id, code, base_net_price, vat_rate,
					available_quantity, base_currency, created_at, updated_at
				) VALUES (?, ?, ?, ?::NUMERIC, ?::NUMERIC, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", id, productId, code, netPrice, vatRate, quantity, currency);
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
