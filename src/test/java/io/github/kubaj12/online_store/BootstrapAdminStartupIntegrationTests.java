package io.github.kubaj12.online_store;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.kubaj12.online_store.testsupport.MigrationFixture;
import io.github.kubaj12.online_store.testsupport.MigrationFixture.MigrationSchema;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgresql")
class BootstrapAdminStartupIntegrationTests extends PostgreSqlServiceTestSupport {

	@Autowired
	private PostgreSQLContainer container;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void realStartupCreatesThenPreservesTheSeedAcrossRestart() {
		MigrationSchema schema = new MigrationFixture(container, jdbcTemplate).newSchema();
		Map<String, Object> first;
		try (ConfigurableApplicationContext context = start(schema, "first-bootstrap-password")) {
			first = schema.jdbcTemplate().queryForMap("SELECT * FROM identity_user WHERE email = ?", "admin@example.test");
		}
		try (ConfigurableApplicationContext ignored = start(schema, "second-bootstrap-password")) {
			assertThat(schema.jdbcTemplate().queryForMap("SELECT * FROM identity_user WHERE email = ?", "admin@example.test"))
					.isEqualTo(first);
		}
	}

	private ConfigurableApplicationContext start(MigrationSchema schema, String password) {
		SpringApplication application = new SpringApplication(OnlineStoreApplication.class);
		application.setWebApplicationType(WebApplicationType.NONE);
		application.setRegisterShutdownHook(false);
		return application.run(
				"--spring.docker.compose.enabled=false",
				"--spring.datasource.url=" + schemaUrl(schema),
				"--spring.datasource.username=" + container.getUsername(),
				"--spring.datasource.password=" + container.getPassword(),
				"--spring.flyway.schemas=" + schema.name(),
				"--spring.flyway.default-schema=" + schema.name(),
				"--spring.flyway.create-schemas=false",
				"--app.bootstrap.admin.enabled=true",
				"--app.bootstrap.admin.email=admin@example.test",
				"--app.bootstrap.admin.password=" + password
		);
	}

	private String schemaUrl(MigrationSchema schema) {
		String separator = container.getJdbcUrl().contains("?") ? "&" : "?";
		return container.getJdbcUrl() + separator + "currentSchema=" + schema.name();
	}

}
