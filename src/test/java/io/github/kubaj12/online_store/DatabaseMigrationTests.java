package io.github.kubaj12.online_store;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

	private static List<MigrationVersion> pendingVersions(MigrationSchema schema) {
		return Arrays.stream(schema.flyway().info().pending())
				.map(MigrationInfo::getVersion)
				.filter(Objects::nonNull)
				.toList();
	}

}
