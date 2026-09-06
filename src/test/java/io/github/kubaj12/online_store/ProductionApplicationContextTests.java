package io.github.kubaj12.online_store;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("production")
@SpringBootTest(properties = {
		"B2B_DB_JDBC_URL=jdbc:postgresql://127.0.0.1:1/service_connection_must_win",
		"B2B_DB_USERNAME=online_store",
		"B2B_DB_PASSWORD=database-password",
		"B2B_MAIL_DELIVERY_ENABLED=true",
		"B2B_MAIL_FROM_ADDRESS=orders@example.com",
		"B2B_MAIL_HOST=smtp.example.com",
		"B2B_MAIL_PORT=587",
		"B2B_MAIL_STARTTLS_ENABLED=true",
		"B2B_MAIL_STARTTLS_REQUIRED=true",
		"B2B_IMAGE_STORAGE_ROOT=/srv/online-store/images",
		"B2B_BASE_URL=https://orders.example.com",
		"B2B_SESSION_COOKIE_SECURE=true"
})
class ProductionApplicationContextTests {

	@Autowired
	private Flyway flyway;

	@Autowired
	private ApplicationMailProperties mailProperties;

	@Test
	void startsTheCompleteProductionContextWithSecureExplicitConfiguration() {
		assertThat(flyway.info().current()).isNotNull();
		assertThat(flyway.info().pending()).isEmpty();
		assertThat(mailProperties.deliveryEnabled()).isTrue();
	}

}
