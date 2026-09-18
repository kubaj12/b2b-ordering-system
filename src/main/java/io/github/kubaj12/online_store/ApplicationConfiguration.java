package io.github.kubaj12.online_store;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import io.github.kubaj12.online_store.identityaccess.domain.NormalizedEmail;
import io.github.kubaj12.online_store.identityaccess.domain.PasswordPolicy;
import io.github.kubaj12.online_store.identityaccess.domain.LoginThrottlePolicy;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
		ApplicationMailProperties.class,
		ApplicationWebProperties.class,
		BootstrapAdminProperties.class,
		ImageStorageProperties.class,
		LoginThrottleProperties.class
})
class ApplicationConfiguration {

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B, 12);
	}

	@Bean
	LoginThrottlePolicy loginThrottlePolicy(LoginThrottleProperties properties) {
		return new LoginThrottlePolicy(properties.maxFailures(), properties.window(), properties.blockDuration(),
				properties.retention());
	}

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	static BeanFactoryPostProcessor bootstrapConfigurationValidator(Environment environment) {
		return beanFactory -> {
			String enabled = environment.getProperty("app.bootstrap.admin.enabled");
			if (enabled == null || enabled.isBlank() || "false".equalsIgnoreCase(enabled)) {
				return;
			}
			if (!"true".equalsIgnoreCase(enabled)) {
				throw new IllegalStateException("app.bootstrap.admin.enabled must be either true or false");
			}
			try {
				NormalizedEmail.of(environment.getProperty("app.bootstrap.admin.email"));
			}
			catch (IllegalArgumentException exception) {
				throw new IllegalStateException("app.bootstrap.admin.email must be a valid address when bootstrap is enabled");
			}
			try {
				String password = environment.getProperty("app.bootstrap.admin.password");
				PasswordPolicy.validate(password);
			}
			catch (IllegalArgumentException exception) {
				String password = environment.getProperty("app.bootstrap.admin.password");
				String message = PasswordPolicy.hasMinimumCharacters(password)
						? "app.bootstrap.admin.password violates the Unicode or UTF-8 byte limit when bootstrap is enabled"
						: "app.bootstrap.admin.password must contain at least 12 characters when bootstrap is enabled";
				throw new IllegalStateException(message);
			}
		};
	}
}
