package io.github.kubaj12.online_store;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OnlineStoreApplicationTests {

	@Autowired
	private Flyway flyway;

	@Autowired
	private PostgreSQLContainer postgresContainer;

	@Test
	void startsFromAnEmptyDatabaseAtTheLatestMigration() {
		var migrationInfo = flyway.info();

		assertThat(migrationInfo.current()).isNotNull();
		assertThat(migrationInfo.current().getVersion()).isEqualTo(MigrationVersion.fromVersion("002"));
		assertThat(migrationInfo.pending()).isEmpty();
		assertThat(postgresContainer.getDockerImageName()).isEqualTo(TestcontainersConfiguration.POSTGRES_IMAGE);
	}

}
