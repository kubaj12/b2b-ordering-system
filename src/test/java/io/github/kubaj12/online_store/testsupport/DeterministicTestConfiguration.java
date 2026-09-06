package io.github.kubaj12.online_store.testsupport;

import javax.sql.DataSource;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class DeterministicTestConfiguration {

	@Bean
	@Primary
	TestClock testClock() {
		return new TestClock();
	}

	@Bean
	@Primary
	RecordingMailDelivery recordingMailDelivery() {
		return new RecordingMailDelivery();
	}

	@Bean
	@Primary
	InMemoryImageStorage inMemoryImageStorage() {
		return new InMemoryImageStorage();
	}

	@Bean
	PostgreSqlDatabaseCleaner postgreSqlDatabaseCleaner(
			DataSource dataSource,
			JdbcTemplate jdbcTemplate,
			PostgreSQLContainer container
	) {
		return new PostgreSqlDatabaseCleaner(dataSource, jdbcTemplate, container);
	}

}
