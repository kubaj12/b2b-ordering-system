package io.github.kubaj12.online_store;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

@Profile("production")
@Configuration(proxyBeanMethods = false)
class ProductionConfiguration {

	private static final int MINIMUM_PASSWORD_LENGTH = 12;
	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

	@Bean
	static BeanFactoryPostProcessor productionConfigurationValidator(Environment environment) {
		return beanFactory -> validateProductionEnvironment(environment);
	}

	private static void validateProductionEnvironment(Environment environment) {
		requireText(environment, "spring.datasource.url");
		requireText(environment, "spring.datasource.username");
		requireText(environment, "spring.datasource.password");

		URI baseUrl = requireUri(environment, "app.web.base-url");
		require(new ApplicationWebProperties(baseUrl).isValidBaseUrl(),
				"app.web.base-url must be an absolute HTTP(S) URL without user info, query, or fragment");
		require("https".equals(baseUrl.getScheme()),
				"app.web.base-url must use HTTPS in production");

		Path imageStorageRoot = requirePath(environment, "app.image-storage.root");
		require(imageStorageRoot.isAbsolute(),
				"app.image-storage.root must be an absolute path in production");

		require(booleanProperty(environment, "server.servlet.session.cookie.secure", false),
				"server.servlet.session.cookie.secure must be true in production");
		require(booleanProperty(environment, "server.servlet.session.cookie.http-only", false),
				"server.servlet.session.cookie.http-only must be true in production");
		require("lax".equals(requireText(
				environment, "server.servlet.session.cookie.same-site").toLowerCase(Locale.ROOT)),
				"server.servlet.session.cookie.same-site must be Lax in production");

		require(booleanProperty(environment, "app.mail.delivery-enabled", false),
				"app.mail.delivery-enabled must be true in production");
		String mailHost = requireText(environment, "spring.mail.host");
		require(!"localhost".equalsIgnoreCase(mailHost),
				"spring.mail.host must be configured for production");
		requirePort(environment, "spring.mail.port");

		String fromAddress = requireText(environment, "app.mail.from-address");
		require(EMAIL_PATTERN.matcher(fromAddress).matches()
					&& !"no-reply@example.test".equalsIgnoreCase(fromAddress),
				"app.mail.from-address must be configured with a valid production address");

		boolean smtpAuthentication = mailBooleanProperty(environment, "mail.smtp.auth", false);
		if (smtpAuthentication) {
			requireText(environment, "spring.mail.username");
			requireText(environment, "spring.mail.password");
		}

		boolean sslEnabled = booleanProperty(environment, "spring.mail.ssl.enabled", false);
		boolean startTlsEnabled = mailBooleanProperty(environment, "mail.smtp.starttls.enable", false);
		boolean startTlsRequired = mailBooleanProperty(environment, "mail.smtp.starttls.required", false);
		require(sslEnabled || (startTlsEnabled && startTlsRequired),
				"SMTP SSL or required STARTTLS must be enabled in production");

		if (booleanProperty(environment, "app.bootstrap.admin.enabled", false)) {
			String email = requireText(environment, "app.bootstrap.admin.email");
			require(EMAIL_PATTERN.matcher(email).matches(),
					"app.bootstrap.admin.email must be valid when bootstrap is enabled");
			String password = requireText(environment, "app.bootstrap.admin.password");
			require(password.length() >= MINIMUM_PASSWORD_LENGTH,
					"app.bootstrap.admin.password must contain at least 12 characters when bootstrap is enabled");
		}
	}

	private static String requireText(Environment environment, String propertyName) {
		String value = environment.getProperty(propertyName);
		require(StringUtils.hasText(value), propertyName + " must be configured in production");
		return value;
	}

	private static URI requireUri(Environment environment, String propertyName) {
		try {
			return new URI(requireText(environment, propertyName));
		}
		catch (URISyntaxException exception) {
			throw new IllegalStateException(propertyName + " must be a valid URI");
		}
	}

	private static Path requirePath(Environment environment, String propertyName) {
		try {
			return Path.of(requireText(environment, propertyName));
		}
		catch (InvalidPathException exception) {
			throw new IllegalStateException(propertyName + " must be a valid path");
		}
	}

	private static int requirePort(Environment environment, String propertyName) {
		String configuredPort = requireText(environment, propertyName);
		try {
			int port = Integer.parseInt(configuredPort);
			require(port >= 1 && port <= 65_535,
					propertyName + " must be an integer between 1 and 65535 in production");
			return port;
		}
		catch (NumberFormatException exception) {
			throw new IllegalStateException(
					propertyName + " must be an integer between 1 and 65535 in production");
		}
	}

	private static boolean mailBooleanProperty(
			Environment environment,
			String mailPropertyName,
			boolean defaultValue
	) {
		String bracketedPropertyName = "spring.mail.properties[" + mailPropertyName + "]";
		if (StringUtils.hasText(environment.getProperty(bracketedPropertyName))) {
			return booleanProperty(environment, bracketedPropertyName, defaultValue);
		}
		return booleanProperty(environment, "spring.mail.properties." + mailPropertyName, defaultValue);
	}

	private static boolean booleanProperty(
			Environment environment,
			String propertyName,
			boolean defaultValue
	) {
		String configuredValue = environment.getProperty(propertyName);
		if (!StringUtils.hasText(configuredValue)) {
			return defaultValue;
		}
		if ("true".equalsIgnoreCase(configuredValue)) {
			return true;
		}
		if ("false".equalsIgnoreCase(configuredValue)) {
			return false;
		}
		throw new IllegalStateException(propertyName + " must be either true or false");
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalStateException(message);
		}
	}

}
