package io.github.kubaj12.online_store.testsupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Clears committed service-test data while retaining the Flyway-managed schema. */
final class PostgreSqlDatabaseCleaner {

	private final DataSource dataSource;
	private final JdbcTemplate jdbcTemplate;
	private final PostgreSQLContainer container;

	PostgreSqlDatabaseCleaner(
			DataSource dataSource,
			JdbcTemplate jdbcTemplate,
			PostgreSQLContainer container
	) {
		this.dataSource = dataSource;
		this.jdbcTemplate = jdbcTemplate;
		this.container = container;
	}

	void clean() {
		verifyDisposableContainerTarget();
		List<String> tables = jdbcTemplate.queryForList("""
				SELECT tablename
				FROM pg_tables
				WHERE schemaname = 'public'
				  AND tablename <> 'flyway_schema_history'
				ORDER BY tablename
				""", String.class);
		if (tables.isEmpty()) {
			return;
		}

		String qualifiedTables = tables.stream()
				.map(table -> "public." + quoteIdentifier(table))
				.reduce((left, right) -> left + ", " + right)
				.orElseThrow();
		jdbcTemplate.execute("TRUNCATE TABLE " + qualifiedTables + " RESTART IDENTITY CASCADE");
	}

	private void verifyDisposableContainerTarget() {
		try (Connection connection = dataSource.getConnection()) {
			String jdbcUrl = connection.getMetaData().getURL();
			if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())
					|| !container.getDatabaseName().equals(connection.getCatalog())
					|| !jdbcUrl.startsWith(container.getJdbcUrl())) {
				throw new IllegalStateException("refusing to clean a database outside the PostgreSQL Testcontainer");
			}
		}
		catch (SQLException exception) {
			throw new IllegalStateException("could not verify the service-test database", exception);
		}
	}

	private static String quoteIdentifier(String identifier) {
		return '"' + identifier.replace("\"", "\"\"") + '"';
	}

}
