package io.github.kubaj12.online_store;

import java.net.URI;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationConfigurationTests {

	private static final String[] PRODUCTION_PROPERTIES = {
		"spring.profiles.active=production",
		"B2B_DB_JDBC_URL=jdbc:postgresql://database.internal:5432/online_store",
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
	};

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(ApplicationConfiguration.class);

	private final ApplicationContextRunner productionContextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(ApplicationConfiguration.class, ProductionConfiguration.class);

	@Test
	void bindsSafeLocalDefaultsWithoutCredentials() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(ApplicationMailProperties.class))
					.extracting(
							ApplicationMailProperties::deliveryEnabled,
							ApplicationMailProperties::fromAddress
					)
					.containsExactly(false, "no-reply@example.test");
			assertThat(context.getBean(ApplicationWebProperties.class).baseUrl())
					.isEqualTo(URI.create("http://localhost:8080"));
			assertThat(context.getBean(ImageStorageProperties.class).root())
					.isEqualTo(Path.of("var/sku-images"));

			BootstrapAdminProperties bootstrap = context.getBean(BootstrapAdminProperties.class);
			assertThat(bootstrap.enabled()).isFalse();
			assertThat(bootstrap.email()).isEmpty();
			assertThat(bootstrap.password()).isEmpty();
			assertThat(bootstrap.toString()).contains("password=<redacted>");
		});
	}

	@Test
	void environmentVariablesOverrideLocalDefaults() {
		String password = "correct-horse-battery-staple";

		contextRunner
				.withPropertyValues(
						"B2B_MAIL_DELIVERY_ENABLED=true",
						"B2B_MAIL_FROM_ADDRESS=orders@example.com",
						"B2B_IMAGE_STORAGE_ROOT=/tmp/catalog-images",
						"B2B_BASE_URL=https://orders.example.com/application",
						"B2B_BOOTSTRAP_ADMIN_ENABLED=true",
						"B2B_BOOTSTRAP_ADMIN_EMAIL=admin@example.com",
						"B2B_BOOTSTRAP_ADMIN_PASSWORD=" + password
				)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(ApplicationMailProperties.class).deliveryEnabled()).isTrue();
					assertThat(context.getBean(ApplicationMailProperties.class).fromAddress())
							.isEqualTo("orders@example.com");
					assertThat(context.getBean(ImageStorageProperties.class).root())
							.isEqualTo(Path.of("/tmp/catalog-images"));
					assertThat(context.getBean(ApplicationWebProperties.class).baseUrl())
							.isEqualTo(URI.create("https://orders.example.com/application"));

					BootstrapAdminProperties bootstrap = context.getBean(BootstrapAdminProperties.class);
					assertThat(bootstrap.enabled()).isTrue();
					assertThat(bootstrap.email()).isEqualTo("admin@example.com");
					assertThat(bootstrap.password()).isEqualTo(password);
					assertThat(bootstrap.toString()).doesNotContain(password, "admin@example.com");
				});
	}

	@Test
	void rejectsUnsupportedBaseUrl() {
		contextRunner
				.withPropertyValues("B2B_BASE_URL=ftp://orders.example.com")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(causalMessages(context.getStartupFailure()))
							.contains("app.web.base-url must be an absolute HTTP(S) URL");
				});
	}

	@Test
	void rejectsIncompleteBootstrapCredentialsWithoutRevealingThePassword() {
		String invalidPassword = "too-short";

		contextRunner
				.withPropertyValues(
						"B2B_BOOTSTRAP_ADMIN_ENABLED=true",
						"B2B_BOOTSTRAP_ADMIN_EMAIL=admin@example.com",
						"B2B_BOOTSTRAP_ADMIN_PASSWORD=" + invalidPassword
				)
				.run(context -> {
					assertThat(context).hasFailed();
					String failureMessages = causalMessages(context.getStartupFailure());
					assertThat(failureMessages)
							.contains("bootstrap administrator password must contain at least 12 characters")
							.doesNotContain(invalidPassword);
				});
	}

	@Test
	void productionRejectsMissingDatabaseConfiguration() {
		productionContextRunner
				.withPropertyValues("spring.profiles.active=production")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasMessageContaining("spring.datasource.url must be configured in production");
				});
	}

	@Test
	void productionAcceptsCompleteSecureConfiguration() {
		productionContextRunner
				.withPropertyValues(PRODUCTION_PROPERTIES)
				.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void productionRejectsAnInsecureBaseUrl() {
		productionContextRunner
				.withPropertyValues(PRODUCTION_PROPERTIES)
				.withPropertyValues("B2B_BASE_URL=http://orders.example.com")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasMessageContaining("app.web.base-url must use HTTPS in production");
				});
	}

	@Test
	void productionRejectsAnInsecureSessionCookie() {
		productionContextRunner
				.withPropertyValues(PRODUCTION_PROPERTIES)
				.withPropertyValues("B2B_SESSION_COOKIE_SECURE=false")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasMessageContaining("server.servlet.session.cookie.secure must be true in production");
				});
	}

	@Test
	void productionRejectsTheLocalSmtpFallback() {
		productionContextRunner
				.withPropertyValues(PRODUCTION_PROPERTIES)
				.withPropertyValues("B2B_MAIL_HOST=localhost")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasMessageContaining("spring.mail.host must be configured for production");
				});
	}

	@Test
	void productionRequiresCredentialsWhenSmtpAuthenticationIsEnabled() {
		productionContextRunner
				.withPropertyValues(PRODUCTION_PROPERTIES)
				.withPropertyValues("B2B_MAIL_SMTP_AUTH=true")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasMessageContaining("spring.mail.username must be configured in production");
				});
	}

	@Test
	void productionRequiresAnExplicitSmtpPort() {
		productionContextRunner
				.withPropertyValues(PRODUCTION_PROPERTIES)
				.withPropertyValues("B2B_MAIL_PORT=")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasMessageContaining("spring.mail.port must be configured in production");
				});
	}

	@Test
	void productionRequiresStartTlsRatherThanOpportunisticUpgrade() {
		productionContextRunner
				.withPropertyValues(PRODUCTION_PROPERTIES)
				.withPropertyValues("B2B_MAIL_STARTTLS_REQUIRED=false")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasMessageContaining("SMTP SSL or required STARTTLS must be enabled in production");
				});
	}

	@Test
	void productionRejectsRelativeImageStorage() {
		productionContextRunner
				.withPropertyValues(PRODUCTION_PROPERTIES)
				.withPropertyValues("B2B_IMAGE_STORAGE_ROOT=./images")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasMessageContaining("app.image-storage.root must be an absolute path in production");
				});
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
