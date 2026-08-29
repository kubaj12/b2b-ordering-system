package io.github.kubaj12.online_store;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class ProductionStartupValidationTests {

	@Test
	void missingDatabaseConfigurationFailsBeforeInfrastructureInitialization() {
		Throwable failure = catchThrowable(() -> productionApplication().run(
				"--spring.profiles.active=production"
		));

		assertThat(causalMessages(failure))
				.contains("spring.datasource.url must be configured in production")
				.doesNotContain("Failed to configure a DataSource", "Connection refused");
	}

	@Test
	void laterProductionValidationFailureStillPrecedesDatasourceConnection() {
		Throwable failure = catchThrowable(() -> productionApplication().run(
				"--spring.profiles.active=production",
				"--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/must-not-connect",
				"--spring.datasource.username=online_store",
				"--spring.datasource.password=database-password",
				"--app.web.base-url=http://orders.example.com",
				"--app.image-storage.root=/srv/online-store/images",
				"--server.servlet.session.cookie.secure=true",
				"--server.servlet.session.cookie.http-only=true",
				"--server.servlet.session.cookie.same-site=lax",
				"--app.mail.delivery-enabled=true",
				"--app.mail.from-address=orders@example.com",
				"--spring.mail.host=smtp.example.com",
				"--spring.mail.port=587",
				"--spring.mail.properties[mail.smtp.starttls.enable]=true",
				"--spring.mail.properties[mail.smtp.starttls.required]=true",
				"--app.bootstrap.admin.enabled=false"
		));

		assertThat(causalMessages(failure))
				.contains("app.web.base-url must use HTTPS in production")
				.doesNotContain("Connection refused", "must-not-connect");
	}

	private static SpringApplication productionApplication() {
		SpringApplication application = new SpringApplication(OnlineStoreApplication.class);
		application.setWebApplicationType(WebApplicationType.NONE);
		application.setLogStartupInfo(false);
		application.setRegisterShutdownHook(false);
		return application;
	}

	private static String causalMessages(Throwable failure) {
		StringBuilder messages = new StringBuilder();
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current.getMessage() != null) {
				messages.append(current.getMessage()).append(System.lineSeparator());
			}
		}
		return messages.toString();
	}

}
