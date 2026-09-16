package io.github.kubaj12.online_store;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgresql")
@Tag("migration")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/service_connection_must_win")
class OnlineStoreApplicationTests {

	@Autowired
	private Flyway flyway;

	@Autowired
	private PostgreSQLContainer postgresContainer;

	@Autowired
	private ApplicationMailProperties mailProperties;

	@Autowired
	private ImageStorageProperties imageStorageProperties;

	@Autowired
	private BootstrapAdminProperties bootstrapAdminProperties;

	@Autowired
	private ServerProperties serverProperties;

	@Autowired
	private JavaMailSender mailSender;

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	void startsFromAnEmptyDatabaseAtTheLatestMigration() {
		var migrationInfo = flyway.info();

		assertThat(migrationInfo.current()).isNotNull();
		assertThat(migrationInfo.pending()).isEmpty();
		assertThat(postgresContainer.getDockerImageName()).isEqualTo(TestcontainersConfiguration.POSTGRES_IMAGE);
		assertThat(mailSender).isNotNull();
		assertThat(mailProperties.deliveryEnabled()).isFalse();
		assertThat(imageStorageProperties.root()).isAbsolute();
		assertThat(bootstrapAdminProperties.enabled()).isFalse();
		assertThat(applicationContext.getBeansOfType(org.springframework.boot.ApplicationRunner.class)).isEmpty();

		var sessionCookie = serverProperties.getServlet().getSession().getCookie();
		assertThat(sessionCookie.getName()).isEqualTo("B2BSESSION");
		assertThat(sessionCookie.getSecure()).isFalse();
		assertThat(sessionCookie.getHttpOnly()).isTrue();
		assertThat(sessionCookie.getSameSite()).isEqualTo(Cookie.SameSite.LAX);
	}

}
