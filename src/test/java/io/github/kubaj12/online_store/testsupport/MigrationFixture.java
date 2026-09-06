package io.github.kubaj12.online_store.testsupport;

import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Creates isolated schemas for forward-only Flyway tests in the shared disposable container. */
public final class MigrationFixture {

	private static final AtomicInteger SCHEMA_SEQUENCE = new AtomicInteger();

	private final PostgreSQLContainer container;
	private final JdbcTemplate containerJdbc;

	public MigrationFixture(PostgreSQLContainer container, JdbcTemplate containerJdbc) {
		this.container = container;
		this.containerJdbc = containerJdbc;
	}

	public MigrationSchema newSchema() {
		String schema = "migration_test_%06d".formatted(SCHEMA_SEQUENCE.incrementAndGet());
		containerJdbc.execute("CREATE SCHEMA " + quoteIdentifier(schema));
		return new MigrationSchema(container, schema);
	}

	private static String quoteIdentifier(String identifier) {
		return '"' + identifier.replace("\"", "\"\"") + '"';
	}

	public static final class MigrationSchema {

		private final PostgreSQLContainer container;
		private final String schema;
		private final JdbcTemplate jdbcTemplate;

		private MigrationSchema(PostgreSQLContainer container, String schema) {
			this.container = container;
			this.schema = schema;
			this.jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
					schemaJdbcUrl(container.getJdbcUrl(), schema),
					container.getUsername(),
					container.getPassword()
			));
		}

		public Flyway flyway() {
			return configuredFlyway(null);
		}

		public Flyway flywayTo(MigrationVersion target) {
			return configuredFlyway(target);
		}

		public JdbcTemplate jdbcTemplate() {
			return jdbcTemplate;
		}

		public String name() {
			return schema;
		}

		private Flyway configuredFlyway(MigrationVersion target) {
			var configuration = Flyway.configure()
					.dataSource(
							schemaJdbcUrl(container.getJdbcUrl(), schema),
							container.getUsername(),
							container.getPassword()
					)
					.locations("classpath:db/migration")
					.schemas(schema)
					.defaultSchema(schema)
					.createSchemas(false)
					.validateOnMigrate(true)
					.validateMigrationNaming(true)
					.cleanDisabled(true)
					.baselineOnMigrate(false)
					.outOfOrder(false);
			if (target != null) {
				configuration.target(target);
			}
			return configuration.load();
		}

		private static String schemaJdbcUrl(String jdbcUrl, String schema) {
			String separator = jdbcUrl.contains("?") ? "&" : "?";
			return jdbcUrl + separator + "currentSchema=" + schema;
		}

	}

}
